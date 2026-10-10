package wolphun.vm;

import wolphun.Screen;
import java.util.*;

/**
 * Camada de APIs do SDK mophun (vGfx*, vSprite*, vMap*, ...). Cada CALLl p/ um
 * import cai aqui via invoke(nome). Args em p0..p3/pilha, retorno em r0.
 * Subconjunto essencial implementado; o resto e logado (para irmos completando).
 */
public final class Sdk {
    // Sprites RGB332 (fmt7): indice 0 e PRETO OPACO, nao transparente. O SDK e explicito — a
    // transparencia 2D vem do TRANSFER MODE, nao do formato: vmgp.h "MODE_TRANS(1): Transparent copy
    // mode, color 0 is treated as transparent"; e VCAPS_RGB332 e "direct color" (sem canal alpha).
    // Antes forcavamos fmt7 sempre transparente (workaround p/ um quadrado preto no ceu do HC2): isso
    // furava os fundos DITHERED que usam preto como uma das 2 cores — os pixels do dither nao eram
    // escritos e o frame ANTERIOR vazava = "smearing entre os pixels" (Joe's Treasure Quest: 13,5% da
    // area 3D ficava sem redesenhar; com o fix: 0,0%). Escape: FMT7TRANSP=1 restaura o antigo.
    final Pip2 vm; final Screen scr;
    long tick0 = System.currentTimeMillis();
    int nextHeap;
    // Allocator com FREE LIST: reclama memoria liberada por vDisposePtr/vFreePtr. Sem isto (bump puro) o
    // "livre" so caia -> jogos com detector de leak (Deep Abyss: aloca+libera temporarios e checa se vMemFree()
    // voltou ao valor de antes) disparavam "MEMORYLEAK". liveBlocks: ptr->tamanho; freeList: blocos livres p/
    // reuso; freeBytes: total livre reciclavel. vMemFree = (heapEnd-nextHeap)+freeBytes.
    final java.util.HashMap<Integer,Integer> liveBlocks = new java.util.HashMap<>();
    final java.util.ArrayList<int[]> freeList = new java.util.ArrayList<>();   // [ptr, size]
    int freeBytes = 0;
    int foreColor=0xff, backColor=0, transferMode=1;   // default = TransferMode.Transparent (nofun)
    // fonte bitmap definida pelo jogo (VMGPFONT, usada por vPrint). ptr=0 => usa a fonte do sistema.
    int aFontPtr=0, aFontData=0, aFontChartbl=0, aFontBpp=0, aFontW=0, aFontH=0, aFontPal=0;
    long rngState = 0x12345678L;
    public boolean soundEnabled = true;   // configuravel pelo emulador (Settings)
    public boolean reverb = true;         // false = mata o "eco"/reverb do MIDI (transforma o SMF, ver MidiReverb)
    // identidade do aparelho (vGetCaps System): deviceId = (model<<16)|vendor. Configuravel.
    public int deviceModel = 2;            // SonyEricssonT610 (default)
    public int deviceVendor = 3;           // SonyEricsson
    // formato grafico reportado no VideoCaps (vmgpvfmt.h): 7 = RGB332 (256 cores diretas), que e o
    // framebuffer real deste emulador em TODOS os perfis de aparelho. "256 colors" nos jogos = formato>=6.
    public int videoFormat = 7;            // VCAPS_RGB332
    public int apiMinor = 50;              // vGetVMGPInfo = (2<<16)|apiMinor

    // sprites
    int spriteCount=0; int[] slotSprite; short[] slotX, slotY;
    // true enquanto vUpdateSprite/vUpdateSpriteMap percorrem os SLOTS. O vmgp.h documenta o transfer
    // mode APENAS no vDrawObject; o motor de slots nao o menciona -- desenha objetos sobre um fundo,
    // logo a cor 0 e sempre a cor-chave.
    boolean spriteSlotDraw=false;
    // mapa atual
    int mapHeaderPtr=0; int mapX=0, mapY=0;
    // COPIA do MAP_HEADER. O vmgp.h descreve vMapInit/vMapHeaderUpdate como quem "aloca" os recursos
    // do tilemap a partir da definicao (vMapDispose "releases all resources used by the current
    // tilemap, as allocated by vMapInit() or vMapHeaderUpdate()"), entao o jogo pode reaproveitar o
    // buffer da struct depois de chamar. O Shooting Star monta o header num buffer temporario e o
    // zera em seguida: guardando so o PONTEIRO, drawMap lia 0x0 e nao pintava nada -> o fundo nunca
    // era repintado e tudo que se movia deixava rastro. mapSync() atualiza a copia enquanto o header
    // do jogo continuar valido (preserva jogos que editam a struct no lugar) e a congela quando morre.
    int mhFlag, mhFmt, mhW, mhH, mhAnim, mhXPan, mhYPan, mhXPos, mhYPos, mhMapData, mhTileData;
    boolean mhValid=false;
    /** ponteiro que pode conter dados de verdade: secao estatica, ou heap ja alocado. */
    boolean plausiblePtr(int p){
        if(p < vm.base || p >= vm.base+vm.mem.length) return false;
        return p < vm.heapBase || p < nextHeap;
    }
    /**
     * Le o MAP_HEADER do jogo para a copia interna. So faz isso quando o jogo MANDA (vMapInit /
     * vMapHeaderUpdate, que zeram mhValid) -- nunca a cada desenho.
     *
     * Motivo (Crazy Bombs, achado por desmontagem em 0x101d0): a funcao que monta a fase poe o
     * MAP_HEADER na PILHA (`ADDQ sp = sp+-28`, header em sp+4, `ADDQ p0 = sp+4` antes do vMapInit)
     * e depois RETORNA. Dali em diante aquela memoria e reaproveitada por outras chamadas, entao
     * reler o header vivo a cada drawMap trazia lixo de pilha -- largura/altura/ponteiros aleatorios
     * -> o cenario virava faixas de fragmentos, e piorava quando o jogo alocava (o TIRO da nave).
     * O exemplo oficial do SDK (tutorials/2DBackground) nao expoe isso porque a funcao dele nunca
     * retorna (fica no while(1)); o runtime real obviamente tira um INSTANTANEO no vMapInit, senao
     * o jogo quebraria no aparelho tambem. A doc confirma: vMapInit "initializes the map functions
     * with the settings specified in map", e vMapHeaderUpdate existe justamente p/ o jogo avisar
     * que mexeu no header. Os setters (vMapSetXY/vMapSetTile/vMapSetAttribute) atualizam a copia.
     */
    void mapSync(){
        if(mhValid) return;                       // ja temos o instantaneo; nao rele a pilha
        int hp=mapHeaderPtr; if(hp==0) return;
        int w=vm.read8(hp+2)&0xff, h=vm.read8(hp+3)&0xff;
        int md=vm.read32(hp+16), td=vm.read32(hp+20);
        if(w==0||h==0||md==0||td==0) return;          // header ja destruido: mantem a copia
        // Ponteiro PLAUSIVEL = area estatica (code/data/bss/res) ou heap ABAIXO do topo alocado.
        // O MAP_HEADER do Crazy Bombs vive na PILHA e e sobrescrito: passava a valer tileData que
        // nunca foi alocado. "Diferente de zero" nao basta -- desenhavamos tiles lidos de memoria
        // nunca escrita e a fase virava faixas de fragmentos.
        if(!plausiblePtr(md) || !plausiblePtr(td)) return;
        mhFlag=vm.read8(hp)&0xff; mhFmt=vm.read8(hp+1)&0xff; mhW=w; mhH=h; mhAnim=vm.read8(hp+4)&0xff;
        mhXPan=(short)vm.read16(hp+8);  mhYPan=(short)vm.read16(hp+10);
        mhXPos=vm.read16(hp+12)&0xffff; mhYPos=vm.read16(hp+14)&0xffff;   // MAP_HEADER.x/y sao uint16
        mhMapData=md; mhTileData=td; mhValid=true;
    }
    // streams (handle 1-based -> dados do recurso + posicao)
    final Map<Integer,byte[]> streamData = new HashMap<Integer,byte[]>();
    final Map<Integer,int[]>  streamPos  = new HashMap<Integer,int[]>();
    // streams de ARQUIVO (save game): alvo em disco + se e gravavel (flush no close)
    final Map<Integer,java.io.File> streamFile     = new HashMap<Integer,java.io.File>();
    final Map<Integer,Boolean>      streamWritable = new HashMap<Integer,Boolean>();
    int nextFd = 1;
    // pasta onde ficam os saves ("resume", "highscore"). Main aponta p/ ao lado do .mpn.
    public java.io.File saveDir = new java.io.File("saves");

    final Set<String> loggedMissing = new HashSet<String>();
    public boolean trace = false;

    public final VSound vsound;
    public final Renderer3D r3d;
    // "geracao" do jogo (nofun): jogos que importam vInit3D = SDK >=1.50; senao = SDK 1.30.
    // No SDK <1.50 o vFillRect IGNORA a janela de recorte (Graphics.cs) — essencial p/ o fundo
    // das corridas pseudo-3D (XxX/V-Rally clipam a tela ao topo e ainda assim preenchem o chao).
    public boolean version150 = false;
    static final int SYSCTL_SETVIBRATE = 1;    // vmgp.h
    /** Saida de vibracao do aparelho. A VM nao conhece Android: quem implementa e o app
        (Vibra.java), e o GameSession liga os dois. null = sem vibrador ou desligado nas opcoes. */
    public interface Vibrador { void vibra(int ms); void para(); }
    public Vibrador vibrador;
    public final boolean fmt7Transp;   // HC2: fmt7 idx0 = transparente mesmo com tm=0 (ver detectaFmt7Transp)
    public Sdk(Pip2 vm, Screen scr){
        this.vm=vm; this.scr=scr; this.nextHeap=vm.heapBase; this.vsound=new VSound(vm); this.r3d=new Renderer3D(vm,scr);
        this.version150 = vm.exe.importsSymbol("vInit3D");
        this.fmt7Transp = detectaFmt7Transp(vm.exe);
    }
    // ---- fmt7 (RGB332) com indice 0 = chave de transparencia, POR JOGO ----
    // Regra geral: a transparencia 2D vem do TRANSFER MODE. O Honey Cave 2 e a excecao medida: TODOS os
    // seus vDrawObject saem com fmt=7 e tm=0 (MODE_BLOCK) -- a lua do ceu, os sprites do HUD -- e mesmo
    // assim no aparelho real eles aparecem recortados. Respeitar o tm da um retangulo preto em volta de
    // cada sprite. Ligar globalmente regride os fundos DITHERED do Joe's Treasure Quest (preto e uma das
    // 2 cores do dither -> buracos + smearing), por isso o escopo e so o HC2.
    // Identificacao pelo CONTEUDO do container (nao pelo nome do arquivo): as strings "Level1.mec".."Level4.mec"
    // existem em 1 dos 246 jogos testados -- o HC2. O HC1 nao as tem.
    static boolean detectaFmt7Transp(Vmgp exe){
        return contem(exe.all,"Level1.mec") && contem(exe.all,"Level4.mec");
    }
    private static boolean contem(byte[] d, String s){
        byte[] n; try { n=s.getBytes("ISO-8859-1"); } catch(Exception e){ return false; }
        outer: for(int i=0;i+n.length<=d.length;i++){
            for(int j=0;j<n.length;j++) if(d[i+j]!=n[j]) continue outer;
            return true;
        }
        return false;
    }

    public void invoke(String name){
        if(trace){
            String extra="";
            if(name.equals("vTextOut")||name.equals("vTextOutU")) extra=" str=\""+readStr(vm.arg(2),name.endsWith("U"))+"\"";
            else if(name.equals("vTextExtentU")||name.equals("vTextExtent")) extra=" str=\""+readStr(vm.arg(0),name.endsWith("U"))+"\"";
            System.out.println("  SDK "+name+"  p0="+vm.arg(0)+" p1="+vm.arg(1)+" p2="+vm.arg(2)+" p3="+vm.arg(3)+extra);
        }
        switch(name){
            // ---------- memoria ----------
            case "vNewPtr": case "vMalloc": case "vNewPtrDbg": vm.ret(alloc(vm.arg(0), false)); break;
            case "vNewPtrClear": case "vAllocClear": case "vCallocPtr": vm.ret(alloc(vm.arg(0), true)); break;
            case "vDisposePtr": case "vFreePtr": freePtr(vm.arg(0)); vm.ret(0); break;   // reclama (free list)
            // vMemFree()/vMaxFreeBlock(): CONSULTAS (nao liberam!) — retornam bytes livres no heap. O Da Vinci
            // Code chama vMemFree() antes de carregar o nivel 3D; retornar 0 fazia ele achar que nao ha memoria
            // -> nao carrega -> tela preta no New Game. Bump allocator: livre = heapEnd-nextHeap.
            case "vMemFree": case "vMaxFreeBlock": case "vMemAvail": vm.ret((vm.heapEnd - nextHeap) + freeBytes); break;   // bump restante + free list
            // ---------- sistema ----------
            case "vGetTickCount": vm.ret((int)(System.currentTimeMillis()-tick0)); break;
            case "vGetTime": vm.ret((int)(System.currentTimeMillis()/1000L)); break;   // segundos desde 1970 (epoch UTC). Football Pro usa p/ seed/relogio; antes caia no default=0 -> logica de tempo quebrava
            case "vGetTimeDate": case "vGetTimeDateUTC": writeDateTime(vm.arg(0)); vm.ret(0); break;
            // vGetRandom: 16 bits (VRAND_MAX=0xffff no SDK) devolvidos com SINAL ESTENDIDO p/ 32 bits,
            // EXATAMENTE como o emulador oficial (log: retornos positivos <32768 E 0xFFFFxxxx negativos
            // = bit 15 do valor 16-bit). Antes devolviamos so 15 bits [0,32767] (nunca bit 15) -> jogos
            // que checam o bit-de-sinal p/ um coin-flip (ex.: Honey Cave 1 dano/recuperacao de vida)
            // NUNCA disparavam. O ARGUMENTO e IGNORADO (jogos fazem o proprio modulo; senao Bouncy travava).
            case "vGetRandom": { rngState=rngState*6364136223846793005L+1442695040888963407L; vm.ret((short)(rngState>>>33)); break; }
            case "vSetRandom": rngState = (vm.arg(0)&0xffffffffL); break;
            case "vGetCaps": vm.ret(getCaps(vm.arg(0), vm.arg(1))); break;
            // vSysCtl(cmd, arg). SYSCTL_SETVIBRATE(1): bit0 = liga/desliga, bits 16-31 = duracao em ms
            // -- e o que o macro vSetVibrate(on,time) do vmgp.h monta: vSysCtl(1, on | (time<<16)).
            // O Honey Cave 2 usa isso ao tomar dano: medido, manda cmd=1 arg=0x012C0001 = ligar por 300 ms.
            // Era no-op; o aparelho nunca vibrava. Os demais comandos continuam ignorados (retornam 0).
            case "vSysCtl": {
                if(vm.arg(0)==SYSCTL_SETVIBRATE && vibrador!=null){
                    int a=vm.arg(1);
                    int ms=(a>>>16)&0xffff;
                    if((a&1)!=0) vibrador.vibra(ms>0? ms : 200);   // on sem duracao: pulso curto padrao
                    else         vibrador.para();
                }
                vm.ret(0); break;
            }
            case "vTerminateVMGP": vm.stopped=true; break;

            // ---- Tarefas (multitarefa cooperativa; semantica = nofun) ----
            case "vSetStackSize": { taskStackSize = Math.max(4096, (vm.arg(0)+3)&~3); vm.ret(taskStackSize); break; }
            case "vCreateTask": {
                int entry=vm.arg(0), p0=vm.arg(1), p1=vm.arg(2), p2=vm.arg(3);
                int blk = alloc(taskStackSize, false);
                if(blk==0){ vm.ret(-1); break; }
                vm.ret(vm.createTask(entry, p0, p1, p2, blk + taskStackSize, blk));
                break;
            }
            case "vDisposeTask": vm.disposeTask(vm.arg(0)); vm.ret(0); break;
            case "vTaskAlive": vm.ret(vm.taskAlive(vm.arg(0))?1:0); break;
            case "vThisTask": vm.ret(vm.thisTaskId()); break;
            case "vSend": { Pip2.Task t=vm.tasks.get(vm.arg(0)); if(t!=null) t.recv=vm.arg(1); vm.ret(0); break; }
            case "vReceiveAny": { Pip2.Task t=vm.tasks.get(vm.arg(0)); vm.ret(t!=null? t.recv : 0); break; }
            case "vReceive": { Pip2.Task t=vm.currentTask; vm.ret(t!=null? t.recv : 0); break; }

            case "vAbs": vm.ret(Math.abs(vm.arg(0))); break;
            case "vStrCmp": {
                int a=vm.arg(0), b=vm.arg(1), i2=0, r=0;
                while(true){ int ca=vm.read8(a+i2), cb=vm.read8(b+i2); if(ca!=cb){ r=ca-cb; break; } if(ca==0) break; i2++; }
                vm.ret(r); break;
            }
            // ordem de bytes de rede (init de comunicacao; Martial Arts 3D)
            case "htons": { int v=vm.arg(0)&0xffff; vm.ret(((v&0xff)<<8)|((v>>8)&0xff)); break; }
            case "htonl": { int v=vm.arg(0);
                vm.ret(((v&0xff)<<24)|(((v>>8)&0xff)<<16)|(((v>>16)&0xff)<<8)|((v>>>24)&0xff)); break; }
            // strings UNICODE (pip_wchar_t = 16 bits); devolvem o ponteiro p/ o ' ' final (SDK)
            case "vStrLenU": { int p=vm.arg(0), n=0; while(vm.read16(p+n*2)!=0) n++; vm.ret(n); break; }
            case "vStrCpyU": { int dst=vm.arg(0), src=vm.arg(1), i2=0; int w;
                while(true){ w=vm.read16(src+i2*2); vm.write16(dst+i2*2, w); if(w==0) break; i2++; }
                vm.ret(dst+i2*2); break; }
            case "vStrToU": { int dst=vm.arg(0), src=vm.arg(1), i2=0; int b2;
                while(true){ b2=vm.read8(src+i2); vm.write16(dst+i2*2, b2); if(b2==0) break; i2++; }
                vm.ret(dst+i2*2); break; }
            case "vStreamReady": vm.ret(streamData.containsKey(vm.arg(0)) ? vm.arg(1) : 0); break;


            // ---------- identidade/DRM/versao (valores do nofun) ----------
            case "vCheckDataCert": case "vCheckDataCertFile": vm.ret(1); break;   // stub de sucesso (nofun)
            case "vCheckIMEI": vm.ret(1); break;
            case "vCheckNetwork": vm.ret(1); break;
            case "vGetVMGPInfo": vm.ret((2<<16)|apiMinor); break;                 // API v2.xx
            case "vUID": vm.ret(0xDEADBEEF); break;                               // nofun
            case "vFrameTickCount": vm.ret(scr.flips); break;                     // contador de frames = flips
            case "vYieldToSystem": vm.ret(0); break;
            case "vWaitVBL": vm.ret(0); break;                                    // no-op (pacing esta no vFlipScreen)
            case "vTestKey":  vm.ret(testKey(vm.arg(0))); break;   // 1 se a tecla ASCII do keypad esta pressionada
            case "vScanKeys": vm.ret(scanKeys()); break;           // ASCII da tecla atualmente pressionada
            case "vBeep": if(soundEnabled) vsound.beep(vm.arg(0), vm.arg(1)); break;   // tom simples (Mooktown Tennis, Alpine Ski)
            // ---------- graficos ----------
            case "vClearScreen": dlog("clearScreen fore="+colStr(vm.arg(0))); Arrays.fill(scr.fb, getColor(vm.arg(0))); vm.ret(0); break;
            case "vFlipScreen": if(DRAWLOG) dlog("--- FLIP (frame "+drawLogFrame+") ---"); scr.present(); r3d.clearDepthPerFrame(); vm.ret(0); break;
            case "vSetForeColor": foreColor=vm.arg(0); break;   // cor crua (indice ou RGB555 se bit31)
            case "vSetBackColor": backColor=vm.arg(0); break;
            case "vSetPaletteEntry": scr.setPaletteRgb555(vm.arg(0), vm.arg(1)); break;
            case "vGetPaletteEntry": vm.ret(Screen.argbToRgb555(scr.palette[vm.arg(0)&0xff])); break;
            case "vSetPalette": setPalette(vm.arg(0), vm.arg(1), vm.arg(2)); break;
            // vSetTransferMode DEVOLVE o modo ANTERIOR (vmgp.h). Nao devolver nada deixava o padrao
            // "old=vSetTransferMode(X); ...; vSetTransferMode(old);" restaurar lixo -- na pratica 0 --
            // e o modo ficava preso em MODE_BLOCK (HoneyCave 2: o aro branco do icone de mel ganhava
            // mordidas pretas porque os cantos do sprite deixavam de ser chave).
            case "vSetTransferMode": { int prevTm=transferMode; transferMode=vm.arg(0); vm.ret(prevTm); break; }
            case "vSetClipWindow": dlog("setClipWindow ("+(short)vm.arg(0)+","+(short)vm.arg(1)+")-("+(short)vm.arg(2)+","+(short)vm.arg(3)+")"); scr.setClip((short)vm.arg(0),(short)vm.arg(1),(short)vm.arg(2),(short)vm.arg(3)); break;
            case "vFillRect": fillRect((short)vm.arg(0),(short)vm.arg(1),(short)vm.arg(2),(short)vm.arg(3)); break;
            case "vDrawLine": drawLine((short)vm.arg(0),(short)vm.arg(1),(short)vm.arg(2),(short)vm.arg(3)); break;
            // vPlot(int16 x, int16 y): UM pixel na foreColor, "The operation is clipped" (vmgp.h).
            // Era no-op: o Hyper Apocalypse desenha TODO o campo de estrelas/particulas ponto a ponto
            // (144.480 vPlot + 173.351 vSetForeColor numa partida) -> fundo do espaco 100% preto.
            case "vPlot": px((short)vm.arg(0), (short)vm.arg(1), getColor(foreColor)); break;
            case "vGetPixel": vm.ret(0); break;
            case "vUpdateSpriteMap": drawMap(); drawSpriteSlots(); break;
            // ---------- input ----------
            case "vGetButtonData": vm.ret(scr.getButtons()); break;
            // ---------- sprites ----------
            case "vSpriteInit": spriteCount=vm.arg(0)&0xff; slotSprite=new int[spriteCount]; slotX=new short[spriteCount]; slotY=new short[spriteCount]; vm.ret(1); break;
            case "vSpriteClear": if(slotSprite!=null) Arrays.fill(slotSprite,0); break;
            case "vSpriteSet": { int s=vm.arg(0)&0xff; if(slotSprite!=null&&s<spriteCount){ slotSprite[s]=vm.arg(1); slotX[s]=(short)vm.arg(2); slotY[s]=(short)vm.arg(3);} break; }
            case "vUpdateSprite": drawSpriteSlots(); break;
            case "vDrawObject": drawObject((short)vm.arg(0),(short)vm.arg(1),vm.arg(2)); break;
            case "vDrawTile":   drawTile(vm.arg(0),vm.arg(1),(short)vm.arg(2),(short)vm.arg(3)); break;   // tile 8x8 (chao/pista 3D do Rally)
            case "vUpdateSpriteRotate": break;
            case "vSpriteBoxCollision": vm.ret(spriteBoxCollision(vm.arg(0), vm.arg(1)&0xff, vm.arg(2)&0xff)); break;
            case "vSpriteCollision": vm.ret(spriteCollision(vm.arg(0)&0xff, vm.arg(1)&0xff, vm.arg(2)&0xff)); break;
            // ---------- tilemap ----------
            case "vMapInit": mapHeaderPtr=vm.arg(0); mhValid=false; mapSync(); vm.ret(1); break;
            // vMapHeaderUpdate(MAP_HEADER*): "Update tilemap definition" (vmgp.h) -- re-aponta o motor
            // de tiles p/ outro header, igual ao vMapInit. Sem isto o Shooting Star trocava de mapa e
            // continuavamos lendo o header ANTIGO (ja liberado, mapData=0) -> drawMap abortava, o fundo
            // nunca era repintado e o frame anterior ficava na tela (smearing em tiles e sprites).
            case "vMapHeaderUpdate": mapHeaderPtr=vm.arg(0); mhValid=false; mapSync(); vm.ret(1); break;
            case "vMapDispose": mapHeaderPtr=0; mhValid=false; break;
            case "vUpdateMap": drawMap(); break;
            // escreve na struct do jogo (se ainda viva) E na copia -- e a copia que o drawMap usa
            case "vMapSetXY": if(mapHeaderPtr!=0){ vm.write16(mapHeaderPtr+12, vm.arg(0)); vm.write16(mapHeaderPtr+14, vm.arg(1)); }
                mhXPos=vm.arg(0)&0xffff; mhYPos=vm.arg(1)&0xffff; break;   // vMapSetXY(uint16,uint16)
            // vMapGetAttribute/Tile(byte x, byte y): args sao BYTES no nofun -> mascarar &0xFF (corrige x "negativo" ao virar esquerda)
            case "vMapGetAttribute": vm.ret(mapAttr(vm.arg(0)&0xff, vm.arg(1)&0xff)); break;
            case "vMapGetTile": vm.ret(mapTile(vm.arg(0)&0xff, vm.arg(1)&0xff)); break;
            // vMapSetTile/vMapSetAttribute(u8 x, u8 y, u8 v): ESCREVEM no mapData da VM (base-1; drawMap le de
            // volta). Sem isto eram no-op -> tilemap VAZIO -> jogos SEM CHAO e com "smearing" (vUpdateMap nao
            // desenhava nada, o fundo nao cobria a tela, o frame anterior persistia). ABI canonica x=P0,y=P1,v=P2
            // (confirmado por disassembly do 1849 Gold Rush: MOVB a0=x; MOVB a1=y; LDQ a2=v; calll). Mascarar &0xFF
            // pois x/y sao carregados com MOVB (so o byte baixo; bits altos = lixo do valor anterior do registrador).
            case "vMapSetTile": mapSetTile(vm.arg(0)&0xff, vm.arg(1)&0xff, vm.arg(2)&0xff); break;
            case "vMapSetAttribute": mapSetAttr(vm.arg(0)&0xff, vm.arg(1)&0xff, vm.arg(2)&0xff); break;
            // ---------- texto (fonte do sistema via Java) ----------
            case "vSelectFont": scr.selectFont(vm.arg(0), vm.arg(1)); vm.ret(vm.arg(1)); break;   // size + flags (SDK)
            case "vSetActiveFont": vm.ret(setActiveFont(vm.arg(0))); break;   // fonte bitmap do jogo (VMGPFONT)
            case "vTextOut":  scr.drawText((short)vm.arg(0), (short)vm.arg(1), readStr(vm.arg(2), false), getColor(foreColor)); vm.ret(0); break;
            case "vTextOutU": scr.drawText((short)vm.arg(0), (short)vm.arg(1), readStr(vm.arg(2), true),  getColor(foreColor)); vm.ret(0); break;
            case "vPrint":    vPrint(vm.arg(0), (short)vm.arg(1), (short)vm.arg(2), readStr(vm.arg(3), false)); vm.ret(0); break;
            // extent = (altura << 16) | largura (o jogo le srli #16 p/ altura, &0xFFFF p/ largura)
            case "vTextExtent":  vm.ret(textExtent(readStr(vm.arg(0), false))); break;
            case "vTextExtentU": vm.ret(textExtent(readStr(vm.arg(0), true))); break;
            case "vCharExtent":  vm.ret(textExtent(new String(Character.toChars(vm.arg(0)&0xffff)))); break;
            // vMsgBox(flags, mensagem, titulo): flags&0x80=tem titulo. Modal, retorna botao (1=OK).
            case "vMsgBoxU": vm.ret(scr.showMessageBox((vm.arg(0)&0x80)!=0?readStr(vm.read32(vm.getReg(Pip2.SP)),true):null, readStr(vm.arg(1),true))); break;
            case "vMsgBox":  vm.ret(scr.showMessageBox((vm.arg(0)&0x80)!=0?readStr(vm.read32(vm.getReg(Pip2.SP)),false):null, readStr(vm.arg(1),false))); break;
            // ---------- string ----------
            case "vStrLen": { int p=vm.arg(0),n=0; while(vm.read8(p+n)!=0)n++; vm.ret(n); break; }
            // vStrCpy devolve o PONTEIRO PARA O FIM (o ' ' escrito), NAO o inicio como o strcpy do C.
            // Os jogos encadeiam isso p/ montar strings: o printf interno do Da Vinci Code faz
            //   out = vStrCpy(out, arg)   e continua escrevendo a partir do retorno.
            // Devolvendo o inicio, o texto seguinte SOBRESCREVIA o que acabou de ser copiado
            // ("levels/%s.clevel" + "0_TheHotel" saia como "levels/.clevel"). Semantica = nofun.
            case "vStrCpy": { int dst=vm.arg(0),src=vm.arg(1),i=0; int b;
                while(true){ b=vm.read8(src+i); vm.write8(dst+i,b); if(b==0) break; i++; }
                vm.ret(dst+i); break; }
            case "vitoa": vm.ret(itoa(vm.arg(0),vm.arg(1),true, vm.arg(2)&0xff, vm.arg(3)&0xff)); break;
            case "vutoa": vm.ret(itoa(vm.arg(0),vm.arg(1),false, vm.arg(2)&0xff, vm.arg(3)&0xff)); break;
            // ---------- descompressao ----------
            // vDecompress(source, dest, streamHandle, readBufSize): source==0 => descomprime do stream
            case "vDecompress": vm.ret(vm.arg(0)==0 ? decompressStream(vm.arg(2), vm.arg(1)) : decompress(vm.arg(0), vm.arg(1))); break;
            // vDecompHdr(infoPtr, source): le o header LZ, preenche NativeCompressedFileInfo, retorna tamanho descomprimido
            case "vDecompHdr": vm.ret(decompHdr(vm.arg(0), vm.arg(1))); break;
            // ---------- streams (recursos do .mpn) ----------
            case "vStreamOpen": vm.ret(streamOpen(vm.arg(0), vm.arg(1))); break;
            case "vStreamRead": vm.ret(streamRead(vm.arg(0), vm.arg(1), vm.arg(2))); break;
            case "vStreamWrite": vm.ret(streamWrite(vm.arg(0), vm.arg(1), vm.arg(2))); break;
            case "vStreamSeek": vm.ret(streamSeek(vm.arg(0), vm.arg(1), vm.arg(2))); break;
            case "vStreamClose": streamClose(vm.arg(0)); vm.ret(0); break;
            case "vPlayResource": vm.ret(playResource(vm.arg(0), vm.arg(1), vm.arg(2))); break;
            // ---------- som avancado (PCM via modulo VSound) ----------
            case "vSoundInit": vsound.enabled=soundEnabled; vm.ret(soundEnabled? vsound.init() : -1); break;
            case "vSoundGetHandle": case "vSoundLoad": vm.ret(soundEnabled? vsound.getHandle(vm.arg(0)) : -1); break;
            case "vSoundCtrl": vm.ret(vsound.ctrl(vm.arg(0), vm.arg(1), 0)); break;
            case "vSoundCtrlEx": vm.ret(vsound.ctrl(vm.arg(0), vm.arg(1), vm.arg(2))); break;
            case "vSoundDispose": vm.ret(vsound.dispose()); break;
            case "vSoundDisposeHandle": vm.ret(vsound.disposeHandle(vm.arg(0))); break;
            case "vSoundUpload": vm.ret(0); break;
            // ---------- 3D (modulo VMGP3D: rasterizador de software) ----------
            case "vInit3D": break;
            case "vSetViewport": r3d.setViewport(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3)); break;
            case "vSetRenderState": r3d.setRenderState(vm.arg(0),vm.arg(1)); break;
            case "vSetZBuffer": r3d.clearDepth(); break;
            case "vSetTexture": vm.ret(r3d.setTexture(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3))); break;
            case "vCreateTexture": vm.ret(r3d.createTexture(vm.arg(0))); break;                 // textura gerenciada c/ paleta propria
            case "vSetActiveTexture": r3d.setActiveTexture(vm.arg(0)); vm.ret(0); break;         // reativa textura gerenciada (handle)
            case "vSetFunctionalTexture": vm.ret(0); break;   // texturas funcionais/procedurais nao suportadas (raras) -> no-op
            case "vDeleteTexture": r3d.deleteTexture(vm.arg(0)); vm.ret(0); break;
            case "vDeleteAllTextures": r3d.deleteAllTextures(); vm.ret(0); break;
            case "vFreeTexture": vm.ret(0); break;                                               // no-op (igual ao nofun)
            case "vRenderPrimitive": r3d.renderPrimitive(vm.arg(0),vm.arg(1)); vm.ret(1); break;
            case "vRenderPrimitiveIndexed": r3d.renderPrimitiveIndexed(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3)); vm.ret(1); break;
            case "vDrawPolygon": r3d.drawPolygon(vm.arg(0),vm.arg(1),vm.arg(2)); break;   // 3D (3 NativeVertexGST)
            case "vDrawBillboard": r3d.drawBillboard(vm.arg(0)); break;                   // quad texturizado camera-facing
            case "vDrawFlatPolygon": fillFlatPoly(vm.arg(0)); break;                       // 2D flat tri (foreColor)
            case "vGetInteger": if(vm.arg(1)!=0) vm.write32(vm.arg(1), r3d.getInteger(vm.arg(0))); vm.ret(1); break;
            // matrizes
            case "vSetMatrixMode": r3d.setMatrixMode(vm.arg(0)); break;
            case "vMatrixIdentity": r3d.matrixIdentity(); break;
            case "vMatrixSetCurrent": r3d.matrixSetCurrent(vm.arg(0)); break;
            case "vMatrixGetCurrent": r3d.matrixGetCurrent(vm.arg(0)); break;
            case "vMatrixMultiply": r3d.matrixMultiply(vm.arg(0)); break;
            case "vMatrixSetProjection": r3d.matrixSetProjection(vm.arg(0)); break;
            case "vMatrixSetLight": r3d.matrixSetLight(vm.arg(0)); break;
            case "vMatrixTranslate": r3d.matrixTranslate(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vMatrixScale": r3d.matrixScale(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vMatrixRotateX": r3d.matrixRotateX((short)vm.arg(0)); break;
            case "vMatrixRotateY": r3d.matrixRotateY((short)vm.arg(0)); break;
            case "vMatrixRotateZ": r3d.matrixRotateZ((short)vm.arg(0)); break;
            case "vMatrixRotateVector": r3d.matrixRotateVector(vm.arg(0),(short)vm.arg(1)); break;
            case "vMatrixPerspective": r3d.matrixPerspective(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3)); break;
            case "vMatrixOrtho": r3d.matrixOrtho(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3)); break;
            case "vMatrixLookAt": r3d.matrixLookAt(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            // vetores 3D + frustum + colisao + plano (VMGP3D Math/Helper do nofun)
            case "vVectorAdd": r3d.vectorAdd(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vVectorSub": r3d.vectorSub(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vVectorMul": r3d.vectorMul(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vVectorNormalize": r3d.vectorNormalize(vm.arg(0)); break;
            case "vCrossProduct": r3d.crossProduct(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vDotProduct": vm.ret(r3d.dotProduct(vm.arg(0),vm.arg(1))); break;
            case "vVectorTransformV3": r3d.vectorTransformV3(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vVectorTransformV4": r3d.vectorTransformV4(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vVectorProjectV3": r3d.vectorProjectV3(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vVectorProjectV4": r3d.vectorProjectV4(vm.arg(0),vm.arg(1),vm.arg(2)); break;
            case "vMatrixInvert": r3d.matrixInvert(); break;
            case "vBoxInViewFrustum": vm.ret(r3d.boxInViewFrustum(vm.arg(0))); break;
            case "vCreatePlaneFromPoly": r3d.createPlaneFromPoly(vm.arg(0),vm.arg(1)); break;
            case "vCollisionPointBox": vm.ret(r3d.collisionPointBox(vm.arg(0),vm.arg(1))); break;
            case "vCollisionBoxBox": vm.ret(r3d.collisionBoxBox(vm.arg(0),vm.arg(1))); break;
            case "vCollisionVectorPoly": vm.ret(r3d.collisionVectorPoly(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3))); break;
            case "vCollisionVectorPlane": vm.ret(r3d.collisionVectorPlane(vm.arg(0),vm.arg(1),vm.arg(2))); break;
            case "vFindRGBIndex": vm.ret(findRGBIndex(vm.arg(0))); break;
            // string/misc + musica (tracker: no-op por ora)
            case "vatoi": vm.ret(vatoi(vm.arg(0), vm.arg(1))); break;
            case "vCopyRect": copyRect(vm.arg(0), vm.arg(1), vm.arg(2), vm.arg(3)); break;
            case "vSprintf": vm.ret(sprintfReg(vm.arg(0), vm.arg(1))); break;
            case "vSprintfVa": vm.ret(sprintfVa(vm.arg(0), vm.arg(1), vm.arg(2))); break;
            case "vMusicInit": vm.ret(0); break;                             // SND_OK (igual vSoundInit)
            case "vMusicLoad": vm.ret(1); break;                             // handle dummy de musica          // handle dummy (sem musica, sem crash)
            case "vMusicCtrl": case "vMusicCtrlEx": case "vMusicDispose": case "vMusicDisposeHandle": case "vMusicClose": vm.ret(0); break;
            // iluminacao T&L (so tem efeito quando o jogo habilita LIGHTINGENABLE / state 17)
            case "vSetLight": r3d.setLight(vm.arg(0), vm.arg(1)); break;
            case "vSetMaterial": r3d.setMaterial(vm.arg(0)); break;
            case "vSetMaterial2": r3d.setMaterial2(vm.arg(0)); break;
            case "vSetAmbientLight": r3d.setAmbientLight(vm.arg(0)); break;
            case "vSetCameraPos": r3d.setCameraPos(vm.arg(0)); break;
            case "vResetLights": r3d.resetLights(); break;
            case "vLightPoint": r3d.lightPoint(vm.arg(0)); break;
            case "vSetDisplayWindow": break;
            case "vSetFogColor": r3d.setFogColor(vm.arg(0)); break;
            // 3D: matriz/consulta/vetores extra (VMGP3D)
            case "vMatrixTranspose": r3d.matrixTranspose(); break;
            case "vMatrixMultiply3x3": r3d.matrixMultiply3x3(vm.arg(0)); break;
            case "vGetSupportedRenderStates": vm.ret(0xFFFFFFFF); break;   // permissivo: todos os valores
            case "vGetZBufferValue": vm.ret(r3d.getZBufferValue(vm.arg(0),vm.arg(1))); break;
            case "vVectorArrayAdd": r3d.vectorArrayAdd(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3)); break;
            case "vVectorArrayDelta": r3d.vectorArrayDelta(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3)); break;
            case "vPow": vm.ret(r3d.vPow(vm.arg(0),vm.arg(1))); break;
            // math fixed-point
            case "vMul": vm.ret(r3d.vMul(vm.arg(0),vm.arg(1))); break;
            case "vDiv": vm.ret(r3d.vDiv(vm.arg(0),vm.arg(1))); break;
            case "vSqrt": vm.ret(r3d.vSqrt(vm.arg(0))); break;

            // ---- ponto fixo 16.16 (familia "16"/"F16"; o SDK 2.5 nao documenta, veio depois) ----
            // O resto do SDK usa base 2^14; estas usam 2^16. Sem elas caiam no default (=0) e TODA a
            // matematica de posicao do jogo virava zero (Worms World Party chama vMul16 ~9 mil vezes).
            case "vMul16": vm.ret((int)(((long)vm.arg(0)*vm.arg(1)) >> 16)); break;
            case "vDiv16": { int b=vm.arg(1); vm.ret(b==0?0:(int)(((long)vm.arg(0)<<16)/b)); break; }
            case "vSinF16": vm.ret((int)Math.round(Math.sin(Renderer3D.ang2rad(vm.arg(0))) * 65536.0)); break;
            case "vCosF16": vm.ret((int)Math.round(Math.cos(Renderer3D.ang2rad(vm.arg(0))) * 65536.0)); break;
            // vLimit(valor, min, max): satura no intervalo
            case "vLimit": { int v=vm.arg(0), lo=vm.arg(1), hi=vm.arg(2);
                vm.ret(v<lo?lo:(v>hi?hi:v)); break; }
            // vStrCat(s1,s2): concatena. Devolve o ponteiro para o ' ' final (FIM), como o vStrCpy
            // do mophun -- verificado por ASM que o Worms nao usa o retorno, entao seguimos a familia.
            case "vStrCat": { int d1=vm.arg(0), s2=vm.arg(1), e=0;
                while(vm.read8(d1+e)!=0) e++;                 // vai ate o fim de s1
                int i=0; int b2;
                while(true){ b2=vm.read8(s2+i); vm.write8(d1+e+i,b2); if(b2==0) break; i++; }
                vm.ret(d1+e+i); break; }
            case "vSin": vm.ret(r3d.vSin(vm.arg(0))); break;
            case "vCos": vm.ret(r3d.vCos(vm.arg(0))); break;
            case "vTan": vm.ret(r3d.vTan(vm.arg(0))); break;
            default:
                if(loggedMissing.add(name)) System.out.println("  [SDK not implemented] "+name);
                vm.ret(0);
        }
    }

    // ---------------- implementacoes ----------------
    int taskStackSize = 16384;      // pilha padrao das tarefas
    int alloc(int size, boolean clear){
        if(size<0) return 0;
        int sz=(size+7)&~7; if(sz==0) sz=8;
        // 1) reusa um bloco livre (first-fit); divide se sobrar
        for(int i=0;i<freeList.size();i++){
            int[] b=freeList.get(i);
            if(b[1]>=sz){
                int p=b[0];
                if(b[1]==sz) freeList.remove(i);
                else { b[0]+=sz; b[1]-=sz; }
                freeBytes-=sz; liveBlocks.put(p, sz);
                // SEMPRE zera bloco REUSADO: os jogos contam com memoria zerada.
                for(int j=0;j<sz;j++) vm.write8(p+j,0);
                return p;
            }
        }
        // 2) bump
        int p=nextHeap;
        if(p+sz>vm.heapEnd) return 0;
        nextHeap+=sz; liveBlocks.put(p, sz);
        for(int i=0;i<sz;i++) vm.write8(p+i,0);   // sempre zerado (os jogos contam com isso)
        return p;
    }
    // libera um bloco: se for o TOPO do heap recua nextHeap (e coalesce blocos livres adjacentes ao topo);
    // senao entra na free list p/ reuso. Ponteiro desconhecido (ex.: alocado antes de um loadState) = ignora.
    void freePtr(int ptr){
        Integer szO = liveBlocks.remove(ptr);
        if(szO==null) return;
        int sz=szO;
        if(ptr+sz==nextHeap){
            nextHeap=ptr;
            boolean merged=true;
            while(merged){
                merged=false;
                for(int i=0;i<freeList.size();i++){
                    int[] b=freeList.get(i);
                    if(b[0]+b[1]==nextHeap){ nextHeap=b[0]; freeBytes-=b[1]; freeList.remove(i); merged=true; break; }
                }
            }
        } else { freeList.add(new int[]{ptr,sz}); freeBytes+=sz; }
    }
    void zero(int ptr,int n){ for(int i=0;i<n;i++) vm.write8(ptr+i,0); }

    // VMDateTime = 8 bytes: year(u16)@0, day(u16)@2, month(b)@4, hour(b)@5, minute(b)@6, second(b)@7
    // CRITICO: escrever EXATAMENTE 8 bytes (o jogo passa buffer na PILHA junto ao RA; zerar demais corrompe o RA)
    void writeDateTime(int ptr){
        java.util.Calendar c = java.util.Calendar.getInstance();
        vm.write16(ptr+0, c.get(java.util.Calendar.YEAR));
        vm.write16(ptr+2, c.get(java.util.Calendar.DAY_OF_MONTH));
        vm.write8 (ptr+4, c.get(java.util.Calendar.MONTH)+1);
        vm.write8 (ptr+5, c.get(java.util.Calendar.HOUR_OF_DAY));
        vm.write8 (ptr+6, c.get(java.util.Calendar.MINUTE));
        vm.write8 (ptr+7, c.get(java.util.Calendar.SECOND));
    }

    // --- instrumentacao TEMPORARIA de draws (investigacao do terreno das corridas) ---
    public static boolean DRAWLOG=false; public static int drawLogFrame=0;
    public static boolean SAVELOG = System.getenv("SAVELOG")!=null;   // loga opens de arquivo (save)
    static void dlog(String s){ if(DRAWLOG) System.out.println("[D"+drawLogFrame+"] "+s); }
    static String colStr(int fore){ return fore<0 ? ("RGB555#"+Integer.toHexString(fore&0xffff)) : ("idx"+(fore&0xff)); }

    // ---- input por tecla ASCII (nofun InputDriver): o keypad do celular mapeia p/ os botoes logicos.
    // vTestKey(ascii): 2=Up 8=Down 4=Left 6=Right 5/*=Fire #=Fire2 0/217(SEOption)=Select;
    // diagonais 1=Up+Left 3=Up+Right 7=Down+Left 9=Down+Right. Retorna 1 se pressionado.
    // CODIGOS DE TECLA DO APARELHO. O vmgp.h diz apenas que acima de 127 sao "implementation
    // defined"; a tabela oficial esta em MophunSDK_2.5/pip/include/vmgpsonyericsson.h e e esta:
    //   200 VOLUME_UP  201 VOLUME_DOWN  202 YES  203 NO  204 CLEAR  205 LEFT  206 RIGHT
    //   207 FLIP_CLOSE 208 FLIP_OPEN    209 PHF  210 UP  211 DOWN   212 LEFT_UP 213 RIGHT_DOWN
    //   214 NAV_SELECT 215 VOICENOTE    216 SELECT 217 OPTION
    // 205/206/210/211 sao o JOYSTICK do aparelho, ou seja o proprio d-pad -- derivamos dos bits.
    // 203 (NO/vermelho) e 204 (CLEAR/tecla C) NAO tem bit equivalente: sao acoes proprias, com botao
    // proprio no teclado da tela, e chegam aqui pelo keypadPressed abaixo.
    static final int SE_YES=202, SE_NO=203, SE_CLEAR=204, SE_LEFT=205, SE_RIGHT=206,
                     SE_UP=210, SE_DOWN=211, SE_SELECT=216;
    static final int SEOPTION = 217;
    static final int SEFT_SOFTKEY = 214;
    static final int ASCII_ESC = 27;       // alguns jogos (Brain Vita, Heli Attack 2) saem pelo ESC   // softkey esquerda ("Select") do Sony Ericsson
    int testKey(int key){
        if(scr.keypadPressed(key)) return 1;              // tecla numerica literal pressionada (jogos tipo Bomb Jack)
        int b = scr.getButtons();
        final int U=Screen.UP,D=Screen.DOWN,L=Screen.LEFT,R=Screen.RIGHT,F=Screen.FIRE,S=Screen.SELECT,F2=Screen.FIRE2;
        switch(key){
            case '1': return ((b&(U|L))==(U|L))?1:0;
            case '2': return (b&U)!=0?1:0;
            case '3': return ((b&(U|R))==(U|R))?1:0;
            case '4': return (b&L)!=0?1:0;
            case '5': case '*': return (b&F)!=0?1:0;
            case '6': return (b&R)!=0?1:0;
            case '7': return ((b&(D|L))==(D|L))?1:0;
            case '8': return (b&D)!=0?1:0;
            case '9': return ((b&(D|R))==(D|R))?1:0;
            case '0': case SEOPTION: return (b&S)!=0?1:0;
            // 214 = SOFTKEY ESQUERDA do Sony Ericsson (a que o proprio jogo rotula "Select" no
            // rodape). O vmgp.h diz que codigos >127 sao "implementation defined" e nem o nofun os
            // mapeia; descoberto por experimento. Devolver sempre 0 travava quem confirma por ela:
            // Kely Slater (BUG-083) e Hugo Evil Mirror nunca saiam do menu e o Something Fishy
            // (BUG-116) nunca aceitava o OK. Vale como o botao de confirmar, igual ao FIRE.
            case SEFT_SOFTKEY: return (b&F)!=0?1:0;
            case '#': return (b&F2)!=0?1:0;
            // JOYSTICK do aparelho = d-pad. O SAAF (BUG-110) le a direcao SO por estes codigos, entao
            // sem eles o jogo fica com controle morto; o Something Fishy le os dois caminhos.
            case SE_LEFT:  return (b&L)!=0?1:0;
            case SE_RIGHT: return (b&R)!=0?1:0;
            case SE_UP:    return (b&U)!=0?1:0;
            case SE_DOWN:  return (b&D)!=0?1:0;
            // tecla verde (atender) e a de "select" dedicada valem como confirmar, igual ao NAV_SELECT
            case SE_YES: case SE_SELECT: return (b&F)!=0?1:0;
            // o ESC das versoes com teclado e a MESMA acao do 203 (voltar/sair) -- mesmo botao
            case ASCII_ESC: return scr.keypadPressed(SE_NO)?1:0;
            default: return 0;
        }
    }
    // vScanKeys(): ASCII da tecla atual (reverso do testKey; nofun InputDriver.KeyScan)
    int scanKeys(){
        for(int a='0'; a<='9'; a++) if(scr.keypadPressed(a)) return a;   // tecla numerica literal tem prioridade
        if(scr.keypadPressed('*')) return '*';
        if(scr.keypadPressed('#')) return '#';
        // teclas de APARELHO antes do d-pad: quem espera "voltar" (Bomb Jack, Nostalgia Ball, SAAF) ou
        // "apagar" (4-in-1, o pause do jogo Jake) le isso pelo vScanKeys, nao por bit de botao.
        if(scr.keypadPressed(SE_NO))    return SE_NO;
        if(scr.keypadPressed(SE_CLEAR)) return SE_CLEAR;
        int b = scr.getButtons();
        final int U=Screen.UP,D=Screen.DOWN,L=Screen.LEFT,R=Screen.RIGHT,F=Screen.FIRE,S=Screen.SELECT,F2=Screen.FIRE2;
        if((b&U)!=0){ if((b&L)!=0) return '1'; if((b&R)!=0) return '3'; return '2'; }
        if((b&D)!=0){ if((b&L)!=0) return '7'; if((b&R)!=0) return '9'; return '8'; }
        if((b&L)!=0) return '4';
        if((b&R)!=0) return '6';
        if((b&F)!=0) return '5';
        if((b&F2)!=0) return '*';
        if((b&S)!=0) return SEOPTION;
        return 0;
    }

    // resolve uma "cor" mophun p/ ARGB: bit31 setado = RGB555 direto; senao = indice de paleta
    int getColor(int c){
        if(c<0) return Screen.rgb555ToArgb(c & 0xffff);
        return scr.palette[c & 0xff];
    }
    void px(int x,int y,int argb){
        scr.put(x,y,argb);
    }
    // vFillRect(x0,y0,x1,y1) - cantos; cor = foreColor.
    // NORMALIZA cantos invertidos: jogos pseudo-3D (XxX/V-Rally) desenham as BANDAS do terreno via
    // fillRects com cantos em ordem DESCENDENTE (x0>x1 e/ou y0>y1). O nofun rejeita (`if x0>=x1||y0>=y1`)
    // -> as faixas somem; o emulador OFICIAL normaliza e desenha. Normalizamos p/ reproduzir o terreno
    // pseudo-3D com faixas claras/escuras seguindo a perspectiva da pista.
    void fillRect(int x0,int y0,int x1,int y1){
        if(x0>x1){ int t=x0; x0=x1; x1=t; }
        if(y0>y1){ int t=y0; y0=y1; y1=t; }
        // vFillRect e INCLUSIVO: x1/y1 sao a borda DIREITA/INFERIOR (SDK: "x1=Right position"), NAO exclusiva.
        // Uma coluna/linha de 1 pixel (x0==x1) desenha 1 pixel — ex.: o preenchimento da barra de vida do
        // Honey Cave 1 (vFillRect(x,74,x,77) por coluna). O nofun usava exclusivo (xx<x1) e pulava x0==x1
        // -> a barra ficava VAZIA. Inclusivo tb corrige fills de tela que perdiam 1px na borda dir/inf.
        int c=getColor(foreColor);
        dlog("fillRect ("+x0+","+y0+")-("+x1+","+y1+") fore="+colStr(foreColor)+" clip=["+scr.clipX0+","+scr.clipY0+"-"+scr.clipX1+","+scr.clipY1+"] v150="+version150);
        // vFillRect RESPEITA o clip window, em qualquer versao de API. vmgp.h e explicito: na doc do
        // vSetClipWindow, vFillRect esta na lista "functions affected by the clip window", e logo
        // acima ela diz "Note that vClearScreen is not affected by the clip window" -- o contraste e
        // proposital. Antes haviamos aberto uma excecao p/ API <1.50 (fill ignorava o clip), como
        // paliativo p/ o chao do XxX/V-Rally. Isso APAGAVA o HUD do Graveyard Adventure: ele faz
        // setClipWindow(0,0,127,95) e vFillRect(0,0,128,160) a cada frame contando com o clip p/
        // limpar SO a area de jogo -- sem clip, o fill varria tambem o logo/caveira do HUD (pintados
        // uma unica vez no inicio da fase), deixando a barra inferior preta.
        for(int yy=y0;yy<=y1;yy++) for(int xx=x0;xx<=x1;xx++) px(xx,yy,c);
    }
    // vDrawLine(x0,y0,x1,y1) - Bresenham com foreColor (nofun implementa; era no-op aqui)
    void drawLine(int x0,int y0,int x1,int y1){
        int c=getColor(foreColor);
        dlog("drawLine ("+x0+","+y0+")-("+x1+","+y1+") fore="+colStr(foreColor));
        int dx=Math.abs(x1-x0), dy=-Math.abs(y1-y0);
        int sx=x0<x1?1:-1, sy=y0<y1?1:-1, err=dx+dy;
        for(int guard=0; guard<4096; guard++){
            px(x0,y0,c);
            if(x0==x1 && y0==y1) break;
            int e2=2*err;
            if(e2>=dy){ err+=dy; x0+=sx; }
            if(e2<=dx){ err+=dx; y0+=sy; }
        }
    }
    // vDrawFlatPolygon(triPtr): Triangle{x0,y0,x1,y1,x2,y2}=s16 -> triangulo 2D preenchido (foreColor)
    void fillFlatPoly(int p){
        int x0=(short)vm.read16(p),   y0=(short)vm.read16(p+2);
        int x1=(short)vm.read16(p+4), y1=(short)vm.read16(p+6);
        int x2=(short)vm.read16(p+8), y2=(short)vm.read16(p+10);
        int c=getColor(foreColor);
        dlog("flatPoly ("+x0+","+y0+")("+x1+","+y1+")("+x2+","+y2+") fore="+colStr(foreColor)+" tm="+transferMode);
        int minX=Math.max(scr.clipX0, Math.min(x0,Math.min(x1,x2)));
        int maxX=Math.min(scr.clipX1, Math.max(x0,Math.max(x1,x2))+1);
        int minY=Math.max(scr.clipY0, Math.min(y0,Math.min(y1,y2)));
        int maxY=Math.min(scr.clipY1, Math.max(y0,Math.max(y1,y2))+1);
        int area=(x1-x0)*(y2-y0)-(x2-x0)*(y1-y0);
        if(area==0) return;
        for(int py=minY;py<maxY;py++) for(int px=minX;px<maxX;px++){
            int w0=(x1-px)*(y2-py)-(x2-px)*(y1-py);
            int w1=(x2-px)*(y0-py)-(x0-px)*(y2-py);
            int w2=area-w0-w1;
            boolean in = area>0 ? (w0>=0&&w1>=0&&w2>=0) : (w0<=0&&w1<=0&&w2<=0);
            if(in) scr.put(px,py,c);
        }
    }

    // vSetPalette(colorsPtr, startIndex, count) - array de u16 RGB555
    void setPalette(int ptr,int start,int count){
        if(count>256) count=256;
        for(int i=0;i<count;i++) scr.setPaletteRgb555((start+i)&0xff, vm.read16(ptr+i*2));
    }
    // vatoi(strPtr, endPtr): ASCII -> int (pula espacos, sinal, digitos); endPtr(opcional) recebe o ptr apos o numero
    int vatoi(int strPtr,int endPtr){
        int p=strPtr, ch;
        while((ch=vm.read8(p)&0xff)==' '||ch=='\t'||ch=='\n'||ch=='\r') p++;
        int sign=1; ch=vm.read8(p)&0xff; if(ch=='+'||ch=='-'){ if(ch=='-') sign=-1; p++; }
        long v=0; while((ch=vm.read8(p)&0xff)>='0'&&ch<='9'){ v=v*10+(ch-'0'); p++; }
        if(endPtr!=0) vm.write32(endPtr, p);
        return (int)(sign*v);
    }
    // vFindRGBIndex(rgb555): indice da paleta de tela mais proximo (menor distancia RGB). nofun Graphics.cs
    int findRGBIndex(int rgb555){
        int tr=Math.round(((rgb555>>10)&31)*255f/31), tg=Math.round(((rgb555>>5)&31)*255f/31), tb=Math.round((rgb555&31)*255f/31);
        int best=0; long bestD=Long.MAX_VALUE;
        for(int i=0;i<256;i++){ int c=scr.palette[i]; int r=(c>>16)&0xff,g=(c>>8)&0xff,b=c&0xff;
            long d=(long)(r-tr)*(r-tr)+(long)(g-tg)*(g-tg)+(long)(b-tb)*(b-tb);
            if(d<bestD){ bestD=d; best=i; } }
        return best;
    }

    // NativeSprite (10B): palOff,format,centerX(s16),centerY(s16),w(u16),h(u16); pixels em +10 (format 7=RGB332 8bpp)
    // bits por pixel de cada TextureFormat (nofun TextureUtil.GetPixelSizeInBits)
    static int bppOf(int fmt){
        switch(fmt){
            case 0: case 3: return 1;                 // Monochrome, Palette2
            case 1: case 4: return 2;                 // Gray4, Palette4
            case 2: case 5: return 4;                 // Gray16, Palette16
            case 6: case 7: return 8;                 // Palette256, RGB332
            case 8: case 13: case 14: return 16;      // RGB565, ARGB4444, ARGB1555
            case 9: return 16;                        // RGB555 (armazenado em 16b)
            default: return 8;
        }
    }
    // NativeSprite (10B): palOff,format,centerX(s16),centerY(s16),w(u16),h(u16); pixels @+10.
    // Formatos com <=8bpp de paleta/RGB332: pixel=indice -> paleta[indice+palOff]; indice 0 = transparente.
    int dobjBudget=0;
    void drawObject(int x,int y,int sp){
        if(sp==0) return;
        int palOff=vm.read8(sp), format=vm.read8(sp+1);
        int cx=(short)vm.read16(sp+2), cy=(short)vm.read16(sp+4);
        // SPRITE.centerx/centery (SDK: s16 @+2/@+4) sao o "ponto de ancoragem" — sempre da ordem do
        // tamanho do sprite. Valores absurdos (|c| > 1024) jogam o desenho todo p/ fora da tela. O ceu
        // da FASE 2 do Joe's Treasure Quest e uma faixa 8x266 repetida 22x cujo header vem com
        // centery=-12416 -> as 22 chamadas desenhavam 0 pixels -> ceu sumia. Protecao (causa de origem aberta).
        if(cx>1024||cx<-1024) cx=0;
        if(cy>1024||cy<-1024) cy=0;
        int w=vm.read16(sp+6), h=vm.read16(sp+8);
        if(w==0||h==0||w>2048||h>2048) return;
        if(DRAWLOG && dobjBudget<400){ dobjBudget++; dlog("drawObject @("+x+","+y+") fmt="+format+" "+w+"x"+h+" palOff="+palOff+" tm="+transferMode); }
        int pix=sp+10, bpp=bppOf(format), mask=(1<<bpp)-1;
        int ox=x-cx, oy=y-cy;
        boolean flipX=(transferMode&2)!=0, flipY=(transferMode&4)!=0;   // TransferMode.FlipX=2/FlipY=4
        // transparencia (indice 0 = transparente) SO no modo Transparent (bit0); Block(0)=opaco -> fundos aparecem
        // fmt7Transp: so o Honey Cave 2 (ver detectaFmt7Transp) -- ele desenha tudo com tm=0 e espera
        // recorte. Para os demais jogos a regra continua sendo o transfer mode.
        boolean transp=(transferMode&1)!=0 || spriteSlotDraw || (fmt7Transp && format==7);
        for(int j=0;j<h;j++){
            int dy=oy+j; if(dy<scr.clipY0||dy>=scr.clipY1) continue;
            int sj = flipY ? (h-1-j) : j;
            for(int i=0;i<w;i++){
                int dx=ox+i; if(dx<scr.clipX0||dx>=scr.clipX1) continue;
                int si = flipX ? (w-1-i) : i;
                int pi=sj*w+si, argb;
                if(bpp<=8){
                    int bitPos=pi*bpp, byteIdx=pix+(bitPos>>3);
                    // sub-byte (Palette2/4/16 = 1/2/4bpp) e LSB-first no mophun: 1o pixel no nibble/bit BAIXO
                    // (nofun DataConvertor.PaletteToARGB8888). MSB-first embaralhava texto/HUD de jogos 4bpp.
                    int idx=(vm.read8(byteIdx) >> (bitPos&7)) & mask;
                    // idx0 = cor-chave TRANSPARENTE: no modo Transparent (tm&1) p/ QUALQUER formato, E
                    // SEMPRE para fmt7 (RGB332). Motivo (pista do repo GitHub jamisson2006/wolphun): os
                    // sprites do HC2 pos-transicao de fase ficam com transferMode=0 mas continuam
                    // "PNG transparentes" no aparelho real -> depender de tm dava "quadrado preto" no ceu,
                    // inclusive apos sair/re-entrar (resume) e em save states. Fmt7-sempre-transparente e
                    // ROBUSTO (nao depende do estado da paleta). Testado: NAO regride o logo synergenix nem
                    // o carro/pneu do menu do Rally (esses nao usam idx0 fmt7 sobre fundo claro).
                    if(transp && idx==0) continue;
                    if(format==1||format==2||format==0){                        // grayscale/mono
                        int g=Math.round(idx*255f/mask); argb=0xff000000|(g<<16)|(g<<8)|g;
                    } else if(format==7){
                        argb=scr.rgb332[idx&0xff];                               // RGB332 = cor DIRETA; palOff NAO se aplica (nao e palette format)
                    } else {
                        // Palette256 (fmt6): o indice JA e 0-255 (paleta completa) -> palOff NAO se aplica
                        // (senao desloca +1: V-Rally 2 seta palette[197]=cor do carro, corpo=idx 197, palOff=1
                        //  -> lia palette[198] -> carros psicodelicos). Palette16 (fmt5): palOff = banco de 16.
                        int po = (format==6) ? 0 : palOff;
                        argb=scr.palette[(idx+po)&0xff];                         // formatos indexados: paleta mutavel
                    }
                } else {                                                        // 16bpp direto
                    int v=vm.read16(pix+pi*2)&0xffff;
                    if(transp && v==0) continue;
                    argb=(format==8)? rgb565(v) : Screen.rgb555ToArgb(v);
                }
                scr.put(dx,dy,argb);
            }
        }
    }
    // vSetActiveFont(pFont): le a struct VMGPFONT {u32 fontdata, u32 chartbl, u8 bpp,w,h,palindex}
    // e a torna a fonte ativa do vPrint. Devolve o ponteiro da fonte anterior (0 se nenhuma).
    // vSetActiveFont(VMGPFONT*) guarda o PONTEIRO, nao uma copia da struct: o jogo pode reescrever os
    // campos entre um vPrint e o outro sem chamar vSetActiveFont de novo (ver fontRefresh()).
    // Desenha os slots do motor de sprites com a cor 0 como chave mesmo em MODE_BLOCK.
    void drawSpriteSlots(){
        if(slotSprite==null) return;
        boolean prev=spriteSlotDraw; spriteSlotDraw=true;
        try { for(int i=0;i<spriteCount;i++) if(slotSprite[i]!=0) drawObject(slotX[i],slotY[i],slotSprite[i]); }
        finally { spriteSlotDraw=prev; }
    }
    int setActiveFont(int ptr){
        int prev = aFontPtr;
        aFontPtr = ptr;
        if(ptr==0){ aFontData=aFontChartbl=aFontBpp=aFontW=aFontH=aFontPal=0; return prev; }
        fontRefresh();
        return prev;
    }
    // vPrint(mode,x,y,str): desenha texto com a fonte bitmap ATIVA (vSetActiveFont). Cada caractere
    // ocupa width x height pixels; glyph = chartbl[ch] (0xff = ausente). Bits LSB-first (bit menos
    // significativo = pixel mais a esquerda), linhas contiguas, cada caractere alinhado a byte.
    // Fonte monocromatica (bpp=1): bit 1 = foreColor. 4-cores (bpp=2): indice 0 = transparente,
    // 1-3 = palette[palindex+indice]. Largura FIXA (por isso a fonte do sistema proporcional embaralhava).
    // Rele a VMGPFONT apontada por aFontPtr. O Word Panic monta UMA struct de fonte e so troca o
    // campo palindex antes de cada vPrint (mesmos glifos, paleta diferente p/ o rotulo e p/ os
    // digitos do placar); guardar uma copia no vSetActiveFont congelava a paleta da chamada anterior.
    void fontRefresh(){
        if(aFontPtr==0) return;
        aFontData    = vm.read32(aFontPtr+0);
        aFontChartbl = vm.read32(aFontPtr+4);
        aFontBpp     = vm.read8(aFontPtr+8);
        aFontW       = vm.read8(aFontPtr+9);
        aFontH       = vm.read8(aFontPtr+10);
        aFontPal     = vm.read8(aFontPtr+11) & 0xff;
    }
    void vPrint(int mode, int x, int y, String str){
        fontRefresh();                                             // a struct pode ter mudado desde o vSetActiveFont
        if(aFontPtr==0 || aFontW<=0 || aFontH<=0){                 // sem fonte de usuario: cai na do sistema
            scr.drawText(x, y, str, getColor(foreColor)); return;
        }
        if(str==null || str.isEmpty()) return;
        int bpp=aFontBpp<=0?1:aFontBpp, mask=(1<<bpp)-1;
        int glyphBytes = (aFontW*aFontH*bpp + 7) >> 3;             // cada caractere alinhado a byte
        int fg = getColor(foreColor);
        // `mode` e um TransferMode (vmgp.h XferModes): MODE_BLOCK(0) = "Block copy mode, NO transparency",
        // MODE_TRANS(1) = indice 0 transparente. Ignorar isso quebra quem usa vPrint como se fosse um
        // "printf opaco": o Graveyard Adventure reimprime TIME/AIR por cima do valor antigo a cada frame,
        // contando com o modo bloco p/ apagar o anterior -- em modo transparente os digitos se somavam
        // ate virar um bloco branco solido. Em bloco, o pixel 0 recebe o backColor (vSetBackColor).
        boolean blockMode = (mode & 1)==0;
        // Em bloco o indice 0 vira cor solida. Numa fonte MONOCROMATICA (bpp=1) nao ha paleta e a unica
        // fonte de cor possivel e o backColor (e assim que o Graveyard Adventure apaga o TIME/AIR antigo).
        // Numa fonte INDEXADA (bpp>=2) o indice 0 e uma entrada de paleta como qualquer outra: o Word
        // Panic conta com isso p/ repintar o azul do interior da caixa do placar antes de escrever o
        // digito -- usar o backColor (preto) enchia as caixas de preto.
        int bg = (aFontBpp>1) ? scr.palette[aFontPal & 0xff] : getColor(backColor);
        int penX = x;
        for(int k=0;k<str.length();k++){
            int ch = str.charAt(k) & 0xff;
            int gi = vm.read8(aFontChartbl + ch);
            if(gi!=0xff){                                          // 0xff = caractere ausente (so avanca)
                int base = aFontData + gi*glyphBytes;
                for(int row=0;row<aFontH;row++){
                    int dy=y+row; if(dy<scr.clipY0||dy>=scr.clipY1) continue;
                    for(int col=0;col<aFontW;col++){
                        int dx=penX+col; if(dx<scr.clipX0||dx>=scr.clipX1) continue;
                        int bitPos=(row*aFontW+col)*bpp;
                        int val=(vm.read8(base+(bitPos>>3)) >> (bitPos&7)) & mask;
                        int argb;
                        if(val==0){ if(!blockMode) continue; argb=bg; }   // transparente, ou fundo em bloco
                        else argb = (bpp==1) ? fg : scr.palette[(aFontPal+val)&0xff];
                        scr.put(dx,dy,argb);
                    }
                }
            }
            penX += aFontW;                                        // largura FIXA
        }
    }

    // vDrawTile(data, format, x, y): desenha UM tile 8x8 em (x,y). format: bits0-2=formato de pixel
    // (VCAPS_IND2..RGB332), bit3=transparente, bits8-15=offset de paleta. Usado como MOTOR DE CHAO/PISTA
    // (ex.: Rally Pro Contest desenha o terreno 3D como grade de tiles 8x8 -> 1144 tiles/frame).
    void drawTile(int dataPtr, int format, int x, int y){
        int pixFmt = format & 0x7;                      // bits 0-2 = formato de pixel
        boolean transp = (format & 0x8)!=0;             // bit 3 = transparente (indice 0 = chave)
        int palOff = (format>>8) & 0xff;                // bits 8-15 = offset de paleta
        int bpp = bppOf(pixFmt), mask=(1<<bpp)-1;
        for(int j=0;j<8;j++){
            int dy=y+j; if(dy<scr.clipY0||dy>=scr.clipY1) continue;
            for(int i=0;i<8;i++){
                int dx=x+i; if(dx<scr.clipX0||dx>=scr.clipX1) continue;
                int argb;
                if(bpp<=8){
                    int bitPos=(j*8+i)*bpp;
                    int idx=(vm.read8(dataPtr+(bitPos>>3)) >> (bitPos&7)) & mask;   // LSB-first (igual drawObject)
                    if((pixFmt==7 || transp) && idx==0) continue;
                    argb = (pixFmt==7) ? scr.rgb332[idx&0xff] : scr.palette[(idx+palOff)&0xff];
                } else {                                                            // 16bpp direto
                    int v=vm.read16(dataPtr+(j*8+i)*2)&0xffff;
                    if(transp && v==0) continue;
                    argb = (pixFmt==8)? rgb565(v) : Screen.rgb555ToArgb(v);
                }
                scr.put(dx,dy,argb);
            }
        }
    }

    // colisao AABB entre dois retangulos (top-left + w/h)
    static boolean aabb(int ax,int ay,int aw,int ah, int bx,int by,int bw,int bh){
        return ax < bx+bw && ax+aw > bx && ay < by+bh && ay+ah > by;
    }
    // vSpriteBoxCollision(boxPtr, from, to): VMGPRect{x,y,w,h}@8B vs sprites [from..to]; retorna indice ou -1
    int spriteBoxCollision(int boxPtr, int from, int to){
        if(slotSprite==null) return -1;
        int bx=(short)vm.read16(boxPtr), by=(short)vm.read16(boxPtr+2), bw=vm.read16(boxPtr+4), bh=vm.read16(boxPtr+6);
        for(int i=Math.max(0,from); i<=Math.min(to, spriteCount-1); i++){
            int sp=slotSprite[i]; if(sp==0) continue;
            int sw=vm.read16(sp+6), sh=vm.read16(sp+8);
            if(aabb(bx,by,bw,bh, slotX[i],slotY[i],sw,sh)) return i;
        }
        return -1;
    }
    // vSpriteCollision(slotCheck, from, to): sprite slotCheck vs sprites [from..to]
    int spriteCollision(int check, int from, int to){
        if(slotSprite==null || check<0 || check>=spriteCount || slotSprite[check]==0) return -1;
        int cp=slotSprite[check]; int cw=vm.read16(cp+6), ch=vm.read16(cp+8);
        int cx=slotX[check], cy=slotY[check];
        for(int i=Math.max(0,from); i<=Math.min(to, spriteCount-1); i++){
            int sp=slotSprite[i]; if(sp==0) continue;
            int sw=vm.read16(sp+6), sh=vm.read16(sp+8);
            if(aabb(cx,cy,cw,ch, slotX[i],slotY[i],sw,sh)) return i;
        }
        return -1;
    }
    static int rgb565(int v){ int r=(v>>11)&31,g=(v>>5)&63,b=v&31;
        return 0xff000000|(Math.round(r*255f/31)<<16)|(Math.round(g*255f/63)<<8)|Math.round(b*255f/31); }

    // NativeMapHeader(24B): flag,format,mapW,mapH,animSpd,animCnt,animActive,pad, s16 xPan,yPan,xPos,yPos, ptr mapData@16, ptr tileData@20
    void drawMap(){
        mapSync();
        if(!mhValid) return;
        int flag=mhFlag;
        int mapW=mhW, mapH=mhH;
        int animSpeed=mhAnim;
        int xPan=mhXPan, yPan=mhYPan;
        // xPos/yPos: ler com SINAL. O vmgp.h declara MAP_HEADER.x/y como uint16_t, e eu cheguei a
        // normalizar por (valor & 0xffff) % tamanhoDoMapa por causa do Sweet Dreams -- ERRADO, e
        // quebrou o City Auto: ele faz vMapSetXY(-20, ...) num mapa 48x48 (384px) e poe o clip em
        // x[20..129], ou seja usa o -20 como deslocamento p/ alinhar a coluna 0 do mapa com a borda
        // da area de jogo. Com o wrap (65516 % 384 = 236) o DESENHO ia parar noutro pedaco do mapa
        // enquanto a COLISAO (o jogo le o mapa por vMapGetTile) continuava certa -> o caminhao batia
        // em parede invisivel e passava por cima de casas. Regressao relatada pelo usuario.
        int xPos=(short)mhXPos, yPos=(short)mhYPos;
        int mapData=mhMapData, tileData=mhTileData;
        // ponteiros invalidos (fora da memoria da VM) -> nao desenha lixo (ex: menu do HC1 com mapData bogus)
        if(mapData<vm.base || mapData>=vm.base+vm.mem.length || tileData<vm.base || tileData>=vm.base+vm.mem.length) return;
        // Plano de atributos: MAP_USERATTRIBUTE(0x2) OU MAP_AUTOANIM(0x8) -- a animacao precisa de um
        // byte por tile p/ saber quais animam, entao tambem intercala. Nao basta MAP_TRANSPARENT(0x1):
        // um mapa so transparente tem passo 1. Medido no Chang's Orbhunt (flag=0x8, so AUTOANIM): com
        // passo 1 caiam 59 das 130 celulas em "tile 0 = nao desenhar" (na verdade eram os bytes de
        // atributo), o fundo de pergaminho do menu de selecao virava xadrez furado e o texto do menu
        // anterior vazava pelos buracos. Com passo 2: ZERO celulas tile-0.
        // REGRA DA VERSAO PUBLICADA: QUALQUER flag != 0 implica plano de atributos intercalado.
        // Eu havia estreitado isso p/ (flag & 0xa) supondo que um mapa so-MAP_TRANSPARENT tivesse
        // passo 1 -- premissa FALSA: a camada de HUD do Honey Cave 2 tem flag=0x1 e e intercalada.
        // Com passo 1 o painel do placar virava farrapos (lia byte de atributo como tile).
        boolean hasAttr = flag!=0; int cell = hasAttr?2:1;
        final int TW=8, TH=8;
        // O campo `format` do NativeMapHeader vale o mesmo que o de um sprite: o tile NAO e sempre 8bpp.
        // Final Hit (T300) declara format=5 (Palette16, 4bpp): lendo 1 byte por pixel o ceu e a cidade
        // saiam como RUIDO RGB (dobro de bytes por tile + indice de paleta errado). Com bpp do formato,
        // jogos 8bpp (HC2/Gold Rush, fmt 6/7) ficam byte-a-byte identicos.
        int fmt = mhFmt;
        int bpp = bppOf(fmt), mask=(1<<bpp)-1;
        int tileBytes = (TW*TH*bpp+7)/8;
        int[] pal = (fmt==7) ? scr.rgb332 : scr.palette;   // format 7 = RGB332 direto (paleta fixa); HC2 depende disso
        // Transparencia de tile e propriedade POR TILE (bit no byte de atributo), HABILITADA pela flag
        // MAP_TRANSPARENT(0x1) da camada: o vmgp.h diz "even if a tile has the transparency bit set its
        // drawn as solid if this layer flag is not set". Tratar todo texel indice 0 como transparente so
        // porque a camada tem a flag furava fundos solidos: no Shooting Star o tile da parede (usado na
        // tela toda) tem 14 dos 64 texels no indice 0 -> esses pixels nunca eram repintados, o frame
        // anterior vazava e os alvos deixavam rastro pontilhado vertical.
        // Sem camada de atributos nao existe bit por tile -> mantem o comportamento antigo.
        boolean layerTransp = (flag & 0x1)!=0;             // MAP_TRANSPARENT
        // rolagem: xPos/yPos = scroll do mapa; xPan/yPan = posicao na tela (% e / truncados, igual ao nofun)
        int startDrawX = xPan - (xPos % TW);
        int startDrawY = yPan - (yPos % TH);
        int tileStartX = xPos / TW, tileStartY = yPos / TH;
        int tilesAcross = (scr.w - startDrawX + TW-1)/TW + 1;
        int tilesDown   = (scr.h - startDrawY + TH-1)/TH + 1;
        for(int ry=0; ry<tilesDown; ry++){
            int my=tileStartY+ry; if(my<0||my>=mapH) continue;
            for(int rx=0; rx<tilesAcross; rx++){
                int mx=tileStartX+rx; if(mx<0||mx>=mapW) continue;
                int idx=(my*mapW+mx)*cell;
                int t=vm.read8(mapData+idx)&0xff;
                if(t==0) continue; t-=1;                        // base-1; 0=vazio
                int attr = hasAttr? vm.read8(mapData+idx+1)&0xff : 0;
                // O nibble ALTO do atributo significa coisas diferentes conforme o FORMATO do tile:
                //  - Palette2/4/16 (fmt 3/4/5): e o BANCO DE PALETA (mesmo papel do palOff do sprite).
                //    O indice do pixel so vai de 0..15, entao sem o banco tudo cai nas 16 primeiras
                //    cores. Hugo Evil Mirror: as plataformas tem attr 65/81 (=64|1, 80|1) e sem o
                //    banco saiam como riscos branco/azul em vez de madeira marrom.
                //  - RGB332 (fmt 7) e cor DIRETA, nao ha paleta p/ indexar -> ali os bits 6/7 sao
                //    os flags de ANIMACAO (Chang's 128, Diamond Hunter/Gold Rush 129, Hopper 141).
                // Medido no acervo: todo mapa indexado que usa o nibble alto o usa em multiplos de 16
                // e NUNCA liga o bit 7; todo mapa fmt=7 que usa o nibble alto liga o bit 7.
                boolean bankFmt = (fmt==3||fmt==4||fmt==5);
                int palBank = bankFmt ? (attr & 0xF0) : 0;
                if(!bankFmt){
                    if((attr&0x40)!=0) t += (frameAnimate%2);        // anima 2 frames
                    else if((attr&0x80)!=0) t += (frameAnimate%4);   // anima 4 frames
                }
                int tp=tileData + t*tileBytes;
                // O bit 0 do atributo do TILE vale sozinho, mesmo sem MAP_TRANSPARENT na camada.
                // vmgp.h ("Differences since API version 1.30"): "Transparent flag for a layer is now
                // actually used... even if a tile has the transparency bit set it's drawn as solid if
                // this layer flag is not set" -- e logo abaixo: "The above changes do not take effect
                // unless the API version is explicitly set in the game" (-mversion, opt-in). Ou seja,
                // o PADRAO e o comportamento antigo: a flag da camada NAO veta o bit do tile.
                // Medido em todo o acervo de teste: so 3 mapas tem bit0 sem MAP_TRANSPARENT --
                // Evac 610 (396/396, cunhas pretas nos cantos da parede), Graveyard (56/1248) e
                // Hopper (352/1600, sem diferenca visivel). Chang's, Diamond Hunter, Gold Rush,
                // HoneyCave 1/2, Bone Idol, Sweet Dreams e Shooting Star tem ZERO -> intocados.
                boolean tileTransp = (layerTransp && (!hasAttr || (attr & 0x1)!=0)) || (hasAttr && (attr & 0x1)!=0);
                int sx=startDrawX+rx*TW, sy=startDrawY+ry*TH;
                for(int j=0;j<TH;j++){ int dy=sy+j; if(dy<scr.clipY0||dy>=scr.clipY1) continue;
                    for(int i=0;i<TW;i++){ int dx=sx+i; if(dx<scr.clipX0||dx>=scr.clipX1) continue;
                        int v, argb;
                        if(bpp<=8){                                   // sub-byte e LSB-first, igual ao drawObject
                            int bitPos=(j*TW+i)*bpp;
                            v=(vm.read8(tp+(bitPos>>3)) >> (bitPos&7)) & mask;
                            if(fmt==0||fmt==1||fmt==2){ int g=Math.round(v*255f/mask); argb=0xff000000|(g<<16)|(g<<8)|g; }
                            else argb=pal[(v + palBank) & (pal.length-1)];
                        } else {                                       // 16bpp direto
                            v=vm.read16(tp+(j*TW+i)*2)&0xffff;
                            argb=(fmt==8)? rgb565(v) : Screen.rgb555ToArgb(v);
                        }
                        if(tileTransp && (bpp<=8 ? v==0 : (argb&0xffffff)==0)) continue;   // cor-chave = indice 0
                        scr.put(dx,dy,argb);
                    }
                }
            }
        }
        if(animSpeed!=0 && (++framePassed>animSpeed)){ frameAnimate=(frameAnimate+1)&3; framePassed=0; }
    }
    int frameAnimate=0, framePassed=0;

    int mapAttr(int x,int y){
        mapSync(); if(!mhValid) return 0;
        if(mhFlag==0) return 0;                             // sem plano de atributos
        if(x<0||y<0||x>=mhW||y>=mhH) return 0;
        return vm.read8(mhMapData+(y*mhW+x)*2+1)&0xff;
    }
    int mapTile(int x,int y){
        mapSync(); if(!mhValid) return 0;
        if(x<0||y<0||x>=mhW||y>=mhH) return 0;
        int cell = (mhFlag!=0)?2:1;   // mesmo passo do drawMap: qualquer flag != 0
        return vm.read8(mhMapData+(y*mhW+x)*cell)&0xff;
    }
    // setters simetricos aos getters: escrevem o tile (base-1) e o atributo no mapData da VM
    void mapSetTile(int x,int y,int tile){
        mapSync(); if(!mhValid) return;
        if(x<0||y<0||x>=mhW||y>=mhH) return;
        int cell = (mhFlag!=0)?2:1;   // mesmo passo do drawMap: qualquer flag != 0
        vm.write8(mhMapData+(y*mhW+x)*cell, tile&0xff);
    }
    void mapSetAttr(int x,int y,int attr){
        mapSync(); if(!mhValid) return;
        if(mhFlag==0) return;                               // sem plano de atributos
        if(x<0||y<0||x>=mhW||y>=mhH) return;
        vm.write8(mhMapData+(y*mhW+x)*2+1, attr&0xff);
    }

    // extent de texto empacotado: bits altos = altura, bits baixos = largura (formato mophun)
    int textExtent(String s){
        int w = scr.textWidth(s), h = scr.fontHeight();
        return (h << 16) | (w & 0xffff);
    }

    // le uma string da memoria da VM: latin1 (byte) ou UTF-16LE (unicode)
    String readStr(int ptr,boolean unicode){
        if(ptr==0) return "";
        StringBuilder sb=new StringBuilder();
        if(unicode){ int c; while((c=vm.read16(ptr))!=0){ sb.append((char)c); ptr+=2; if(sb.length()>4096)break; } }
        else       { int c; while((c=vm.read8(ptr))!=0){ sb.append((char)c);  ptr+=1; if(sb.length()>4096)break; } }
        return sb.toString();
    }

    // vCopyRect(VMGPSTRIDEPTR* dest, *source, u16 width, u16 height): copia um retangulo WxH de graficos 8bpp
    // entre dois locais. STRIDEPTR{ptr@0, xpan@4(u16), ypan@6(u16), stride@8(s16)}; ptr==0 = a TELA em (0,0).
    // Golf Pro Contest 1/2 desenham o MAPA/campo com isto (course 256x160 -> tela); antes era no-op -> chao
    // preto(Golf1)/magenta(Golf2). Tela = RGB332 (Screen.rgb332), buffers do jogo tb; converte byte->ARGB no fb.
    void copyRect(int dS,int sS,int w,int h){
        w&=0xffff; h&=0xffff; if(w<=0||h<=0||w>4096||h>4096) return;
        int dP=vm.read32(dS), dX=(short)vm.read16(dS+4), dY=(short)vm.read16(dS+6), dStr=(short)vm.read16(dS+8);
        int sP=vm.read32(sS), sX=(short)vm.read16(sS+4), sY=(short)vm.read16(sS+6), sStr=(short)vm.read16(sS+8);
        boolean dScr=(dP==0), sScr=(sP==0);
        if(dScr && dStr==0) dStr=scr.w;
        if(sScr && sStr==0) sStr=scr.w;
        for(int y=0;y<h;y++){
            for(int x=0;x<w;x++){
                int b;                                              // pixel 8bpp de origem
                if(sScr){ int sx=sX+x, sy=sY+y; if(sx<0||sx>=scr.w||sy<0||sy>=scr.h) continue; b=argbToRgb332(scr.get(sx,sy)); }
                else    { b=vm.read8(sP + sX + (sY+y)*sStr + x) & 0xff; }
                if(dScr){ int dx=dX+x, dy=dY+y; if(dx<0||dx>=scr.w||dy<0||dy>=scr.h) continue; scr.put(dx,dy,scr.rgb332[b]); }
                else    { vm.write8(dP + dX + (dY+y)*dStr + x, b); }
            }
        }
    }
    // ARGB do framebuffer -> indice RGB332 aproximado (p/ copias screen->system, raro)
    int argbToRgb332(int argb){ int r=(argb>>16)&0xff, g=(argb>>8)&0xff, bl=argb&0xff; return ((r>>5)<<5)|((g>>5)<<2)|(bl>>6); }

    // vitoa/vutoa(val, buf, len, pad): escreve o numero, completa ate 'len' com 'pad' e devolve o
    // ponteiro para o ' ' final (o FIM), nao o inicio -- os jogos encadeiam a partir do retorno.
    int itoa(int val,int buf,boolean signed,int len,int pad){
        String s = signed? Integer.toString(val) : Long.toString(val&0xffffffffL);
        int i=0; for(;i<s.length();i++) vm.write8(buf+i, s.charAt(i));
        for(int k=s.length(); k<len; k++) vm.write8(buf+k, pad);
        int nullPos = Math.max(s.length(), len);
        vm.write8(buf+nullPos, 0);
        return buf+nullPos;
    }

    // ---------------- vSprintf / vSprintfVa (printf estilo C) ----------------
    // vSprintf(buf, fmt, ...): varargs em p2,p3 e depois na PILHA (vm.arg cuida do split).
    // ABI do PIP2 (MophunAsmRef, secao 1.3.1): os quatro primeiros argumentos vao em $p0-$p3;
    // os demais E TAMBEM os argumentos de funcoes stdarg (ellipsis) vao na PILHA.
    // Em vSprintf(buf,fmt,...) os args do ... estao na pilha a partir de $sp+0, nao em $p2/$p3.
    // Antes: Golf Pro Contest imprimia PAR 625608 | HOLE 2 em vez de PAR 4 | HOLE 1.
    int sprintfReg(int buf,int fmt){
        final int base = vm.getReg(Pip2.SP);
        final int[] off = {0};
        return sprintfImpl(buf, fmt, new java.util.function.IntSupplier(){
            public int getAsInt(){ int v = vm.read32(base + off[0]); off[0] += 4; return v; } });
    }
    // vSprintfVa(buf, fmt, ap): ap = ponteiro p/ o va_list (array de u32 na memoria da VM).
    int sprintfVa(int buf,int fmt,final int ap){ final int[] off={0}; return sprintfImpl(buf, fmt, new java.util.function.IntSupplier(){ public int getAsInt(){ int v=vm.read32(ap+off[0]); off[0]+=4; return v; } }); }
    int sprintfImpl(int buf,int fmt,java.util.function.IntSupplier args){
        String s = formatC(readStr(fmt,false), args);
        int i=0; for(; i<s.length() && i<8192; i++) vm.write8(buf+i, s.charAt(i));
        vm.write8(buf+i, 0);
        return buf;                                 // CRITICO: retorna buf (nao 0/null) — o jogo usa como char*
    }
    // formatador printf minimo: %d/%i/%u/%x/%X/%c/%s/%p/%%, com flags(-,0,+,espaco), largura e precisao.
    String formatC(String fmt, java.util.function.IntSupplier args){
        StringBuilder out=new StringBuilder(); int i=0, n=fmt.length();
        while(i<n){
            char c=fmt.charAt(i++);
            if(c!='%'){ out.append(c); continue; }
            if(i<n && fmt.charAt(i)=='%'){ out.append('%'); i++; continue; }
            boolean left=false, zero=false, plus=false, space=false;
            loop: while(i<n){ switch(fmt.charAt(i)){ case '-':left=true;i++;break; case '0':zero=true;i++;break; case '+':plus=true;i++;break; case ' ':space=true;i++;break; default: break loop; } }
            int width=0; while(i<n && Character.isDigit(fmt.charAt(i))) width=width*10+(fmt.charAt(i++)-'0');
            int prec=-1; if(i<n && fmt.charAt(i)=='.'){ i++; prec=0; while(i<n && Character.isDigit(fmt.charAt(i))) prec=prec*10+(fmt.charAt(i++)-'0'); }
            while(i<n && (fmt.charAt(i)=='l'||fmt.charAt(i)=='h'||fmt.charAt(i)=='L')) i++;
            if(i>=n) break;
            char cv=fmt.charAt(i++); String piece;
            switch(cv){
                case 'd': case 'i': { int v=args.getAsInt(); piece=Integer.toString(v); if(v>=0){ if(plus)piece="+"+piece; else if(space)piece=" "+piece; } break; }
                case 'u': piece=Long.toString(args.getAsInt()&0xffffffffL); break;
                case 'x': piece=Integer.toHexString(args.getAsInt()); break;
                case 'X': piece=Integer.toHexString(args.getAsInt()).toUpperCase(); break;
                case 'o': piece=Integer.toOctalString(args.getAsInt()); break;
                case 'c': piece=String.valueOf((char)(args.getAsInt()&0xff)); break;
                case 'p': piece="0x"+Integer.toHexString(args.getAsInt()); break;
                case 's': { int p=args.getAsInt(); piece = (p==0)?"":readStr(p,false); if(prec>=0 && piece.length()>prec) piece=piece.substring(0,prec); break; }
                default: piece="%"+cv; break;
            }
            if(width>piece.length()){
                int pad=width-piece.length();
                if(left){ out.append(piece); for(int k=0;k<pad;k++) out.append(' '); }
                else if(zero && cv!='s' && cv!='c' && piece.length()>0 && (piece.charAt(0)=='-'||piece.charAt(0)=='+'||piece.charAt(0)==' ')){
                    out.append(piece.charAt(0)); for(int k=0;k<pad;k++) out.append('0'); out.append(piece.substring(1));
                } else { char pc=(zero && cv!='s' && cv!='c')?'0':' '; for(int k=0;k<pad;k++) out.append(pc); out.append(piece); }
            } else out.append(piece);
        }
        return out.toString();
    }

    // vGetCaps(queryType, buffer): preenche a struct e retorna 1. Video: {u16 size,flags,width,height}
    // Vendor anunciado no deviceId do System caps. TUDO o que este emulador implementa e a variante
    // SONY ERICSSON do mophun (som por vPlayResource->MIDI, framebuffer RGB332, ...) e o proprio
    // vGetCaps(Sound) ja devolve config=3 (SE) FIXO para qualquer modelo. Anunciar outro vendor no
    // System caps contradizia isso: o Honey Cave 2 le o vendor do deviceId e so toca musica se for 3.
    // Como a escolha de resolucao seleciona junto o MODELO, pedir 176x208 virava "Nokia 7650"
    // (vendor 4) e o jogo ficava MUDO -- parecia bug de resolucao, mas nao e: medido com autoplay,
    // 60 chamadas de vPlayResource com vendor 3 contra 0 com vendor 4, nas DUAS resolucoes. O mesmo
    // vale p/ 176x220 (Tiger, vendor 7). O MODELO continua honesto (CheckTerminal/resolucao nativa);
    // so o vendor passa a acompanhar o que de fato emulamos.
    static final int SE_VENDOR = 3;        // SystemDeviceVendor.SonyEricsson

    int getCaps(int queryType,int buf){
        if(buf==0) return 0;
        // CapsQueryType (nofun): Video=0, Input=1, Sound=2, Communication=3, System=4
        switch(queryType){
            case 0:  // Video (CAPS_VIDEO): VIDEOCAPS {u16 size, u16 flags, u16 width, u16 height} (vmgpcaps.h)
                // flags: os 8 BITS BAIXOS = FORMATO grafico da tela (enum VideoFormats de vmgpvfmt.h);
                // bits altos = VCAPS_3D(0x1000) e VCAPS_ORIENTATION(0x2000).
                // VideoFormats: 0-5 = mono/gray/indexed<=16 cores; 6=IND256; 7=RGB332; 8=RGB565(16bpp)...
                // "requires 256 colors" = formato >= 6. Reportamos **7 (RGB332)** = 256 cores diretas,
                // o framebuffer real deste emulador. NAO setamos VCAPS_3D/ORIENTATION: jogos 2D simplistas
                // (ex.: Black Deal) comparam o flags INTEIRO e rejeitam bits altos (0x3007 > 8 -> "256 colors");
                // Deep Abyss (mascara) e os 3D (VRally2) aceitam 7 igual. Assim TODOS os "256 colors" passam.
                // VCAPS_3D (0x1000): so anunciamos 3D quando o JOGO e 3D (importa vInit3D). Jogos 3D
                // estritos (Anarchy Boxing 3D, Martial Arts 3D) checam flags&VCAPS_3D e abortam com
                // "3D not supported!" se ausente; ja os 2D (Black Deal) comparam o flags inteiro e
                // rejeitam bits altos. Como version150 = importa vInit3D, isso discrimina sem quebrar 2D.
                vm.write16(buf+0, 8);
                vm.write16(buf+2, videoFormat | (version150 ? 0x1000 : 0));
                vm.write16(buf+4, scr.w);
                vm.write16(buf+6, scr.h);
                return 1;
            case 4:  // System: {u16 size,flags, u32 deviceId, u32 vendorFlags}. deviceId=(model<<16)|vendor
                vm.write16(buf+0, 12);
                vm.write16(buf+2, 0x25);                       // Unicode|Vibrate|OpenURL
                vm.write32(buf+4, (deviceModel<<16)|SE_VENDOR);
                vm.write32(buf+8, 0);
                return 1;
            case 2:  // Sound: {u16 size,flags, u32 config}. vendor=3 -> HC2 liga o som (pool[65]=1)
                vm.write16(buf+0, 8);
                vm.write16(buf+2, 0xFFFF);
                vm.write32(buf+4, 3);
                return 1;
            case 3:  // Communication: struct CAPSHDR {u16 size; u16 flags;} = SO 4 BYTES (vmgpcaps.h).
                // Escreviamos 8 bytes aqui. O Martial Arts 3D reserva exatamente 4 bytes na PILHA p/ este
                // buffer (STORE RA; ADDQ sp,-4; P1=sp), entao o write32(buf+4) caia em cima do ENDERECO DE
                // RETORNO salvo, zerando-o: o RET saltava p/ 0 e a VM encerrava em 910 instrucoes -- parecia
                // "main retornou". Escrever so os 4 bytes da struct resolve.
                vm.write16(buf+0, 4);
                vm.write16(buf+2, 0);
                return 1;
            default: // Input/etc
                vm.write16(buf+0, 8);
                vm.write16(buf+2, 0);
                vm.write32(buf+4, 0);
                return 1;
        }
    }

    // ---------------- streams (recursos do .mpn + save game em disco) ----------------
    // vStreamOpen(fileName, mode). tipo = mode&0xFF (7=Resource, 0=File); resNum = mode>>16.
    // Flags de File: 0x100=Read, 0x200=Write, 0x400=Create, 0x800=Truncate.
    int streamOpen(int namePtr, int mode){
        int type = mode & 0xFF;
        if(type==7){                                  // Resource (dados do .mpn)
            // ATENCAO: recurso NAO e so leitura. Varios jogos (a familia *Pro Contest da
            // Synergenix -- Rally, Golf, Football -- e outros 3D) guardam o PROGRESSO
            // escrevendo de volta num recurso do proprio container, e nao num arquivo:
            //     vStreamOpen(NULL, (res<<16) | 0x207)   // 0x200 = Write
            //     vStreamOpen(NULL, (res<<16) | 0x707)   // Read|Write|Binary
            // No aparelho o .mpn instalado e gravavel, entao isso persiste. Aqui a ROM e
            // intocavel, por isso a escrita vai para uma SOBREPOSICAO por jogo em
            // saveDir/res_<n>.bin -- e a leitura prefere a sobreposicao, se existir. Sem isto
            // o jogo escrevia num buffer jogado fora no vStreamClose: o save sumia.
            int resNum = mode>>>16;
            boolean write = (mode & SF_WRITE)!=0;
            boolean zera  = (mode & (SF_TRUNC|SF_CREATE))!=0;
            boolean naRom = resNum < vm.exe.resourceCount();
            java.io.File ov = resSaveFile(resNum);
            byte[] data;
            if(write && zera)      data = new byte[0];          // regrava do zero
            else if(ov.isFile()){                                // progresso ja salvo
                try { data = readAllBytes(ov); } catch(Exception e){ return -1; }
            }
            else if(naRom){                                      // conteudo original da ROM
                int off = vm.exe.resourceOffset(resNum), len = vm.exe.resourceLength(resNum);
                data = new byte[len];
                System.arraycopy(vm.exe.all, off, data, 0, len);
            }
            else if(write) data = new byte[0];                   // slot novo, so p/ gravar
            else return -1;
            int fd = nextFd++;
            streamData.put(fd, data); streamPos.put(fd, new int[]{0});
            if(write){ streamFile.put(fd, ov); streamWritable.put(fd, true); }   // persiste a cada write
            return fd;
        }
        // type 0 = File.
        String nm = readStr(namePtr, false);
        boolean writingF = (mode & SF_WRITE)!=0;
        // ARQUIVO EMBUTIDO (ZIP no .mpn): resolve nomes de asset e o proprio ".mpc" (ex.: Da Vinci Code).
        if(!writingF && vm.exe.hasArchive()){
            byte[] emb = nm.toLowerCase().endsWith(".mpc") ? vm.exe.archiveBytes() : vm.exe.vfsGet(nm);
            if(emb != null){
                int fd = nextFd++;
                streamData.put(fd, emb); streamPos.put(fd, new int[]{0});
                return fd;
            }
        }
        if(SAVELOG) System.out.println("[SAVE] open File '"+nm+"' mode=0x"+Integer.toHexString(mode)
            +((mode&SF_WRITE)!=0?" WRITE":" read"));
        return fileOpen(nm, mode);
    }

    // Flags de stream (StreamFlags.cs do nofun): Read=0x100, Write=0x200, Binary=0x400,
    // Create=0x800, Trunc=0x1000, MustNotExistBefore=0x2000.
    static final int SF_READ=0x100, SF_WRITE=0x200, SF_BINARY=0x400, SF_CREATE=0x800, SF_TRUNC=0x1000, SF_EXCL=0x2000;

    // Abre um save em disco (pasta saveDir). Devolve o conteudo atual num buffer editavel; gravaveis
    // sao persistidos JA na abertura e a cada write (nao dependem do vStreamClose -> save robusto
    // mesmo se o jogo/janela fechar sem fechar o stream). Semantica = nofun VMWrapperIStream.
    int fileOpen(String name, int mode){
        boolean write  = (mode & SF_WRITE)!=0;
        boolean create = (mode & SF_CREATE)!=0;
        boolean trunc  = (mode & SF_TRUNC)!=0;
        java.io.File f = saveFile(name);
        byte[] data;
        // O companheiro/arquivo embutido tambem vale para ABERTURA DE ESCRITA: no aparelho o
        // arquivo JA EXISTE, entao um jogo que abre Read|Write para ATUALIZAR um arquivo de dados
        // tem de receber o conteudo dele, nao um arquivo vazio. So Create/Trunc comecam do zero.
        java.io.File companion = (!create && !trunc) ? findCompanion(name) : null;
        byte[] embutido = (!create && !trunc && companion==null && vm.exe.hasArchive())
                        ? vm.exe.vfsGet(name) : null;
        if(create || trunc){             // Create/Trunc: comeca vazio (regrava do zero)
            data = new byte[0];
        } else if(f.isFile()){           // arquivo existe: carrega conteudo
            try { data = readAllBytes(f); } catch(Exception e){ return -1; }
        } else if(companion!=null){      // ARQUIVO DE DADOS COMPANHEIRO ao lado da ROM
            try { data = readAllBytes(companion); } catch(Exception e){ return -1; }
        } else if(embutido!=null){       // arquivo embutido no .mpn (VFS/ZIP)
            data = embutido;
        } else if(write){                // gravavel sem existir -> OpenOrCreate (nofun): cria vazio
            data = new byte[0];
        } else if((mode & SF_BINARY)!=0 && (mode & SF_EXCL)==0){   // STREAM_EXCL(0x2000): jogo espera arquivo existir -> -1 (Da Vinci Code .clevel/.pan); sem EXCL = handle vazio (Deep Abyss)
            // leitura BINARIA (STREAM_BINARY) de arquivo inexistente -> trata como arquivo de DADOS
            // vazio (handle valido, 0 bytes), NAO como "sem save". Alguns jogos abrem um arquivo de
            // dados que so passa a existir depois de criado; ex.: Deep Abyss multiscreen le 'level'
            // (modo 0x500=Read|Binary) e, sem isto, recebia -1 -> "Failed to open mappack" -> terminava.
            // Saves de "Continue"/highscore usam modo TEXTO (0x100, sem Binary) -> continuam recebendo -1.
            data = new byte[0];
        } else {                         // leitura TEXTO de save inexistente -> falha (jogo sabe: sem "Continue")
            return -1;
        }
        int fd = nextFd++;
        streamData.put(fd, data); streamPos.put(fd, new int[]{0});
        streamFile.put(fd, f); streamWritable.put(fd, write);
        if(write) flushFile(f, data);    // cria/persiste JA (robustez: nao espera o close)
        if(trace) System.out.println("  [save] open '"+name+"' -> "+f.getName()+" mode=0x"+Integer.toHexString(mode)
            +(write?" (write)":" (read)")+" bytes="+data.length);
        return fd;
    }
    // grava o buffer inteiro do save no disco (idempotente; buffer sempre consistente apos seek/write)
    void flushFile(java.io.File f, byte[] data){
        if(f==null) return;
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try { out.write(data!=null? data : new byte[0]); } finally { out.close(); }
        } catch(Exception e){ System.err.println("[save] failed to write "+f+": "+e); }
    }

    /** Sobreposicao gravavel de um RECURSO (save nativo dos jogos que gravam no container). */
    java.io.File resSaveFile(int resNum){
        if(!saveDir.exists()) saveDir.mkdirs();
        return new java.io.File(saveDir, "res_"+resNum+".bin");
    }

    // mapeia o nome logico do jogo ("resume"/"highscore") p/ um arquivo seguro em saveDir
    // Pasta da ROM: varios jogos mophun vem com ARQUIVOS DE DADOS COMPANHEIROS ao lado do .mpn
    // (ex.: "dvc.mpc" do Da Vinci Code, "VRally2_extrapack1.mpc", "Microw0.mpc"). O jogo os abre por nome
    // via vStreamOpen; sem procura-los aqui so olhavamos saveDir/<nome>.sav e o jogo recebia arquivo VAZIO
    // -> sem modelos/niveis (tela preta). Somente LEITURA.
    public java.io.File romDir;
    java.io.File findCompanion(String name){
        if(romDir==null || name==null || name.isEmpty()) return null;
        if(name.indexOf((char)47)>=0 || name.indexOf((char)92)>=0 || name.contains("..")) return null;
        java.io.File exact = new java.io.File(romDir, name);
        if(exact.isFile()) return exact;
        java.io.File[] list = romDir.listFiles();
        if(list!=null) for(java.io.File c : list) if(c.isFile() && c.getName().equalsIgnoreCase(name)) return c;
        return null;
    }

    java.io.File saveFile(String name){
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        if(safe.isEmpty()) safe = "save";
        if(!saveDir.exists()) saveDir.mkdirs();
        return new java.io.File(saveDir, safe + ".sav");
    }
    static byte[] readAllBytes(java.io.File f) throws java.io.IOException {
        byte[] d = new byte[(int)f.length()];
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
        try { in.readFully(d); } finally { in.close(); }
        return d;
    }

    int streamRead(int handle,int buf,int count){
        byte[] data = streamData.get(handle); if(data==null) return -1;
        int[] pp = streamPos.get(handle); int pos = pp[0];
        int n = Math.min(count, data.length-pos); if(n<0) n=0;
        for(int i=0;i<n;i++) vm.write8(buf+i, data[pos+i]&0xff);
        pp[0]=pos+n; return n;
    }
    // grava na memoria do stream; cresce o buffer se necessario (save game)
    int streamWrite(int handle,int buf,int count){
        byte[] data = streamData.get(handle); if(data==null || count<0) return -1;
        int[] pp = streamPos.get(handle); int pos = pp[0];
        if(pos+count > data.length){                       // cresce p/ caber a escrita
            byte[] nd = new byte[pos+count];
            System.arraycopy(data,0,nd,0,data.length);
            data = nd; streamData.put(handle, data);
        }
        for(int i=0;i<count;i++) data[pos+i] = (byte)vm.read8(buf+i);
        pp[0] = pos+count;
        // persiste JA se for arquivo gravavel (nao depende do vStreamClose)
        if(Boolean.TRUE.equals(streamWritable.get(handle))) flushFile(streamFile.get(handle), data);
        return count;
    }
    int streamSeek(int handle,int where,int whence){  // 0=Set 1=Cur 2=End
        byte[] data = streamData.get(handle); if(data==null) return -1;
        int[] pp = streamPos.get(handle); int pos = pp[0];
        switch(whence){ case 0: pos=where; break; case 1: pos+=where; break; case 2: pos=data.length+where; break; }
        if(pos<0)pos=0; if(pos>data.length)pos=data.length; pp[0]=pos; return pos;
    }
    // fecha o stream; se for arquivo gravavel, persiste o buffer no disco
    void streamClose(int handle){
        java.io.File f = streamFile.get(handle);
        if(f!=null && Boolean.TRUE.equals(streamWritable.get(handle))){
            byte[] data = streamData.get(handle);
            flushFile(f, data);           // grava final (redundante c/ o flush por write, mas garante)
            if(trace) System.out.println("  [save] wrote "+f+" ("+(data!=null?data.length:0)+" bytes)");
        }
        streamData.remove(handle); streamPos.remove(handle);
        streamFile.remove(handle); streamWritable.remove(handle);
    }

    // flush de TODOS os saves gravaveis ainda abertos -> chamado no stop/fechar (garante persistencia
    // mesmo se o jogo nao chamou vStreamClose antes de o usuario sair pelo Quit/fechar o emulador).
    public void flushSaves(){
        for(java.util.Map.Entry<Integer,java.io.File> e : streamFile.entrySet()){
            if(Boolean.TRUE.equals(streamWritable.get(e.getKey())))
                flushFile(e.getValue(), streamData.get(e.getKey()));
        }
    }

    // ---------------- som (vPlayResource -> MIDI via backend de plataforma) ----------------
    // O MIDI e tocado pelo MidiBridge (backend injetado: no Android = MediaPlayer; no desktop = Sequencer).
    // vPlayResource(data, length, flags): tipo=flags&0xF, loop=0x100, stream=0x200, stop=0x400
    int playResource(int data,int length,int flags){
        try {
            if((flags & 0x400)!=0){ stopSound(); return 1; }   // Stop
            if(!soundEnabled) return 1;                         // som desligado (Settings)
            byte[] bytes;
            if((flags & 0x200)!=0){                            // Stream: data = handle
                byte[] s = streamData.get(data); if(s==null) return 0;
                int[] pp = streamPos.get(data); int pos=pp[0];
                int n = Math.min(length, s.length-pos); if(n<0)n=0;
                bytes = new byte[n]; System.arraycopy(s,pos,bytes,0,n); pp[0]=pos+n;
            } else {                                           // memoria da VM
                if(length<=0||length>1<<20) return 0;
                bytes = new byte[length];
                for(int i=0;i<length;i++) bytes[i]=(byte)vm.read8(data+i);
            }
            // toca so se for MIDI (SMF "MThd")
            if(bytes.length<4 || bytes[0]!='M'||bytes[1]!='T'||bytes[2]!='h'||bytes[3]!='d') return 1;
            stopSound();
            MidiBridge.play(reverb ? bytes : MidiReverb.kill(bytes), (flags & 0x100)!=0);
            return 1;
        } catch(Throwable e){ return 0; }   // sem dispositivo MIDI: nao trava o jogo
    }
    // para o som ATUAL (MIDI + vozes), mas MANTEM o motor de audio vivo — usado ao pausar / trocar de MIDI.
    // (antes fazia vsound.dispose() aqui -> derrubava o AudioTrack e o jogo, que so chama vSoundInit 1x,
    //  ficava MUDO p/ sempre depois de qualquer pausa/MIDI.)
    public void stopSound(){
        try { MidiBridge.stop(); } catch(Throwable e){}
        try { if(vsound!=null) vsound.stopVoices(); } catch(Throwable e){}
    }
    // teardown COMPLETO do audio (fim da sessao / destruir a Activity).
    public void disposeAudio(){
        try { MidiBridge.stop(); } catch(Throwable e){}
        try { if(vsound!=null) vsound.dispose(); } catch(Throwable e){}
    }

    // ---------------- save state (snapshot da VM: funciona p/ QUALQUER jogo) ----------------
    static final int STATE_MAGIC = 0x4D505631;    // "MPV1" (antigo, sem tick)
    static final int STATE_MAGIC2 = 0x4D505632;   // "MPV2" (com base de tempo)
    static final int STATE_MAGIC3 = 0x4D505633;   // "MPV3" (+framebuffer, p/ fundo persistente das corridas)
    static final int STATE_MAGIC5 = 0x4D505635;   // "MPV5" (+estado do pipeline 3D: matrizes/viewport/render states)
    static final int STATE_MAGIC4 = 0x4D505634;   // "MPV4" (+metadados do allocator: free list — p/ o detector de leak sobreviver a save/load)
    static final int STATE_MAGIC6 = 0x4D505636;   // "MPV6" (+luzes/ambiente/material: MPV5 perdia todas as luzes ao carregar)
    static final int STATE_MAGIC7 = 0x4D505637;   // "MPV7" (+texturas gerenciadas: MPV6 perdia o mapa de handles do vCreateTexture)
    static final int STATE_MAGIC8 = 0x4D505638;   // "MPV8" (+copia do MAP_HEADER: o ponteiro do jogo pode morrer)
    // "MPV9" (+ponteiro da fonte ativa do jogo). O vSetActiveFont guarda so um PONTEIRO para a struct
    // VMGPFONT, que vive na memoria da VM (essa sim era salva). Sem persistir o ponteiro, ao retomar um
    // estado o aFontPtr voltava a 0 e o vPrint caia na fonte do SISTEMA: no Honey Cave 2 o placar do HUD
    // ("20" / "31020") saia como blocos brancos. Os demais campos sao derivados -> fontRefresh() relê.
    static final int STATE_MAGIC9 = 0x4D505639;
    public void saveState(java.io.File f) throws java.io.IOException {
        java.io.DataOutputStream o = new java.io.DataOutputStream(
            new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)));
        try {
            o.writeInt(STATE_MAGIC9);
            o.writeInt(vm.R.length);
            for(int i=0;i<vm.R.length;i++) o.writeInt(vm.R[i]);
            o.writeInt(vm.mem.length); o.write(vm.mem);
            o.writeInt(nextHeap); o.writeInt(foreColor); o.writeInt(backColor); o.writeInt(transferMode);
            o.writeLong(rngState);
            o.writeInt(mapHeaderPtr); o.writeInt(mapX); o.writeInt(mapY);
            o.writeInt(spriteCount);
            for(int i=0;i<spriteCount;i++){ o.writeInt(slotSprite[i]); o.writeShort(slotX[i]); o.writeShort(slotY[i]); }
            for(int i=0;i<256;i++) o.writeInt(scr.palette[i]);
            o.writeLong(System.currentTimeMillis()-tick0);   // vGetTickCount atual (base de tempo)
            o.writeInt(scr.fb.length);                        // framebuffer (fundo persistente desenhado 1x)
            for(int i=0;i<scr.fb.length;i++) o.writeInt(scr.fb[i]);
            // metadados do allocator (MPV4): sem isto, apos um load os blocos alocados antes do save ficavam
            // "desconhecidos" -> free deles virava no-op -> o detector de leak (Deep Abyss) reaparecia.
            o.writeInt(freeBytes);
            o.writeInt(liveBlocks.size());
            for(java.util.Map.Entry<Integer,Integer> e : liveBlocks.entrySet()){ o.writeInt(e.getKey()); o.writeInt(e.getValue()); }
            o.writeInt(freeList.size());
            for(int[] b : freeList){ o.writeInt(b[0]); o.writeInt(b[1]); }
            r3d.saveState3D(o);          // MPV5: pipeline 3D (projecao/camera/viewport/render states)
            r3d.saveLights(o);           // MPV6: luzes/ambiente/material
            r3d.saveTextures(o);         // MPV7: texturas gerenciadas (handles do vCreateTexture)
            // MPV8: copia do MAP_HEADER (o buffer do jogo pode ja estar morto na hora do save)
            o.writeBoolean(mhValid);
            o.writeInt(mhFlag); o.writeInt(mhFmt); o.writeInt(mhW); o.writeInt(mhH); o.writeInt(mhAnim);
            o.writeInt(mhXPan); o.writeInt(mhYPan); o.writeInt(mhXPos); o.writeInt(mhYPos);
            o.writeInt(mhMapData); o.writeInt(mhTileData);
            o.writeInt(aFontPtr);        // MPV9: fonte bitmap ativa do jogo
        } finally { o.close(); }
    }
    public void loadState(java.io.File f) throws java.io.IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(
            new java.io.BufferedInputStream(new java.io.FileInputStream(f)));
        try {
            int magic=in.readInt();
            if(magic!=STATE_MAGIC && magic!=STATE_MAGIC2 && magic!=STATE_MAGIC3 && magic!=STATE_MAGIC4 && magic!=STATE_MAGIC5 && magic!=STATE_MAGIC6 && magic!=STATE_MAGIC7 && magic!=STATE_MAGIC8 && magic!=STATE_MAGIC9) throw new java.io.IOException("invalid state");
            int nr=in.readInt(); for(int i=0;i<nr;i++){ int v=in.readInt(); if(i<vm.R.length) vm.R[i]=v; }
            int ml=in.readInt(); byte[] m=new byte[ml]; in.readFully(m);
            System.arraycopy(m,0,vm.mem,0,Math.min(ml,vm.mem.length));
            nextHeap=in.readInt(); foreColor=in.readInt(); backColor=in.readInt(); transferMode=in.readInt();
            liveBlocks.clear(); freeList.clear(); freeBytes=0;   // metadados do allocator (MPV4 os restaura abaixo)
            rngState=in.readLong();
            mapHeaderPtr=in.readInt(); mapX=in.readInt(); mapY=in.readInt();
            // restaura o estado de sprites AUTO-CONTIDO: realoca os arrays (senao crasha se carregado
            // fora do gameplay, antes do jogo chamar vSpriteInit -> slotSprite==null)
            int sc=in.readInt();
            spriteCount=sc;
            slotSprite=new int[sc]; slotX=new short[sc]; slotY=new short[sc];
            for(int i=0;i<sc;i++){ slotSprite[i]=in.readInt(); slotX[i]=(short)in.readShort(); slotY[i]=(short)in.readShort(); }
            for(int i=0;i<256;i++) scr.palette[i]=in.readInt();
            // base de tempo: sem isso o jogo trava em while(vGetTickCount()<alvo). MPV2 tem o tick salvo;
            // saves antigos (MPV1) nao -> pula 1h pra frente pra satisfazer qualquer alvo pendente.
            if(magic==STATE_MAGIC2 || magic==STATE_MAGIC3 || magic==STATE_MAGIC4 || magic==STATE_MAGIC5 || magic==STATE_MAGIC6 || magic==STATE_MAGIC7 || magic==STATE_MAGIC8 || magic==STATE_MAGIC9){ long savedTick=in.readLong(); tick0=System.currentTimeMillis()-savedTick; }
            else { tick0=System.currentTimeMillis()-3600000L; }
            if(magic==STATE_MAGIC3 || magic==STATE_MAGIC4 || magic==STATE_MAGIC5 || magic==STATE_MAGIC6 || magic==STATE_MAGIC7 || magic==STATE_MAGIC8 || magic==STATE_MAGIC9){   // restaura o framebuffer (fundo persistente)
                int fl=in.readInt();
                boolean apply=(fl==scr.fb.length);
                for(int i=0;i<fl;i++){ int v=in.readInt(); if(apply) scr.fb[i]=v; }
            }
            if(magic==STATE_MAGIC4 || magic==STATE_MAGIC5 || magic==STATE_MAGIC6 || magic==STATE_MAGIC7 || magic==STATE_MAGIC8 || magic==STATE_MAGIC9){    // metadados do allocator (free list)
                freeBytes=in.readInt();
                int nlive=in.readInt(); for(int i=0;i<nlive;i++){ int p=in.readInt(), s=in.readInt(); liveBlocks.put(p,s); }
                int nfree=in.readInt(); for(int i=0;i<nfree;i++){ int p=in.readInt(), s=in.readInt(); freeList.add(new int[]{p,s}); }
            }
            if(magic==STATE_MAGIC5 || magic==STATE_MAGIC6 || magic==STATE_MAGIC7 || magic==STATE_MAGIC8 || magic==STATE_MAGIC9) r3d.loadState3D(in);   // pipeline 3D (sem isto a projecao voltava p/ identidade)
            if(magic==STATE_MAGIC6 || magic==STATE_MAGIC7 || magic==STATE_MAGIC8 || magic==STATE_MAGIC9) r3d.loadLights(in);       // luzes/ambiente/material (MPV5 os perdia)
            if(magic==STATE_MAGIC7 || magic==STATE_MAGIC8 || magic==STATE_MAGIC9) r3d.loadTextures(in);     // handles do vCreateTexture (MPV6 os perdia -> textura errada em tudo)
            if(magic==STATE_MAGIC8 || magic==STATE_MAGIC9){
                mhValid=in.readBoolean();
                mhFlag=in.readInt(); mhFmt=in.readInt(); mhW=in.readInt(); mhH=in.readInt(); mhAnim=in.readInt();
                mhXPan=in.readInt(); mhYPan=in.readInt(); mhXPos=in.readInt(); mhYPos=in.readInt();
                mhMapData=in.readInt(); mhTileData=in.readInt();
            } else mhValid=false;   // saves antigos: reconstroi do header se ele ainda estiver vivo
            if(magic==STATE_MAGIC9){ aFontPtr=in.readInt(); fontRefresh(); }   // fonte ativa do jogo
        } finally { in.close(); }
    }

    // ---------------- descompressao LZ (mophun) ----------------
    static int u32le(byte[] b,int o){ return (b[o]&0xff)|((b[o+1]&0xff)<<8)|((b[o+2]&0xff)<<16)|((b[o+3]&0xff)<<24); }

    // Descomprime LZ lendo o fluxo de um stream (source==0 no vDecompress). dest fica na memoria da VM.
    int decompressStream(int handle,int dest){
        byte[] s = streamData.get(handle); if(s==null) return -1;
        int[] pp = streamPos.get(handle); int off = pp[0];
        if(s.length-off < 22 || s[off]!='L' || s[off+1]!='Z') return -1;
        int maxB=s[off+2]&0xff, extB=s[off+3]&0xff;
        int usize=u32le(s,off+4), csize=u32le(s,off+8);
        ByteBits bs = new ByteBits(s, off+22, csize);
        int dp=0;
        while(dp<usize && bs.valid()){
            if(bs.read(1)==1){
                int v2=0; while(v2<maxB && bs.read(1)==1) v2++;
                int cl=2; if(v2!=0) cl=(bs.read(v2)|(1<<v2))+1;
                int bo = (cl==2)? bs.read(8)+2 : bs.read(extB)+cl;
                if(cl>usize-dp) cl=usize-dp;
                for(int i=0;i<cl;i++) vm.write8(dest+dp+i, vm.read8(dest+dp-bo+i));
                dp+=cl;
            } else { vm.write8(dest+dp, bs.read(8)); dp++; }
        }
        pp[0] = off + 22 + csize;
        return dp;
    }
    // leitor de bits MSB-first sobre um byte[] (mesma semantica de LZBits, mas fonte em array)
    static final class ByteBits {
        final byte[] a; final int base,len; int p=0;
        ByteBits(byte[] a,int base,int len){ this.a=a; this.base=base; this.len=len; }
        int g(int i){ int idx=base+i; return (idx>=0&&idx<a.length)? (a[idx]&0xff):0; }
        boolean valid(){ return p < len*8; }
        int read(int count){
            int result=0,bitLeft=count,pp=p;
            if(pp%8!=0){ int toE=Math.min(8-pp%8,bitLeft);
                result=(g(pp>>3)>>(8-((pp%8)+toE)))&(0xFF>>(8-toE)); bitLeft-=toE; pp+=toE; }
            if(bitLeft==0){ p=pp; return result; }
            for(int i=0;i<(bitLeft>>3);i++){ result=(result<<8)|g(pp>>3); bitLeft-=8; pp+=8; }
            if(bitLeft==0){ p=pp; return result; }
            result=(result<<bitLeft)|(g(pp>>3)>>(8-bitLeft)); pp+=bitLeft; p=pp; return result;
        }
    }
    // vDecompHdr: le header LZ (usize@source+4, csize@source+8), preenche NativeCompressedFileInfo (20B):
    // cnt@0, offset@1, crc16@2, option@4, srcSize@8, destSize@12, literalSize@16. Retorna usize.
    int decompHdr(int infoPtr,int source){
        if(source==0 || vm.read8(source)!='L' || vm.read8(source+1)!='Z') return -1;
        int usize=vm.read32(source+4), csize=vm.read32(source+8);
        if(infoPtr!=0){
            vm.write8(infoPtr+0, 0);          // cnt
            vm.write8(infoPtr+1, 0);          // offset
            vm.write16(infoPtr+2, 0x1234);    // crc16
            vm.write16(infoPtr+4, 0);         // option
            vm.write32(infoPtr+8, csize);     // srcSize
            vm.write32(infoPtr+12, usize);    // destSize
            vm.write32(infoPtr+16, 0);        // literalSize
        }
        return usize;
    }
    int decompress(int source,int dest){
        if(source==0) return -1;
        if(vm.read8(source)!='L' || vm.read8(source+1)!='Z') return -1;
        int maxB=vm.read8(source+2), extB=vm.read8(source+3);
        int usize=vm.read32(source+4);
        int csize=vm.read32(source+8);
        LZBits bs=new LZBits(source+22, csize);
        int dp=0;
        while(dp<usize && bs.valid()){
            if(bs.read(1)==1){
                int v2=0; while(v2<maxB && bs.read(1)==1) v2++;
                int cl=2; if(v2!=0) cl=(bs.read(v2)|(1<<v2))+1;
                int bo = (cl==2)? bs.read(8)+2 : bs.read(extB)+cl;
                if(cl>usize-dp) cl=usize-dp;
                for(int i=0;i<cl;i++) vm.write8(dest+dp+i, vm.read8(dest+dp-bo+i));
                dp+=cl;
            } else {
                vm.write8(dest+dp, bs.read(8)); dp++;
            }
        }
        return dp;
    }
    final class LZBits {
        final int base,len; int p=0;
        LZBits(int base,int len){ this.base=base; this.len=len; }
        boolean valid(){ return p < len*8; }
        int read(int count){
            int result=0,bitLeft=count,pp=p;
            if(pp%8!=0){ int toE=Math.min(8-pp%8,bitLeft);
                result=(vm.read8(base+(pp>>3))>>(8-((pp%8)+toE)))&(0xFF>>(8-toE)); bitLeft-=toE; pp+=toE; }
            if(bitLeft==0){ p=pp; return result; }
            for(int i=0;i<(bitLeft>>3);i++){ result=(result<<8)|vm.read8(base+(pp>>3)); bitLeft-=8; pp+=8; }
            if(bitLeft==0){ p=pp; return result; }
            result=(result<<bitLeft)|(vm.read8(base+(pp>>3))>>(8-bitLeft)); pp+=bitLeft; p=pp; return result;
        }
    }
}
