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
        if(System.getenv("FPWATCH")!=null) vm.fpWatch=true;
        if(System.getenv("VTICK")!=null){ sdk.virtualTick=0; sdk.virtualTickStep=Long.parseLong(System.getenv("VTICK")); }
        if(System.getenv("PCWATCH")!=null) vm.pcWatch=(int)Long.decode(System.getenv("PCWATCH").trim()).longValue();
        if(System.getenv("GAMMA")!=null) scr.gamma = Double.parseDouble(System.getenv("GAMMA"));
        if(System.getenv("TRACE")!=null) sdk.trace = true;
        sdk.loadState(new java.io.File(save));
        { String ms=System.getenv("MEMSET16"); if(ms!=null){ String[] p=ms.split(":"); vm.write16((int)Long.decode(p[0].trim()).longValue(), Integer.parseInt(p[1].trim())); } }
        System.out.println("save loaded: PC=0x"+Integer.toHexString(vm.getReg(Pip2.PC))
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
        // BTNSEQ="10,2,10" = sequencia de presses (cada bit hex): segura 3 frames, solta 3 (debounce de edge).
        int[] seq = null; { String s=System.getenv("BTNSEQ"); if(s!=null){ String[] p=s.split(","); seq=new int[p.length]; for(int i=0;i<p.length;i++) seq[i]=Integer.parseInt(p[i].trim(),16); } }
        int seqPress = System.getenv("SEQPRESS")!=null ? Integer.parseInt(System.getenv("SEQPRESS")) : 3;
        int seqGap   = System.getenv("SEQGAP")!=null ? Integer.parseInt(System.getenv("SEQGAP")) : 3;
        int logLast = System.getenv("LOGLAST")!=null ? Integer.parseInt(System.getenv("LOGLAST")) : 0;
        int startFlips=scr.flips;
        long guard=0;
        for(int f=1; f<=frames && !vm.stopped; ){
            Sdk.DRAWLOG = (System.getenv("NOLOG")==null) && (logLast==0 || f>frames-logLast);
            Sdk.drawLogFrame=f;
            if(btn!=0) scr.setButtonBit(btn, f<=hold);   // segura o botao nos primeiros 'hold' frames
            if(seq!=null){                               // sequencia de presses com debounce (press/gap por item)
                int cycle=seqPress+seqGap; int idx=(f-1)/cycle; int phase=(f-1)%cycle;
                scr.clearButtons();
                if(idx<seq.length && phase<seqPress) scr.setButtonBit(seq[idx], true);
            }
            // HOLDBIT=hex segurado continuamente a partir do frame HOLDFROM (p/ testar mov. sustentado)
            { String hb=System.getenv("HOLDBIT"); if(hb!=null){ int bit=Integer.parseInt(hb.trim(),16);
                int from=System.getenv("HOLDFROM")!=null?Integer.parseInt(System.getenv("HOLDFROM")):1;
                scr.setButtonBit(bit, f>=from); } }
            // KEYPAD=ascii (decimal) segura uma tecla NUMERICA literal a partir de HOLDFROM (vTestKey/vScanKeys)
            { String kp=System.getenv("KEYPAD"); if(kp!=null){ int ka=Integer.parseInt(kp.trim());
                int from=System.getenv("HOLDFROM")!=null?Integer.parseInt(System.getenv("HOLDFROM")):1;
                scr.setKeypad(ka, f>=from); } }
            int before=scr.flips;
            while(scr.flips==before && !vm.stopped && guard<120_000_000L){
                vm.run(20000); guard+=20000;
            }
            if(snapPrefix!=null) scr.savePng(snapPrefix+"_f"+f+".png");
            f++;
            if(guard>=120_000_000L){ System.out.println("guard overflow (no flip)"); break; }
        }
        System.out.println("frames run; flips="+(scr.flips-startFlips)+" instr="+vm.instr);
        if(vm.pcWatch!=0) System.out.println("PCWATCH 0x"+Integer.toHexString(vm.pcWatch)+" hits="+vm.pcWatchHits);
        { String md=System.getenv("MEMDUMP"); if(md!=null){ String[] p=md.split(":"); int addr=(int)Long.decode(p[0].trim()).longValue(); int len=p.length>1?Integer.parseInt(p[1].trim()):64; int stride=p.length>2?Integer.parseInt(p[2].trim()):20;
            System.out.println("MEMDUMP @0x"+Integer.toHexString(addr)+" len="+len+" stride="+stride+":");
            for(int i=0;i<len;i+=stride){ StringBuilder sb=new StringBuilder(String.format("  +0x%x: ",i)); for(int j=0;j<stride && i+j<len;j+=4) sb.append(String.format("%08x ", vm.read32(addr+i+j))); System.out.println(sb); } } }
        if(System.getenv("DUMPSPRITES")!=null){                 // posicoes dos sprites (achar o Jack e medir movimento)
            try {
                java.lang.reflect.Field fc=Sdk.class.getDeclaredField("spriteCount"); fc.setAccessible(true);
                java.lang.reflect.Field fx=Sdk.class.getDeclaredField("slotX"); fx.setAccessible(true);
                java.lang.reflect.Field fy=Sdk.class.getDeclaredField("slotY"); fy.setAccessible(true);
                java.lang.reflect.Field fs=Sdk.class.getDeclaredField("slotSprite"); fs.setAccessible(true);
                int n=fc.getInt(sdk); short[] xs=(short[])fx.get(sdk); short[] ys=(short[])fy.get(sdk); int[] ss=(int[])fs.get(sdk);
                StringBuilder b=new StringBuilder("SPRITES: ");
                for(int i=0;i<n && ss!=null;i++) if(ss[i]!=0) b.append(i+":ptr="+ss[i]+"@("+xs[i]+","+ys[i]+") ");
                System.out.println(b.toString());
            } catch(Exception e){ System.out.println("dump failed: "+e); }
        }
        System.out.println("3D totals: drawCalls="+sdk.r3d.drawCalls+" tris="+sdk.r3d.trisDrawn+" billboards="+sdk.r3d.billboards+" pixels="+sdk.r3d.pixelsDrawn);
        System.out.println("frustum: visible="+sdk.r3d.frustumVis+" culled="+sdk.r3d.frustumCull);
    }
    // acessos simples via reflection-free (campos package-visiveis)
    static String sdk_fore(Sdk s){ try { java.lang.reflect.Field f=Sdk.class.getDeclaredField("foreColor"); f.setAccessible(true); return ""+f.getInt(s);}catch(Exception e){return "?";} }
    static String sdk_tm(Sdk s){ try { java.lang.reflect.Field f=Sdk.class.getDeclaredField("transferMode"); f.setAccessible(true); return ""+f.getInt(s);}catch(Exception e){return "?";} }
    static String sdk_sc(Sdk s){ try { java.lang.reflect.Field f=Sdk.class.getDeclaredField("spriteCount"); f.setAccessible(true); return ""+f.getInt(s);}catch(Exception e){return "?";} }
}
