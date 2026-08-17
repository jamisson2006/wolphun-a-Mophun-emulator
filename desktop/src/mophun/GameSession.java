package mophun;

import wolphun.Screen;
import wolphun.vm.*;
import java.awt.Canvas;
import java.io.File;

/**
 * Uma partida em execucao: carrega o .mpn, monta VM (PIP2) + SDK + Screen embutido,
 * e roda o bytecode num thread proprio. Permite pausar/retomar/parar. O emulador
 * insere getCanvas() na janela do jogo.
 */
public final class GameSession {
    public final String gameName;
    public final File mpnFile;
    final Config cfg;

    Vmgp exe; Pip2 vm; Sdk sdk; Screen scr;
    Thread thread;
    volatile boolean running=false, paused=false, parked=false;
    public volatile String error=null;

    public GameSession(File mpn, String gameName, Config cfg){
        this.mpnFile=mpn; this.gameName=gameName; this.cfg=cfg;
    }

    /** monta a VM e a tela (na thread da UI). Depois adicione getCanvas() a uma janela e chame start(). */
    public void init() throws Exception {
        exe = Vmgp.load(mpnFile.getAbsolutePath());
        // modelo/resolucao: detecta pelo nome ([T300]/[T610]/...); multiscreen/desconhecido usa o default
        int model = GameLibrary.detectModel(mpnFile);
        int w, h;
        if(model>=0){ int[] r = Config.resForModel(model); w=r[0]; h=r[1]; }
        else { model = cfg.deviceModel; w=cfg.width; h=cfg.height; }
        scr = new Screen(w, h, cfg.scale, false, true);   // embutido
        scr.keyMap = cfg.screenKeyMap();
        scr.targetFps = cfg.targetFps;
        scr.gamma = cfg.gamma;
        vm  = new Pip2(exe, 4*1024*1024, 256*1024);
        sdk = new Sdk(vm, scr);
        sdk.soundEnabled = cfg.sound;
        sdk.deviceModel  = model;
        sdk.deviceVendor = Config.vendorForModel(model);
        sdk.saveDir = cfg.savesDir(gameName);
        vm.sdk = sdk;
    }

    public Canvas getCanvas(){ return scr.getCanvas(); }
    public Screen getScreen(){ return scr; }

    public void start(){
        if(running) return;
        running=true; paused=false;
        thread = new Thread(new Runnable(){ public void run(){ loop(); } }, "mophun-vm:"+gameName);
        thread.setDaemon(true);
        thread.start();
    }

    void loop(){
        try {
            while(running && !vm.stopped){
                if(paused){ parked=true; Thread.sleep(20); continue; }
                parked=false;
                int before = scr.flips;
                vm.run(50000);            // lote de instrucoes (frame pacing vive no vFlipScreen/present)
                // se o lote nao produziu frame (ex: menu girando em vGetButtonData), cede CPU
                if(scr.flips==before) Thread.sleep(1);
            }
            parked=true;
        } catch(InterruptedException ie){
            // parada solicitada
        } catch(Throwable t){
            error = t.toString();
            System.err.println("[sessao] excecao @instr "+(vm!=null?vm.instr:-1)+": "+t);
            t.printStackTrace();
        } finally {
            running=false;
            stopSound();
        }
    }

    public void pause(){ paused=true; stopSound(); }
    public void resume(){ paused=false; if(scr!=null && scr.getCanvas()!=null) scr.getCanvas().requestFocus(); }
    public boolean isPaused(){ return paused; }
    public boolean isRunning(){ return running; }
    public boolean isStopped(){ return vm!=null && vm.stopped; }
    public long instrCount(){ return vm!=null? vm.instr : 0; }
    public Sdk sdk(){ return sdk; }

    // espera a thread da VM realmente estacionar (nao mid-vm.run) antes de tocar no estado
    void waitParked(){
        for(int i=0;i<400 && !parked && running;i++){ try { Thread.sleep(5); } catch(InterruptedException e){} }
    }
    // save state (snapshot da VM) — pausa a VM E espera parar p/ ler/escrever memoria com seguranca
    public void saveState(File f) throws Exception {
        boolean wasPaused = paused; paused=true; waitParked();
        try { sdk.saveState(f); } finally { if(!wasPaused){ paused=false; if(scr!=null&&scr.getCanvas()!=null) scr.getCanvas().requestFocus(); } }
    }
    public void loadState(File f) throws Exception {
        boolean wasPaused = paused; paused=true; waitParked();
        try { sdk.loadState(f); } finally { if(!wasPaused){ paused=false; if(scr!=null&&scr.getCanvas()!=null) scr.getCanvas().requestFocus(); } }
    }

    public void stop(){
        running=false;
        if(vm!=null) vm.stopped=true;
        if(thread!=null){ thread.interrupt(); }
        try { if(sdk!=null) sdk.flushSaves(); } catch(Throwable t){}   // garante save nativo no disco ao sair/fechar
        stopSound();
    }
    void stopSound(){ try { if(sdk!=null) sdk.stopSound(); } catch(Throwable t){} }
}
