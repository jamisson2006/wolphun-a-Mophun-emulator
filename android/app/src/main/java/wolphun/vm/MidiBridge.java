package wolphun.vm;

/**
 * Ponte de MIDI independente de plataforma. O Sdk chama MidiBridge.play/stop;
 * o app injeta um Backend concreto (no Android: MediaPlayer; no desktop: Sequencer).
 * Assim o Sdk fica sem dependência de javax.sound.midi (que não existe no Android).
 */
public final class MidiBridge {
    private MidiBridge(){}

    public interface Backend {
        void play(byte[] smf, boolean loop);
        void stop();
    }

    private static volatile Backend backend;

    public static void setBackend(Backend b){ backend = b; }

    public static void play(byte[] smf, boolean loop){
        Backend b = backend;
        if(b != null) try { b.play(smf, loop); } catch(Throwable t){}
    }

    public static void stop(){
        Backend b = backend;
        if(b != null) try { b.stop(); } catch(Throwable t){}
    }
}
