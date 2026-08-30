package wolphun.vm;

import javax.sound.sampled.*;
import java.util.*;

/**
 * Som avancado do mophun (modulo VSound: vSoundInit/GetHandle/Ctrl/Dispose).
 *
 * MIXER DE SOFTWARE proprio (nao mais 1 Clip por som): uma unica SourceDataLine 16-bit
 * stereo @ OUT_RATE alimentada por uma thread; cada "voz" (handle tocando) e reamostrada
 * pela sua FREQUENCIA atual -> permite PITCH em tempo real (motor do carro no Rally) +
 * LOOP + mix de varios sons. Todos os formatos viram PCM16 mono na carga:
 *   - fmt2 ADPCM  -> IMA (high-nibble, ver decodeImaAdpcm)
 *   - 8-bit PCM   -> unsigned (silencio=128) -> 16-bit signed ((v-128)<<8)  [tira o "chiado"]
 *   - 16-bit PCM  -> signed LE direto
 * Layout NativeSoundHeader (SHDR, 36B LE): magic@0, headerSize@4, format@8[1=PCM,2=ADPCM],
 *   loopStart@12, loopEnd@16 (em SAMPLES), freq@20, bits@24, ch@25, prio@26, "BODY"@28,
 *   bodySize@32 -> corpo@36.
 * Controles (SNDCTRL): Play=1 Stop=2 Pause=3 Resume=4 Volume=5 Freq=6 Pan=7 Status=8
 *   StopLooping=9 ... MasterVolume=12. Play recebe flags (bits0-15=loopcount, 0xFFFF=infinito).
 */
public final class VSound {
    final Pip2 vm;
    public boolean enabled = true;
    static final int OUT_RATE = 22050;

    /** som decodificado (PCM16 mono) + metadados. */
    static final class Snd { short[] pcm; int freq=11025, loopStart=0, loopEnd=0; }
    /** estado de reproducao de um handle. */
    static final class Voice { Snd snd; double pos; double step; int loop; float vol=1f, pan=0f; boolean playing; }

    final Map<Integer,Snd> sounds = new HashMap<Integer,Snd>();
    final Map<Integer,Voice> voices = new HashMap<Integer,Voice>();   // acesso protegido por 'voices' (lock)
    int nextHandle = 1;
    float masterVol = 1f;

    SourceDataLine line;
    Thread mixThread;
    volatile boolean running = false;

    public VSound(Pip2 vm){ this.vm = vm; }

    public int init(){
        if(!enabled) return -1;
        if(line!=null) return 0;                                 // ja inicializado
        try {
            AudioFormat af = new AudioFormat(OUT_RATE, 16, 2, true, false);   // stereo 16-bit signed LE
            line = AudioSystem.getSourceDataLine(af);
            line.open(af, OUT_RATE * 4 / 5);                     // ~200ms de buffer
            line.start();
            running = true;
            mixThread = new Thread(new Runnable(){ public void run(){ mixLoop(); } }, "vsound-mix");
            mixThread.setDaemon(true);
            mixThread.start();
            return 0;
        } catch(Throwable t){ line=null; return -1; }            // headless/sem device: no-op gracioso
    }

    // vSoundGetHandle(soundData): decodifica p/ PCM16 mono e guarda um Snd.
    public int getHandle(int dataPtr){
        try {
            int format    = vm.read32(dataPtr+8);
            int loopStart = vm.read32(dataPtr+12);
            int loopEnd   = vm.read32(dataPtr+16);
            int freq      = vm.read32(dataPtr+20);
            int bits      = vm.read8(dataPtr+24);
            int bodySize  = vm.read32(dataPtr+32);
            int bodyOff   = dataPtr + 36;
            if(bodySize<=0 || bodySize > (16<<20)) return -1;
            if(freq<=0) freq = 8000;
            short[] pcm;
            if(format==2){
                pcm = decodeImaAdpcm(bodyOff, bodySize);         // IMA ADPCM -> PCM16
            } else if(bits==16){
                int n = bodySize/2; pcm = new short[n];
                for(int i=0;i<n;i++) pcm[i] = (short)((vm.read8(bodyOff+i*2)&0xff) | (vm.read8(bodyOff+i*2+1)<<8));
            } else {                                             // 8-bit UNSIGNED (silencio=128) -> 16-bit signed
                pcm = new short[bodySize];
                for(int i=0;i<bodySize;i++) pcm[i] = (short)(((vm.read8(bodyOff+i)&0xff)-128) << 8);
            }
            Snd s = new Snd(); s.pcm = pcm; s.freq = freq;
            s.loopStart = (loopStart>=0 && loopStart<pcm.length) ? loopStart : 0;
            s.loopEnd   = (loopEnd>0 && loopEnd<=pcm.length)     ? loopEnd   : pcm.length;
            int h = nextHandle++;
            sounds.put(h, s);
            return h;
        } catch(Throwable t){ return -1; }
    }

    // vSoundCtrl / vSoundCtrlEx: controla uma voz. param=-1 (em Volume/Pan) le o valor atual.
    public int ctrl(int handle, int control, int param){
        Snd s = sounds.get(handle);
        synchronized(voices){
            Voice v = voices.get(handle);
            switch(control){
                case 1:                                          // PLAY (param = flags; bits0-15 = loopcount, 0xFFFF = infinito)
                    if(s==null || !enabled || line==null) return 0;
                    if(v==null){ v = new Voice(); voices.put(handle, v); }
                    v.snd = s; v.pos = s.loopStart; v.step = (double)s.freq / OUT_RATE;
                    int lc = param & 0xffff;
                    v.loop = (lc==0xffff) ? -1 : (lc>0 ? lc : 0);
                    v.playing = true;
                    return 0;
                case 2: if(v!=null) v.playing=false; return 0;   // STOP
                case 3: if(v!=null) v.playing=false; return 0;   // PAUSE
                case 4: if(v!=null && enabled) v.playing=true; return 0;  // RESUME
                case 5: if(v!=null){ if(param==-1) return (int)(v.vol*127); v.vol=clampf(param/127f); } return 0;  // VOLUME 0..127
                case 6: if(v!=null && param>0) v.step=(double)param/OUT_RATE; return 0;  // FREQ (pitch em Hz)
                case 7: if(v!=null){ if(param==-1) return (int)(v.pan*127); v.pan=Math.max(-1f,Math.min(1f,param/127f)); } return 0;  // PAN -127..127
                case 8: return (v!=null && v.playing) ? 1 : 0;   // STATUS (tocando?)
                case 9: if(v!=null && v.loop<0) v.loop=0; return 0;  // STOPLOOPING (termina a passada atual)
                case 12: if(param!=-1) masterVol=clampf(param/127f); return (int)(masterVol*127);  // MASTERVOLUME
                default: return 0;
            }
        }
    }

    // ---------------- mixer ----------------
    void mixLoop(){
        final int FR = 512;                                      // frames por lote
        byte[] buf = new byte[FR*4];
        int[] mixL = new int[FR], mixR = new int[FR];
        while(running){
            renderFrames(mixL, mixR, FR);
            for(int i=0;i<FR;i++){
                int l=mixL[i], r=mixR[i];
                if(l>32767)l=32767; else if(l<-32768)l=-32768;
                if(r>32767)r=32767; else if(r<-32768)r=-32768;
                buf[i*4]=(byte)l; buf[i*4+1]=(byte)(l>>8); buf[i*4+2]=(byte)r; buf[i*4+3]=(byte)(r>>8);
            }
            try { line.write(buf,0,buf.length); } catch(Throwable t){ /* underrun/close */ }
        }
    }

    // mistura as vozes ativas em mixL/mixR (int, pre-clamp). Testavel sem device de audio.
    void renderFrames(int[] mixL, int[] mixR, int frames){
        for(int i=0;i<frames;i++){ mixL[i]=0; mixR[i]=0; }
        synchronized(voices){
            for(Voice v : voices.values()){
                if(!v.playing || v.snd==null) continue;
                Snd s = v.snd; short[] p = s.pcm;
                float g  = v.vol * masterVol;
                float gl = g * (v.pan>0 ? 1f-v.pan : 1f);
                float gr = g * (v.pan<0 ? 1f+v.pan : 1f);
                for(int i=0;i<frames;i++){
                    if(v.pos >= s.loopEnd){
                        if(v.loop!=0){ if(v.loop>0) v.loop--; v.pos = s.loopStart + (v.pos - s.loopEnd); if(v.pos<s.loopStart||v.pos>=s.loopEnd) v.pos=s.loopStart; }
                        else { v.playing=false; break; }
                    }
                    int ip = (int)v.pos;
                    short sm = (ip>=0 && ip<p.length) ? p[ip] : 0;
                    mixL[i] += (int)(sm*gl);
                    mixR[i] += (int)(sm*gr);
                    v.pos += v.step;
                }
            }
        }
    }

    static float clampf(float v){ return v<0?0:v>1?1:v; }

    public int dispose(){
        running = false;
        try { if(mixThread!=null) mixThread.join(300); } catch(Throwable t){}
        try { if(line!=null){ line.stop(); line.flush(); line.close(); } } catch(Throwable t){}
        line=null; mixThread=null;
        sounds.clear();
        synchronized(voices){ voices.clear(); }
        return 0;
    }
    public int disposeHandle(int h){
        sounds.remove(h);
        synchronized(voices){ voices.remove(h); }
        return 0;
    }

    // ---------------- IMA ADPCM (mophun SNDFORMAT_ADPCM=2) ----------------
    static final int[] IMA_STEP = {
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,
        130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,
        1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,
        5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,
        27086,29794,32767 };
    static final int[] IMA_IDX = {-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8};
    // decodifica corpo IMA ADPCM (2 samples/byte, HIGH nibble primeiro) -> PCM16 mono.
    short[] decodeImaAdpcm(int bodyOff, int bodySize){
        short[] out = new short[bodySize*2];
        int pred=0, idx=0, o=0;
        for(int i=0;i<bodySize;i++){
            int by = vm.read8(bodyOff+i)&0xff;
            for(int half=0; half<2; half++){
                int nib = (half==0) ? (by>>4) : (by&0x0f);       // HIGH nibble primeiro (variante mophun)
                int step = IMA_STEP[idx];
                int diff = step>>3;
                if((nib&1)!=0) diff += step>>2;
                if((nib&2)!=0) diff += step>>1;
                if((nib&4)!=0) diff += step;
                if((nib&8)!=0) pred -= diff; else pred += diff;
                if(pred>32767) pred=32767; else if(pred<-32768) pred=-32768;
                idx += IMA_IDX[nib];
                if(idx<0) idx=0; else if(idx>88) idx=88;
                out[o++] = (short)pred;
            }
        }
        return out;
    }
}
