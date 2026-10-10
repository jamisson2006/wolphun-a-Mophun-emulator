package com.manedsoftware.wolphun;

import wolphun.Screen;
import wolphun.vm.Pip2;
import wolphun.vm.Sdk;
import wolphun.vm.Vmgp;
import java.io.File;

/**
 * Uma partida em execução (Android): carrega o .mpn, monta VM (PIP2) + SDK +
 * Screen, e roda o bytecode numa thread própria. A Activity insere o FrameSink
 * (SurfaceView) via getScreen().sink e injeta o input pelo teclado virtual.
 */
public final class GameSession {
    public final String gameName;
    public final File mpnFile;
    final PrefsConfig cfg;
    final File saveDir;

    Vmgp exe; Pip2 vm; Sdk sdk; Screen scr;
    Thread thread;
    volatile boolean running=false, paused=false, parked=false;
    volatile boolean userStopped=false;   // true = paramos nos (stop()); false = o jogo saiu sozinho
    public int forceModel = -1;           // resolucao forcada por jogo (indice de modelo); -1 = auto (detecta)
    public volatile String error=null;

    /** avisado quando o jogo SAI SOZINHO (softkey 'sair'/Quit ou fim de task) — nao no stop() manual. */
    public interface EndListener { void onGameEnded(); }
    volatile EndListener endListener;
    public void setEndListener(EndListener l){ this.endListener = l; }

    /** Contexto do app, so para servicos do aparelho (vibracao). Pode ser null em testes. */
    private android.content.Context ctx;
    public GameSession setContext(android.content.Context c){ ctx=c; return this; }

    public GameSession(File mpn, String gameName, PrefsConfig cfg, File saveDir){
        this.mpnFile=mpn; this.gameName=gameName; this.cfg=cfg; this.saveDir=saveDir;
    }

    public void init() throws Exception {
        exe = Vmgp.load(mpnFile.getAbsolutePath());
        // deteccao automatica de perfil por jogo: 1) tag de modelo ([T610]/[7650]...);
        // 2) tag de resolucao [LxA] (ex.: [176x208]) -> modelo canonico; 3) default do usuario.
        int model; int w, h;
        if(forceModel>=0){                                  // override de resolucao POR JOGO (menu da biblioteca)
            model = forceModel; int[] r = PrefsConfig.resForModel(model); w=r[0]; h=r[1];
        } else {
            model = PrefsConfig.detectModel(mpnFile.getName());
            if(model>=0){ int[] r = PrefsConfig.resForModel(model); w=r[0]; h=r[1]; }
            else {
                int[] res = PrefsConfig.detectRes(mpnFile.getName());
                if(res!=null){ w=res[0]; h=res[1]; int canon=PrefsConfig.modelForRes(w,h); model=(canon>=0)?canon:cfg.deviceModel; }
                else { model = cfg.deviceModel; w=cfg.width; h=cfg.height; }
            }
        }

        scr = new Screen(w, h, cfg.scale, cfg.renderScale);   // renderScale = supersampling HD
        scr.targetFps = cfg.targetFps;
        scr.gamma = cfg.gamma;
        scr.setFontScale(cfg.fontScale);

        // mesma formula do desktop: nenhuma ROM do acervo passa de 4 MB (a maior pede 60 KB),
        // mas assim uma ROM que peca mais nao roda com heap curto.
        vm  = new Pip2(exe, (int)Math.max(4L*1024*1024, exe.heapBytesRequested()), 256*1024);
        sdk = new Sdk(vm, scr);
        sdk.soundEnabled = cfg.sound;
        sdk.reverb       = cfg.reverb;
        sdk.deviceModel  = model;
        sdk.deviceVendor = PrefsConfig.vendorForModel(model);
        sdk.romDir  = mpnFile!=null? mpnFile.getAbsoluteFile().getParentFile() : null;   // .mpc companheiros
        sdk.saveDir = saveDir;
        sdk.r3d.texFilterForce = cfg.texFilter;               // FILTERMODE: 0=jogo 1=bilinear 2=nearest
        // Vibracao: o jogo pede por vSysCtl(SYSCTL_SETVIBRATE). So ligamos se o usuario deixou
        // ativo nas opcoes E o aparelho tem motor (tablet/emulador normalmente nao tem).
        if(ctx!=null && cfg.vibrate){
            Vibra v = new Vibra(ctx, true);
            if(v.disponivel()) sdk.vibrador = v;
        }
        vm.sdk = sdk;
        vm.runConstructors();   // construtores globais C++ ("~C") antes do main (ex.: HC1 inicializa entidades/sprites)
    }

    // teclas de aparelho que ESTA ROM consulta (203/204/...). Analise estatica feita uma vez, no
    // carregamento: custa 0-25 ms e evita manter lista de nomes de jogo na mao.
    private java.util.Set<Integer> phoneKeys;
    public java.util.Set<Integer> phoneKeysUsed(){
        if(phoneKeys==null){
            java.util.Set<Integer> all = vm!=null ? wolphun.vm.KeyScan.keysUsedBy(vm)
                                                  : new java.util.TreeSet<Integer>();
            java.util.TreeSet<Integer> out = new java.util.TreeSet<Integer>();
            for(int c : new int[]{203,204}) if(all.contains(c)) out.add(c);
            phoneKeys = out;
        }
        return phoneKeys;
    }

    public Screen getScreen(){ return scr; }
    public Sdk sdk(){ return sdk; }

    public void start(){
        if(running) return;
        running=true; paused=false;
        thread = new Thread(new Runnable(){ public void run(){ loop(); } }, "wolphun-vm:"+gameName);
        thread.setDaemon(true);
        thread.start();
    }

    void loop(){
        try {
            while(running && !vm.stopped){
                if(paused){ parked=true; Thread.sleep(20); continue; }
                parked=false;
                int before = scr.flips;
                vm.run(50000);
                if(scr.flips==before) Thread.sleep(1);
            }
            parked=true;
        } catch(InterruptedException ie){
            // parada solicitada
        } catch(Throwable t){
            error = t.toString();
            t.printStackTrace();
        } finally {
            running=false;
            disposeAudio();          // fim do jogo -> teardown completo do audio
            // o jogo terminou por conta propria (nao foi stop() manual) -> avisa a Activity p/ oferecer Reiniciar/Biblioteca
            if(!userStopped){ EndListener l = endListener; if(l!=null) try { l.onGameEnded(); } catch(Throwable t){} }
        }
    }

    // No Android o sistema pode MATAR o processo depois do onPause, sem passar pelo stop():
    // por isso o save nativo e descarregado aqui, e nao so ao fechar o jogo.
    public void pause(){
        paused=true; stopSound();
        try { if(sdk!=null) sdk.flushSaves(); } catch(Throwable t){}
    }
    public void resume(){ paused=false; }
    public boolean isPaused(){ return paused; }
    public boolean isStopped(){ return vm!=null && vm.stopped; }

    void waitParked(){
        for(int i=0;i<400 && !parked && running;i++){ try { Thread.sleep(5); } catch(InterruptedException e){} }
    }
    public void saveState(File f) throws Exception {
        boolean wasPaused = paused; paused=true; waitParked();
        try { sdk.saveState(f); } finally { if(!wasPaused) paused=false; }
    }
    public void loadState(File f) throws Exception {
        boolean wasPaused = paused; paused=true; waitParked();
        try { sdk.loadState(f); } finally { if(!wasPaused) paused=false; }
    }

    public void stop(){
        userStopped=true;
        running=false;
        if(vm!=null) vm.stopped=true;
        if(thread!=null) thread.interrupt();
        try { if(sdk!=null) sdk.flushSaves(); } catch(Throwable t){}
        disposeAudio();
    }
    // pausa: silencia o som mas MANTEM o motor vivo (senao fica mudo p/ sempre depois de pausar)
    void stopSound(){ try { if(sdk!=null) sdk.stopSound(); } catch(Throwable t){} }
    // teardown completo do audio (fim/destruir)
    void disposeAudio(){ try { if(sdk!=null) sdk.disposeAudio(); } catch(Throwable t){} }
}
