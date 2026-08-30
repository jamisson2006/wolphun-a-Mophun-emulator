package wolphun.vm;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import java.util.HashMap;
import java.util.Map;

/**
 * Som avancado do mophun (VSound) — versao Android com MIXER DE SOFTWARE proprio.
 * MESMA API publica da versao desktop (Sdk usa sem mudanca) e MESMA logica de mix.
 *
 * Um unico AudioTrack (MODE_STREAM, 16-bit stereo @ OUT_RATE) alimentado por uma thread;
 * cada "voz" e reamostrada pela FREQUENCIA atual -> PITCH em tempo real (motor do Rally) +
 * LOOP + mix de varios sons. Todos os formatos viram PCM16 mono na carga:
 *   - fmt2 ADPCM -> IMA (high-nibble); 8-bit unsigned -> 16-bit ((v-128)<<8); 16-bit -> direto.
 * Header SHDR (36B LE): format@8[1=PCM,2=ADPCM], loopStart@12, loopEnd@16 (samples),
 *   freq@20, bits@24, ch@25, "BODY"@28, bodySize@32 -> corpo@36.
 * Controles: Play=1(flags:bits0-15=loopcount,0xFFFF=infinito) Stop=2 Pause=3 Resume=4
 *   Volume=5 Freq=6 Pan=7 Status=8 StopLooping=9 MasterVolume=12.
 */
public final class VSound {
    final Pip2 vm;
    public boolean enabled = true;
    // 44100 Hz: taxa mais universalmente suportada (o AudioTrack de alguns AVDs fica MUDO em 22050).
    // O mixer reamostra cada voz por step=freq/OUT_RATE, entao a taxa de saida e independente dos sons.
    static final int OUT_RATE = 44100;

    static final class Snd { short[] pcm; int freq=11025, loopStart=0, loopEnd=0; }
    static final class Voice { Snd snd; double pos; double step; int loop; float vol=1f, pan=0f; boolean playing; }

    final Map<Integer,Snd> sounds = new HashMap<Integer,Snd>();
    final Map<Integer,Voice> voices = new HashMap<Integer,Voice>();
    int nextHandle = 1;
    float masterVol = 1f;

    AudioTrack track;
    Thread mixThread;
    volatile boolean running = false;

    public VSound(Pip2 vm){ this.vm = vm; }

    public int init(){
        if(!enabled) return -1;
        if(track!=null) return 0;
        try {
            int min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if(min <= 0) min = OUT_RATE * 4;                     // fallback se o device nao reportar (frame=4B)
            int buf = min * 4;                                   // 4x min. min ja e MULTIPLO DO FRAME (4B stereo16);
                                                                 // antes usava max(min*2, 22050) e 22050 NAO e mult. de 4
                                                                 // -> "Invalid audio buffer size" -> track nulo -> MUDO.
            track = null;
            // API moderna (Builder + AudioAttributes): o construtor legado STREAM_MUSIC muitas vezes
            // NAO emite som em Android novo (alvo API 29). Usa Builder; cai no legado se algo falhar.
            try {
                if(Build.VERSION.SDK_INT >= 23){
                    track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                        .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(OUT_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                        .setBufferSizeInBytes(buf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                }
            } catch(Throwable t){ track = null; }
            if(track == null){                                   // fallback legado
                track = new AudioTrack(AudioManager.STREAM_MUSIC, OUT_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                                       AudioFormat.ENCODING_PCM_16BIT, buf, AudioTrack.MODE_STREAM);
            }
            track.play();
            running = true;
            mixThread = new Thread(new Runnable(){ public void run(){ mixLoop(); } }, "vsound-mix");
            mixThread.setDaemon(true);
            mixThread.start();
            return 0;
        } catch(Throwable t){ track=null; return -1; }
    }

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
                pcm = decodeImaAdpcm(bodyOff, bodySize);
            } else if(bits==16){
                int n = bodySize/2; pcm = new short[n];
                for(int i=0;i<n;i++) pcm[i] = (short)((vm.read8(bodyOff+i*2)&0xff) | (vm.read8(bodyOff+i*2+1)<<8));
            } else {
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

    public int ctrl(int handle, int control, int param){
        Snd s = sounds.get(handle);
        synchronized(voices){
            Voice v = voices.get(handle);
            switch(control){
                case 1:
                    if(s==null || !enabled || track==null) return 0;
                    if(v==null){ v = new Voice(); voices.put(handle, v); }
                    v.snd = s; v.pos = s.loopStart; v.step = (double)s.freq / OUT_RATE;
                    int lc = param & 0xffff;
                    v.loop = (lc==0xffff) ? -1 : (lc>0 ? lc : 0);
                    v.playing = true;
                    return 0;
                case 2: if(v!=null) v.playing=false; return 0;
                case 3: if(v!=null) v.playing=false; return 0;
                case 4: if(v!=null && enabled) v.playing=true; return 0;
                case 5: if(v!=null){ if(param==-1) return (int)(v.vol*127); v.vol=clampf(param/127f); } return 0;
                case 6: if(v!=null && param>0) v.step=(double)param/OUT_RATE; return 0;
                case 7: if(v!=null){ if(param==-1) return (int)(v.pan*127); v.pan=Math.max(-1f,Math.min(1f,param/127f)); } return 0;
                case 8: return (v!=null && v.playing) ? 1 : 0;
                case 9: if(v!=null && v.loop<0) v.loop=0; return 0;
                case 12: if(param!=-1) masterVol=clampf(param/127f); return (int)(masterVol*127);
                default: return 0;
            }
        }
    }

    void mixLoop(){
        final int FR = 512;
        short[] out = new short[FR*2];
        int[] mixL = new int[FR], mixR = new int[FR];
        while(running){
            renderFrames(mixL, mixR, FR);
            for(int i=0;i<FR;i++){
                int l=mixL[i], r=mixR[i];
                if(l>32767)l=32767; else if(l<-32768)l=-32768;
                if(r>32767)r=32767; else if(r<-32768)r=-32768;
                out[i*2]=(short)l; out[i*2+1]=(short)r;
            }
            try { track.write(out, 0, out.length); } catch(Throwable t){}
        }
    }

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

    /** silencia as vozes que estao tocando, mas MANTEM o motor de audio vivo (AudioTrack+thread).
     *  Usado ao pausar / trocar de MIDI — NAO derruba o VSound (senao ele nunca mais volta, pois o
     *  jogo so chama vSoundInit uma vez no inicio). */
    public void stopVoices(){
        synchronized(voices){ for(Voice v : voices.values()) v.playing=false; }
    }

    public int dispose(){
        running = false;
        try { if(mixThread!=null) mixThread.join(300); } catch(Throwable t){}
        try { if(track!=null){ track.stop(); track.flush(); track.release(); } } catch(Throwable t){}
        track=null; mixThread=null;
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
    short[] decodeImaAdpcm(int bodyOff, int bodySize){
        short[] out = new short[bodySize*2];
        int pred=0, idx=0, o=0;
        for(int i=0;i<bodySize;i++){
            int by = vm.read8(bodyOff+i)&0xff;
            for(int half=0; half<2; half++){
                int nib = (half==0) ? (by>>4) : (by&0x0f);
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
