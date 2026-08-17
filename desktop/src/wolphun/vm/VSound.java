package wolphun.vm;

import javax.sound.sampled.*;
import java.util.*;

/**
 * Som avancado do mophun (modulo VSound: vSoundInit/GetHandle/Ctrl/Dispose).
 * Toca PCM via javax.sound.sampled.Clip. Layout NativeSoundHeader (36B, LE):
 *   magic[4]@0, headerSize@4, format@8 (1=PCM,2=ADPCM), loopStart@12, loopEnd@16,
 *   frequency@20, bitsPerSample@24, channelCount@25, priority@26, pad@27,
 *   magicBody[4]@28, bodySize@32 -> corpo PCM em @36.
 * Codigos de controle: Play=1 Stop=2 Pause=3 Resume=4 Volume=5 Freq=6 StopLooping=9
 *   MasterVolume=12 ChannelCount=14 (estes 2 nao exigem handle).
 */
public final class VSound {
    final Pip2 vm;
    final Map<Integer,Clip> clips = new HashMap<Integer,Clip>();
    int nextHandle = 1;
    public boolean enabled = true;

    public VSound(Pip2 vm){ this.vm = vm; }

    public int init(){ return 0; }                    // SND_OK

    // vSoundGetHandle(soundData): monta um Clip a partir do header + corpo PCM
    public int getHandle(int dataPtr){
        try {
            int format   = vm.read32(dataPtr+8);
            int freq     = vm.read32(dataPtr+20);
            int bits     = vm.read8(dataPtr+24);
            int channels = vm.read8(dataPtr+25);
            int bodySize = vm.read32(dataPtr+32);
            int bodyOff  = dataPtr + 36;
            if(bodySize<=0 || bodySize > (8<<20)) return -1;
            int h = nextHandle++;
            if(format==2){ clips.put(h, null); return h; }   // ADPCM: handle silencioso (decodificacao futura)
            if(channels<1||channels>2) channels=1;
            if(bits!=8 && bits!=16) bits=8;
            if(freq<=0) freq=8000;
            byte[] pcm = new byte[bodySize];
            for(int i=0;i<bodySize;i++) pcm[i]=(byte)vm.read8(bodyOff+i);
            boolean signed = (bits==16);                     // 8-bit = unsigned, 16-bit = signed LE (comum no mophun)
            AudioFormat af = new AudioFormat(freq, bits, channels, signed, false);
            Clip clip = AudioSystem.getClip();
            clip.open(af, pcm, 0, pcm.length - (pcm.length % (bits/8*channels)));
            clips.put(h, clip);
            return h;
        } catch(Throwable t){ return -1; }
    }

    public int ctrl(int handle, int control, int param){
        Clip c = clips.get(handle);
        switch(control){
            case 1:  if(c!=null && enabled){ c.stop(); c.setFramePosition(0); c.start(); } return 0;   // Play
            case 2:  if(c!=null) c.stop();  return 0;                                                   // Stop
            case 3:  if(c!=null) c.stop();  return 0;                                                   // Pause
            case 4:  if(c!=null && enabled) c.start(); return 0;                                        // Resume
            case 5:  if(c!=null){ if(param==-1) return getVol(c); setVol(c,param); } return 0;          // Volume
            case 9:  if(c!=null) c.loop(0); return 0;                                                   // StopLooping
            case 12: case 14: return 0;                                                                 // Master/ChannelCount
            default: return 0;
        }
    }
    public int dispose(){
        for(Clip c: clips.values()) if(c!=null){ try{ c.stop(); c.close(); }catch(Throwable t){} }
        clips.clear(); return 0;
    }
    public int disposeHandle(int h){
        Clip c = clips.remove(h);
        if(c!=null){ try{ c.stop(); c.close(); }catch(Throwable t){} }
        return 0;
    }

    // volume 0..127 <-> MASTER_GAIN (dB)
    int getVol(Clip c){
        try { if(c.isControlSupported(FloatControl.Type.MASTER_GAIN)){
            FloatControl g=(FloatControl)c.getControl(FloatControl.Type.MASTER_GAIN);
            float t=(g.getValue()-g.getMinimum())/(g.getMaximum()-g.getMinimum());
            return Math.round(t*127);
        } } catch(Throwable e){}
        return 127;
    }
    void setVol(Clip c,int v){
        try { if(c.isControlSupported(FloatControl.Type.MASTER_GAIN)){
            FloatControl g=(FloatControl)c.getControl(FloatControl.Type.MASTER_GAIN);
            float t=Math.max(0,Math.min(127,v))/127f;
            g.setValue(g.getMinimum()+t*(g.getMaximum()-g.getMinimum()));
        } } catch(Throwable e){}
    }
}
