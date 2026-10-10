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
        // modelo/resolucao — cadeia de deteccao automatica por jogo:
        //   1) tag de modelo no nome ([T610]/[7650]/...) -> mais forte (define terminal E resolucao);
        //   2) tag de resolucao [LxA] ou screenshot -> resolucao nativa + modelo canonico p/ CheckTerminal;
        //   3) nada detectado -> default global do usuario (Settings).
        int model = GameLibrary.detectModel(mpnFile);
        int w, h;
        if(model>=0){ int[] r = Config.resForModel(model); w=r[0]; h=r[1]; }
        else {
            int[] res = GameLibrary.detectRes(mpnFile);
            if(res!=null){
                w=res[0]; h=res[1];
                int canon = Config.modelForRes(w,h);      // modelo/vendor coerentes com a resolucao
                model = (canon>=0)? canon : cfg.deviceModel;
            } else {
                model = cfg.deviceModel; w=cfg.width; h=cfg.height;
            }
        }
        // auto-fit do zoom: a janela (w*scale x h*scale) nao pode passar da tela fisica,
        // senao ela abre fora dos limites e o jogo aparece "preto"/cortado. Clampa o scale
        // ao maior que couber (deixando margem p/ barra de titulo + menu + taskbar).
        int scale = clampScaleToScreen(w, h, cfg.scale);
        scr = new Screen(w, h, scale, false, true, cfg.renderScale);   // embutido; renderScale = supersampling HD
        scr.keyMap = cfg.screenKeyMap();
        scr.targetFps = cfg.targetFps;
        scr.gamma = cfg.gamma;
        scr.setFontScale(cfg.fontScale);
        vm  = new Pip2(exe, (int)Math.max(4L*1024*1024, exe.heapBytesRequested()), 256*1024);
        sdk = new Sdk(vm, scr);
        sdk.soundEnabled = cfg.sound;
        sdk.soundFont    = cfg.soundFont;
        sdk.reverb       = cfg.reverb;
        sdk.deviceModel  = model;
        sdk.deviceVendor = Config.vendorForModel(model);
        sdk.romDir  = mpnFile!=null? mpnFile.getAbsoluteFile().getParentFile() : null;   // .mpc companheiros
        sdk.saveDir = cfg.savesDir(gameName);
        sdk.r3d.texFilterForce = cfg.texFilter;                  // FILTERMODE: 0=jogo 1=bilinear 2=nearest
        vm.sdk = sdk;
        vm.runConstructors();   // construtores globais C++ ("~C") antes do main (ex.: HC1 inicializa entidades/sprites)
    }

    /** Maior zoom (>=1) para o qual a janela w*scale x h*scale ainda cabe na tela fisica,
     *  limitado ao 'want' pedido. Evita janela maior que o monitor (jogo "preto"/cortado)
     *  em resolucoes altas (ex.: 208x320) — o gargalo real do "nao passa de 4x". */
    static int clampScaleToScreen(int w, int h, int want){
        if(want < 1) want = 1;
        try {
            java.awt.Rectangle scr = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getMaximumWindowBounds();   // ja desconta a taskbar
            int availW = scr.width  - 24;    // margem p/ bordas da janela
            int availH = scr.height - 80;    // margem p/ barra de titulo + barra de menu
            int fit = Math.min(availW / Math.max(1,w), availH / Math.max(1,h));
            if(fit < 1) fit = 1;
            return Math.min(want, fit);
        } catch(Throwable t){ return want; }   // headless/sem display: mantem o pedido
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
            System.err.println("[session] exception @instr "+(vm!=null?vm.instr:-1)+": "+t);
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
