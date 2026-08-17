package wolphun.vm;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.util.HashMap;
import java.util.Map;

/**
 * Som PCM do mophun (módulo VSound) — versão Android (AudioTrack em modo estático).
 * MESMA API pública da versão desktop, então o Sdk usa sem mudança.
 *
 * NativeSoundHeader (36B, LE): magic[4]@0, headerSize@4, format@8 (1=PCM,2=ADPCM),
 *   loopStart@12, loopEnd@16, frequency@20, bitsPerSample@24, channelCount@25,
 *   priority@26, pad@27, magicBody[4]@28, bodySize@32 -> corpo PCM em @36.
 * Controles: Play=1 Stop=2 Pause=3 Resume=4 Volume=5 Freq=6 StopLooping=9
 *   MasterVolume=12 ChannelCount=14.
 */
public final class VSound {
    final Pip2 vm;
    final Map<Integer,AudioTrack> tracks = new HashMap<Integer,AudioTrack>();
    int nextHandle = 1;
    public boolean enabled = true;

    public VSound(Pip2 vm){ this.vm = vm; }

    public int init(){ return 0; }

    // vSoundGetHandle(soundData): monta um AudioTrack a partir do header + corpo PCM
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
            if(format==2){ tracks.put(h, null); return h; }   // ADPCM: handle silencioso (decodificação futura)
            if(channels<1||channels>2) channels=1;
            if(bits!=8 && bits!=16) bits=8;
            if(freq<=0) freq=8000;

            byte[] pcm = new byte[bodySize];
            for(int i=0;i<bodySize;i++) pcm[i]=(byte)vm.read8(bodyOff+i);

            int chCfg = (channels==2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            // mophun: 8-bit unsigned, 16-bit signed LE -> bate com ENCODING_PCM_8BIT / _16BIT do Android
            int enc = (bits==16) ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT;
            int frameBytes = (bits/8) * channels;
            int len = pcm.length - (pcm.length % frameBytes);
            if(len <= 0) return -1;

            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, freq, chCfg, enc, len, AudioTrack.MODE_STATIC);
            t.write(pcm, 0, len);
            tracks.put(h, t);
            return h;
        } catch(Throwable t){ return -1; }
    }

    public int ctrl(int handle, int control, int param){
        AudioTrack t = tracks.get(handle);
        switch(control){
            case 1:  // Play (do início)
                if(t!=null && enabled){ try { t.stop(); t.reloadStaticData(); t.play(); } catch(Throwable e){} }
                return 0;
            case 2:  case 3:  // Stop / Pause
                if(t!=null){ try { t.pause(); t.flush(); } catch(Throwable e){} }
                return 0;
            case 4:  // Resume
                if(t!=null && enabled){ try { t.play(); } catch(Throwable e){} }
                return 0;
            case 5:  // Volume 0..127
                if(t!=null){ if(param==-1) return 127; try { float v=Math.max(0,Math.min(127,param))/127f; t.setVolume(v); } catch(Throwable e){} }
                return 0;
            case 9:  // StopLooping
                if(t!=null){ try { t.setLoopPoints(0,0,0); } catch(Throwable e){} }
                return 0;
            case 12: case 14:
                return 0;
            default:
                return 0;
        }
    }

    public int dispose(){
        for(AudioTrack t: tracks.values()) if(t!=null){ try { t.stop(); t.release(); } catch(Throwable e){} }
        tracks.clear();
        return 0;
    }

    public int disposeHandle(int h){
        AudioTrack t = tracks.remove(h);
        if(t!=null){ try { t.stop(); t.release(); } catch(Throwable e){} }
        return 0;
    }
}
