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
    public volatile String error=null;

    public GameSession(File mpn, String gameName, PrefsConfig cfg, File saveDir){
        this.mpnFile=mpn; this.gameName=gameName; this.cfg=cfg; this.saveDir=saveDir;
    }

    public void init() throws Exception {
        exe = Vmgp.load(mpnFile.getAbsolutePath());
        int model = PrefsConfig.detectModel(mpnFile.getName());
        int w, h;
        if(model>=0){ int[] r = PrefsConfig.resForModel(model); w=r[0]; h=r[1]; }
        else { model = cfg.deviceModel; w=cfg.width; h=cfg.height; }

        scr = new Screen(w, h, cfg.scale);
        scr.targetFps = cfg.targetFps;
        scr.gamma = cfg.gamma;

        vm  = new Pip2(exe, 4*1024*1024, 256*1024);
        sdk = new Sdk(vm, scr);
        sdk.soundEnabled = cfg.sound;
        sdk.deviceModel  = model;
        sdk.deviceVendor = PrefsConfig.vendorForModel(model);
        sdk.saveDir = saveDir;
        vm.sdk = sdk;
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
            stopSound();
        }
    }

    public void pause(){ paused=true; stopSound(); }
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
        running=false;
        if(vm!=null) vm.stopped=true;
        if(thread!=null) thread.interrupt();
        try { if(sdk!=null) sdk.flushSaves(); } catch(Throwable t){}
        stopSound();
    }
    void stopSound(){ try { if(sdk!=null) sdk.stopSound(); } catch(Throwable t){} }
}
