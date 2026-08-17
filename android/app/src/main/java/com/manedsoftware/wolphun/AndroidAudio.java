package com.manedsoftware.wolphun;

import android.content.Context;
import android.media.MediaPlayer;
import wolphun.vm.MidiBridge;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Backend de MIDI para o Sdk (via MidiBridge). No Android o SMF é tocado pelo
 * MediaPlayer (sintetizador Sonivox embutido). Grava os bytes num arquivo temp
 * no cacheDir e toca. Efeitos curtos podem ter latência (limitação do MediaPlayer).
 */
public final class AndroidAudio implements MidiBridge.Backend {
    private final File cacheDir;
    private MediaPlayer mp;
    private int counter = 0;

    public AndroidAudio(Context ctx){ this.cacheDir = ctx.getCacheDir(); }

    @Override public synchronized void play(byte[] smf, boolean loop){
        stop();
        try {
            File f = new File(cacheDir, "midi_" + (counter++ % 4) + ".mid");
            FileOutputStream out = new FileOutputStream(f);
            out.write(smf); out.close();
            mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setLooping(loop);
            mp.prepare();
            mp.start();
        } catch(Throwable t){
            // sem device/erro: não trava o jogo
            try { if(mp!=null){ mp.release(); mp=null; } } catch(Throwable e){}
        }
    }

    @Override public synchronized void stop(){
        if(mp!=null){
            try { if(mp.isPlaying()) mp.stop(); } catch(Throwable t){}
            try { mp.release(); } catch(Throwable t){}
            mp = null;
        }
    }
}
