package wolphun;

import wolphun.vm.*;

/**
 * Harness TEMPORARIO de debug: carrega uma ROM + save state de corrida, roda N frames
 * com o log de draws (Sdk.DRAWLOG) ligado e salva um PNG por frame.
 * Uso: java wolphun.DbgRace <rom.mpn> <savestate.bin> <frames> <snapPrefix>
 */
public final class DbgRace {
    public static void main(String[] a) throws Exception {
        String rom=a[0], save=a[1];
        int frames = a.length>2 ? Integer.parseInt(a[2]) : 4;
        String snapPrefix = a.length>3 ? a[3] : null;

        Vmgp exe = Vmgp.load(rom);
        int W = System.getenv("WIDTH")!=null? Integer.parseInt(System.getenv("WIDTH")) : 128;
        int H = System.getenv("HEIGHT")!=null? Integer.parseInt(System.getenv("HEIGHT")) : 160;
        Screen scr = new Screen(W, H, 2, true);        // headless; resolucao via env (bate com a do savestate)
        Pip2 vm = new Pip2(exe, 4*1024*1024, 256*1024);
        Sdk sdk = new Sdk(vm, scr);
        vm.sdk = sdk;
        if(System.getenv("GAMMA")!=null) scr.gamma = Double.parseDouble(System.getenv("GAMMA"));
        if(System.getenv("TRACE")!=null) sdk.trace = true;
        sdk.loadState(new java.io.File(save));
        System.out.println("save carregado: PC=0x"+Integer.toHexString(vm.getReg(Pip2.PC))
            +" fore="+sdk_fore(sdk)+" tm="+sdk_tm(sdk)+" spriteCount="+sdk_sc(sdk));

        // snapshot do frame 0 = exatamente o que estava salvo (framebuffer restaurado)
        if(snapPrefix!=null) scr.savePng(snapPrefix+"_f0.png");

        // TESTE: limpa a tela p/ MAGENTA -> o que o jogo NAO repintar num frame fica magenta (=buraco/smearing)
        boolean magenta = System.getenv("MAGENTA")!=null;
        if(magenta) java.util.Arrays.fill(scr.fb, 0xffff00ff);

        // opcional: injeta um botao (BTN=hex) por HOLD frames p/ avancar (ex.: FIRE=10 p/ largar a corrida);
        // DRAWLOG so liga nos ultimos LOGLAST frames (evita flood; foca no frame 3D de interesse).
        int btn = System.getenv("BTN")!=null ? Integer.parseInt(System.getenv("BTN"),16) : 0;
        int hold = System.getenv("HOLD")!=null ? Integer.parseInt(System.getenv("HOLD")) : 6;
        int logLast = System.getenv("LOGLAST")!=null ? Integer.parseInt(System.getenv("LOGLAST")) : 0;
        int startFlips=scr.flips;
        long guard=0;
        for(int f=1; f<=frames && !vm.stopped; ){
            Sdk.DRAWLOG = (System.getenv("NOLOG")==null) && (logLast==0 || f>frames-logLast);
            Sdk.drawLogFrame=f;
            if(btn!=0) scr.setButtonBit(btn, f<=hold);   // segura o botao nos primeiros 'hold' frames
            int before=scr.flips;
            while(scr.flips==before && !vm.stopped && guard<120_000_000L){
                vm.run(20000); guard+=20000;
            }
            if(snapPrefix!=null) scr.savePng(snapPrefix+"_f"+f+".png");
            f++;
            if(guard>=120_000_000L){ System.out.println("guard estourado (sem flip)"); break; }
        }
        System.out.println("frames rodados; flips="+(scr.flips-startFlips)+" instr="+vm.instr);
        System.out.println("3D totais: drawCalls="+sdk.r3d.drawCalls+" tris="+sdk.r3d.trisDrawn+" billboards="+sdk.r3d.billboards+" pixels="+sdk.r3d.pixelsDrawn);
        System.out.println("frustum: visivel="+sdk.r3d.frustumVis+" culled="+sdk.r3d.frustumCull);
    }
    // acessos simples via reflection-free (campos package-visiveis)
    static String sdk_fore(Sdk s){ try { java.lang.reflect.Field f=Sdk.class.getDeclaredField("foreColor"); f.setAccessible(true); return ""+f.getInt(s);}catch(Exception e){return "?";} }
    static String sdk_tm(Sdk s){ try { java.lang.reflect.Field f=Sdk.class.getDeclaredField("transferMode"); f.setAccessible(true); return ""+f.getInt(s);}catch(Exception e){return "?";} }
    static String sdk_sc(Sdk s){ try { java.lang.reflect.Field f=Sdk.class.getDeclaredField("spriteCount"); f.setAccessible(true); return ""+f.getInt(s);}catch(Exception e){return "?";} }
}
