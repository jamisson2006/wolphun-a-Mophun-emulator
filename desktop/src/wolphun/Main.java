package wolphun;

import wolphun.vm.*;

/**
 * Ponto de entrada do port JavaSE do Honey Cave 2.
 * Carrega o .mpn descriptografado, monta a VM PIP2 + SDK + tela, e roda o
 * bytecode REAL do jogo (approach de fidelidade total).
 *
 * Teclas: setas/WASD = mover, Espaco/Enter/Ctrl = fogo/OK, Q/E = softkeys.
 */
public final class Main {
    static long DEBUG=0;
    public static void main(String[] args) throws Exception {
        String mpn = args.length>0 ? args[0] : "assets/honeycave2.mpn";
        int scrW = 128, scrH = 160, scale = 4;   // resolucao nativa do menu (mapa 16x20 preenche a tela)
        boolean trace = false, headless = false;
        long limit = 0;
        String snap = null, flipSnap = null;
        boolean autoplay = false;
        double gamma = 1.0;   // brilho de display (1.0=exato SDK; <1.0 clareia preservando matiz)
        double fontScale = 1.0;   // escala do texto in-game (teste do redimensionamento de fonte)
        for(String a: args){
            if(a.startsWith("--snap=")){ snap=a.substring(7); continue; }
            if(a.startsWith("--flipsnap=")){ flipSnap=a.substring(11); continue; }
            if(a.equals("--autoplay")){ autoplay=true; continue; }
            if(a.startsWith("--gamma=")){ gamma=Double.parseDouble(a.substring(8)); continue; }
            if(a.startsWith("--fontscale=")){ fontScale=Double.parseDouble(a.substring(12)); continue; }
            if(a.startsWith("--w=")) scrW=Integer.parseInt(a.substring(4));
            else if(a.startsWith("--h=")) scrH=Integer.parseInt(a.substring(4));
            else if(a.startsWith("--scale=")) scale=Integer.parseInt(a.substring(8));
            else if(a.equals("--trace")) trace=true;
            else if(a.equals("--headless")) headless=true;
            else if(a.startsWith("--limit=")) limit=Long.parseLong(a.substring(8));
            else if(a.startsWith("--debug=")) DEBUG=Long.parseLong(a.substring(8));
        }

        System.out.println("Loading "+mpn+" ...");
        Vmgp exe = Vmgp.load(mpn);
        System.out.printf("VMGP: code=%d data=%d bss=%d res=%d pool=%d  imports:%n",
            exe.codeSize, exe.dataSize, exe.bssSize, exe.resSize, exe.poolCount);

        Screen scr = new Screen(scrW, scrH, scale, headless);
        scr.flipSnapPrefix = flipSnap;
        scr.autoAdvance = autoplay;
        scr.gamma = gamma;
        scr.setFontScale(fontScale);
        if(System.getenv("DRAWLOG")!=null) wolphun.vm.Sdk.DRAWLOG=true;   // loga draws (sprites: fmt/w/h/palOff)
        Pip2 vm = new Pip2(exe, 4*1024*1024, 256*1024);   // heap 4MB, stack 256KB
        Sdk sdk = new Sdk(vm, scr);
        sdk.trace = trace;
        // saves ("resume"/"highscore") ficam numa pasta ao lado do .mpn
        java.io.File mpnDir = new java.io.File(mpn).getAbsoluteFile().getParentFile();
        sdk.saveDir = new java.io.File(mpnDir!=null? mpnDir : new java.io.File("."), "saves");
        System.out.println("Saves at: "+sdk.saveDir.getAbsolutePath());
        vm.sdk = sdk;
        vm.traceSteps = DEBUG;
        vm.runConstructors();   // roda os construtores globais C++ ("~C") antes do main (ex.: HC1)
        if(System.getenv("PCWATCH")!=null) vm.pcWatch=(int)Long.decode(System.getenv("PCWATCH").trim()).longValue();

        System.out.println("Starting VM (PC=0x"+Integer.toHexString(vm.getReg(Pip2.PC))+", SP=0x"+Integer.toHexString(vm.getReg(Pip2.SP))+")");

        // loop: ~fatia de instrucoes por frame; a apresentacao ocorre em vFlipScreen
        long lastReport=System.currentTimeMillis();
        while(!vm.stopped){
            try {
                vm.run(200000);          // executa um lote de instrucoes
                if(limit>0 && vm.instr>=limit){ System.out.println("instruction limit reached"); break; }
            } catch(Throwable e){
                System.err.println("Exception in VM @instr "+vm.instr+" PC=0x"+Integer.toHexString(vm.getReg(Pip2.PC))+": "+e);
                e.printStackTrace();
                break;
            }
            scr.present();
            try { Thread.sleep(5); } catch(InterruptedException ie){}
            if(System.currentTimeMillis()-lastReport>2000){
                System.out.println("... instr="+vm.instr+" PC=0x"+Integer.toHexString(vm.getReg(Pip2.PC)));
                lastReport=System.currentTimeMillis();
            }
        }
        System.out.println("VM stopped. Total instructions: "+vm.instr);
        if(vm.pcWatch!=0) System.out.println("PCWATCH 0x"+Integer.toHexString(vm.pcWatch)+" hits="+vm.pcWatchHits);
        if(snap!=null) scr.savePng(snap);
    }
}
