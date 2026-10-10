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
        boolean trace = false, headless = false, mute0 = false;
        long limit = 0;
        int devModel = -1, devVendor = -1;   // -1 = mantem o default do Sdk
        int renderScale = 1;                 // supersampling interno (a GUI usa cfg.renderScale)
        String snap = null, flipSnap = null, loadState = null, saveState = null;
        boolean autoplay = false;
        double gamma = 1.0;   // brilho de display (1.0=exato SDK; <1.0 clareia preservando matiz)
        double fontScale = 1.0;   // escala do texto in-game (teste do redimensionamento de fonte)
        for(String a: args){
            if(a.startsWith("--loadstate=")){ loadState=a.substring(12); continue; }
            if(a.startsWith("--savestate=")){ saveState=a.substring(12); continue; }
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
            else if(a.equals("--som")) mute0=true;   // headless nao toca som; --som reabilita
            else if(a.startsWith("--limit=")) limit=Long.parseLong(a.substring(8));
            else if(a.startsWith("--debug=")) DEBUG=Long.parseLong(a.substring(8));
            else if(a.startsWith("--model=")) devModel=Integer.parseInt(a.substring(8));
            else if(a.startsWith("--vendor=")) devVendor=Integer.parseInt(a.substring(9));
            else if(a.startsWith("--rs=")) renderScale=Integer.parseInt(a.substring(5));
        }

        System.out.println("Loading "+mpn+" ...");
        // ROMs com DRM mophun so rodam depois de descriptografadas. A GUI faz isso e guarda em
        // ~/.mophunemu/decrypted/; o headless nao fazia -> a VM morria na instrucao 1 com opcode
        // invalido. Usa o MESMO cache, entao rodar aqui e pela GUI da o mesmo arquivo.
        try {
            java.io.File dec = mophun.MophunCrypt.ensureDecrypted(new java.io.File(mpn));
            if(dec!=null && !dec.getAbsolutePath().equals(new java.io.File(mpn).getAbsolutePath())){
                System.out.println("  (descriptografado: "+dec.getName()+")");
                mpn = dec.getAbsolutePath();
            }
        } catch(Throwable t){ System.out.println("  (falha ao descriptografar: "+t+")"); }
        Vmgp exe = Vmgp.load(mpn);
        System.out.printf("VMGP: code=%d data=%d bss=%d res=%d pool=%d  imports:%n",
            exe.codeSize, exe.dataSize, exe.bssSize, exe.resSize, exe.poolCount);

        Screen scr = new Screen(scrW, scrH, scale, headless, false, renderScale);
        scr.flipSnapPrefix = flipSnap;
        scr.autoAdvance = autoplay;
        scr.gamma = gamma;
        scr.setFontScale(fontScale);
        if(System.getenv("DRAWLOG")!=null) wolphun.vm.Sdk.DRAWLOG=true;   // loga draws (sprites: fmt/w/h/palOff)
        // heap: pelo menos 4MB, mas respeita o pedido da ROM (WWP pede ~6MB). O piso de 4MB mantem
        // o layout identico p/ jogos que pedem menos -> save states antigos continuam validos.
        int heapBytes = (int)Math.max(4L*1024*1024, exe.heapBytesRequested());
        Pip2 vm = new Pip2(exe, heapBytes, 256*1024);
        Sdk sdk = new Sdk(vm, scr);
        sdk.trace = trace;
        // headless = teste em lote: sem som por padrao (varrer o acervo enchia o alto-falante do
        // usuario de musica de jogo). --som liga de volta se for preciso testar audio sem GUI.
        if(headless && !mute0) sdk.soundEnabled = false;
        // identidade do aparelho: o headless precisa poder casar com o modelo escolhido na GUI
        // (vGetCaps System) -- senao o jogo carregado de um save state ve outro terminal.
        if(devModel>=0)  sdk.deviceModel  = devModel;
        if(devVendor>=0) sdk.deviceVendor = devVendor;
        // saves ("resume"/"highscore") ficam numa pasta ao lado do .mpn
        java.io.File mpnDir = new java.io.File(mpn).getAbsoluteFile().getParentFile();
        sdk.romDir  = mpnDir;                       // arquivos de dados companheiros (.mpc) ao lado da ROM
        sdk.saveDir = new java.io.File(mpnDir!=null? mpnDir : new java.io.File("."), "saves");
        System.out.println("Saves at: "+sdk.saveDir.getAbsolutePath());
        vm.sdk = sdk;
        vm.traceSteps = DEBUG;
        vm.runConstructors();
        if(loadState!=null){                     // --loadstate=arq: retoma um save state e renderiza dali
            try { sdk.loadState(new java.io.File(loadState)); System.out.println("estado carregado: "+loadState); }
            catch(Throwable t){ System.out.println("FALHA ao carregar estado: "+t); }
            if(System.getenv("CODECHK")!=null){
                int base=0x10000, n=exe.codeSize, bad=-1, ndiff=0;
                for(int i=0;i<n;i++){ int m=vm.read8(base+i)&0xff, r=exe.all[40+i]&0xff;
                    if(m!=r){ ndiff++; if(bad<0) bad=i; } }
                if(bad<0) System.out.println("[CODECHK] codigo INTACTO ("+n+" bytes)");
                else System.out.printf("[CODECHK] CODIGO DIVERGE: %d bytes diferentes; 1a em VM 0x%x (rom=0x%02x mem=0x%02x)%n",
                    ndiff, base+bad, exe.all[40+bad]&0xff, vm.read8(base+bad)&0xff);
            }
            if(System.getenv("MATDUMP")!=null) sdk.r3d.dumpMatrices("apos-load");
        }   // roda os construtores globais C++ ("~C") antes do main (ex.: HC1)
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
        if(System.getenv("TASKLOG")!=null) System.out.println("[TAREFAS] SLEEP executado "+vm.sleepCount+"x, trocas="+vm.switchCount+", vivas="+vm.tasks.size()+", fila="+vm.taskQueue.size());
        if(saveState!=null){
            try { sdk.saveState(new java.io.File(saveState)); System.out.println("estado salvo: "+saveState); }
            catch(Throwable t){ System.out.println("falha ao salvar estado: "+t); }
        }
        if(System.getenv("STATELOG")!=null){ StringBuilder sb=new StringBuilder("[ESTADOS] ");
            for(java.util.Map.Entry<Integer,java.util.TreeSet<Integer>> e : sdk.r3d.stateSeen.entrySet())
                sb.append(e.getKey()).append("=").append(e.getValue()).append("  ");
            System.out.println(sb); }
        if(System.getenv("R3DSTAT")!=null && sdk.r3d.ndlN>0) System.out.println("[NDOTL] n="+sdk.r3d.ndlN+" min="+String.format("%.3f",sdk.r3d.ndlMin)+" max="+String.format("%.3f",sdk.r3d.ndlMax)+" medio="+String.format("%.3f",sdk.r3d.ndlSum/sdk.r3d.ndlN));
        if(System.getenv("SDKCOUNT")!=null){
            java.util.List<java.util.Map.Entry<String,Long>> es = new java.util.ArrayList<java.util.Map.Entry<String,Long>>(sdk.sdkCalls.entrySet());
            java.util.Collections.sort(es, new java.util.Comparator<java.util.Map.Entry<String,Long>>(){
                public int compare(java.util.Map.Entry<String,Long> a, java.util.Map.Entry<String,Long> b){ return b.getValue().compareTo(a.getValue()); }});
            StringBuilder sb=new StringBuilder("[SDKCOUNT] ");
            for(int i=0;i<es.size();i++) sb.append(es.get(i).getKey()).append("=").append(es.get(i).getValue()).append("  ");
            System.out.println(sb);
        }
        if(System.getenv("MVLOG")!=null) for(java.util.Map.Entry<String,Long> e : sdk.r3d.mvCount.entrySet()) System.out.println("[MVUSO] "+e.getValue()+" primitivas | "+e.getKey());
        if(System.getenv("EXTENT")!=null){
            if(sdk.r3d.txN>0) System.out.printf("[EXT-TEXTURIZADO] %d vert  X[%.0f..%.0f] Y[%.0f..%.0f] Z[%.0f..%.0f]%n", sdk.r3d.txN, sdk.r3d.txMinX, sdk.r3d.txMaxX, sdk.r3d.txMinY, sdk.r3d.txMaxY, sdk.r3d.txMinZ, sdk.r3d.txMaxZ);
            if(sdk.r3d.ntN>0) System.out.printf("[EXT-SEM-TEXTURA] %d vert  X[%.0f..%.0f] Y[%.0f..%.0f] Z[%.0f..%.0f]%n", sdk.r3d.ntN, sdk.r3d.ntMinX, sdk.r3d.ntMaxX, sdk.r3d.ntMinY, sdk.r3d.ntMaxY, sdk.r3d.ntMinZ, sdk.r3d.ntMaxZ); }
        if(System.getenv("EXTENT")!=null && sdk.r3d.exN>0) System.out.printf("[EXTENSAO] %d vertices  X[%.1f .. %.1f]  Y[%.1f .. %.1f]  Z[%.1f .. %.1f]%n", sdk.r3d.exN, sdk.r3d.exMinX, sdk.r3d.exMaxX, sdk.r3d.exMinY, sdk.r3d.exMaxY, sdk.r3d.exMinZ, sdk.r3d.exMaxZ);
        if(System.getenv("MAPSTAT")!=null) System.out.println("[MAPSTAT] drawMap="+sdk.mapDraws+" abortos="+sdk.mapAborts+" copiaSalvou="+sdk.mapSalvos);
        if(System.getenv("FMTSTAT")!=null){ StringBuilder sb=new StringBuilder("[FMTSTAT]");
            for(java.util.Map.Entry<Integer,Long> e : sdk.r3d.fmtBinds.entrySet()) sb.append(" fmt").append(e.getKey()).append("=").append(e.getValue());
            System.out.println(sb); }
        if(System.getenv("TEXSTAT")!=null) System.out.println("[TEXSTAT] comTextura="+sdk.r3d.primTex+"  semTextura="+sdk.r3d.primNoTex+"  (comUVmasSemTex="+sdk.r3d.primUvSemTex+"  texModeOff="+sdk.r3d.primTexModeOff+")");
        if(System.getenv("R3DSTAT")!=null) System.out.println("[BLEND] triangulos_com_blend="+sdk.r3d.blendFrags+"  sem_blend="+sdk.r3d.noBlendFrags+"  colorBlend_final="+sdk.r3d.colorBlendPublic());
        if(System.getenv("R3DSTAT")!=null) System.out.println("[RASTER] w<=0="+sdk.r3d.rtNearW+" areaZero="+sdk.r3d.rtZeroArea+" backface="+sdk.r3d.rtCull+" foraDaTela="+sdk.r3d.rtEmptyBox);
        if(System.getenv("R3DSTAT")!=null) System.out.println("[LUZCHAMADAS] vSetLight="+sdk.r3d.nSetLight+" vResetLights="+sdk.r3d.nResetLights+" vSetAmbientLight="+sdk.r3d.nSetAmbient+" vSetMaterial="+sdk.r3d.nSetMaterial+" vMatrixSetLight="+sdk.r3d.nMatrixSetLight);
        if(System.getenv("R3DSTAT")!=null) System.out.println("[LUZ] iluminados="+sdk.r3d.litYes+" semNormais="+sdk.r3d.litNoNormals+" luzDesligada="+sdk.r3d.litDisabled);
        if(System.getenv("R3DSTAT")!=null) System.out.println("[EMIT] emitTri="+sdk.r3d.emitCalls+" foraDeFaixa="+sdk.r3d.emitOob+" runsCurtos="+sdk.r3d.runsZeroLen+" 1oIndice="+sdk.r3d.firstIdxVal);
        if(System.getenv("R3DSTAT")!=null) System.out.println("[IDX] aceitos="+sdk.r3d.idxOk+" descartados="+sdk.r3d.idxBail+" (semVerts="+sdk.r3d.idxNoVerts+" semIdx="+sdk.r3d.idxNoIdx+" idxCount<3="+sdk.r3d.idxFewIdx+")  1o: idxCount="+(sdk.r3d.idxFirst>>32)+" count="+(sdk.r3d.idxFirst&0xffffffffL));
        if(System.getenv("R3DSTAT")!=null) System.out.println("[R3D] triangulos="+sdk.r3d.rasterStatTris+" pixels_escritos="+sdk.r3d.rasterStatPix+" pixels_rejeitados_por_Z="+sdk.r3d.rasterStatZfail+" frustum(vis="+sdk.r3d.frustumVis+" cull="+sdk.r3d.frustumCull+")");
        vm.dumpLastPcs();
        if(snap!=null) scr.savePng(snap);
    }
}
