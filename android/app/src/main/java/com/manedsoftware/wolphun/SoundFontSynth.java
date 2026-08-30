package com.manedsoftware.wolphun;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import wolphun.vm.MidiBridge;

/**
 * Backend de MIDI que toca via um SoundFont .sf2 (timbres customizados), igual à
 * ideia do desktop (Gervill) e do JL-Mod. Usa código NATIVO (TinySoundFont +
 * TinyMidiLoader, ver src/main/cpp) para renderizar PCM 16-bit stereo @ 44100,
 * alimentando um AudioTrack por uma thread. Se o .sf2 não carregar, tryCreate()
 * retorna null e o app cai no backend padrão (AndroidAudio/Sonivox).
 */
public final class SoundFontSynth implements MidiBridge.Backend {
    static { System.loadLibrary("wolphunsf"); }

    private static native long nativeCreate(String sf2Path);
    private static native void nativeSetMidi(long p, byte[] smf, boolean loop);
    private static native int  nativeRender(long p, short[] out, int frames);
    private static native void nativeStop(long p);
    private static native void nativeDestroy(long p);

    private static final int RATE = 44100;
    private static final int FR = 1024;                 // frames por bloco (~23ms)

    private long player;
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running, playing;
    private final Object lock = new Object();

    /** cria o synth; retorna null se o .sf2 não carregar (ou sem NDK) — aí usa o backend padrão. */
    public static SoundFontSynth tryCreate(String sf2Path){
        if(sf2Path==null || sf2Path.isEmpty()) return null;
        try {
            long p = nativeCreate(sf2Path);
            if(p==0) return null;
            SoundFontSynth s = new SoundFontSynth(p);
            if(!s.start()){ s.release(); return null; }
            return s;
        } catch(Throwable t){ return null; }
    }

    private SoundFontSynth(long p){ this.player = p; }

    private boolean start(){
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0) min = RATE*4;
            int buf = min*4;                            // multiplo do frame (4B) -> tamanho valido
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            track.play();
            running = true;
            thread = new Thread(new Runnable(){ public void run(){ loop(); } }, "soundfont-mix");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch(Throwable t){ return false; }
    }

    private void loop(){
        short[] out = new short[FR*2];
        short[] silence = new short[FR*2];
        while(running){
            boolean any = false;
            synchronized(lock){
                if(playing && player!=0){
                    int st = nativeRender(player, out, FR);
                    if(st==0) playing=false;
                    any = true;
                }
            }
            try { track.write(any?out:silence, 0, FR*2); } catch(Throwable t){}
        }
    }

    @Override public void play(byte[] smf, boolean loop){
        synchronized(lock){ if(player!=0){ nativeSetMidi(player, smf, loop); playing=true; } }
    }
    @Override public void stop(){
        synchronized(lock){ if(player!=0) nativeStop(player); playing=false; }
    }

    /** libera o AudioTrack, a thread e o synth nativo. */
    public void release(){
        running=false;
        try { if(thread!=null) thread.join(300); } catch(Throwable t){}
        try { if(track!=null){ track.stop(); track.flush(); track.release(); } } catch(Throwable t){}
        track=null;
        synchronized(lock){ if(player!=0){ nativeDestroy(player); player=0; } }
    }
}
