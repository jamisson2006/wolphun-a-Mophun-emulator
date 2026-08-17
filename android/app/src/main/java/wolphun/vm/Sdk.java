package wolphun.vm;

import wolphun.Screen;
import java.util.*;

/**
 * Camada de APIs do SDK mophun (vGfx*, vSprite*, vMap*, ...). Cada CALLl p/ um
 * import cai aqui via invoke(nome). Args em p0..p3/pilha, retorno em r0.
 * Subconjunto essencial implementado; o resto e logado (para irmos completando).
 */
public final class Sdk {
    final Pip2 vm; final Screen scr;
    long tick0 = System.currentTimeMillis();
    int nextHeap;
    int foreColor=0xff, backColor=0, transferMode=1;   // default = TransferMode.Transparent (nofun)
    long rngState = 0x12345678L;
    public boolean soundEnabled = true;   // configuravel pelo emulador (Settings)
    // identidade do aparelho (vGetCaps System): deviceId = (model<<16)|vendor. Configuravel.
    public int deviceModel = 2;            // SonyEricssonT610 (default)
    public int deviceVendor = 3;           // SonyEricsson
    public int apiMinor = 50;              // vGetVMGPInfo = (2<<16)|apiMinor

    // sprites
    int spriteCount=0; int[] slotSprite; short[] slotX, slotY;
    // mapa atual
    int mapHeaderPtr=0; int mapX=0, mapY=0;
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
    public Sdk(Pip2 vm, Screen scr){
        this.vm=vm; this.scr=scr; this.nextHeap=vm.heapBase; this.vsound=new VSound(vm); this.r3d=new Renderer3D(vm,scr);
        this.version150 = vm.exe.importsSymbol("vInit3D");
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
            case "vNewPtr": case "vMalloc": vm.ret(alloc(vm.arg(0), false)); break;
            case "vNewPtrClear": case "vAllocClear": case "vCallocPtr": vm.ret(alloc(vm.arg(0), true)); break;
            case "vDisposePtr": case "vFreePtr": vm.ret(0); break;   // bump allocator: no-op
            // ---------- sistema ----------
            case "vGetTickCount": vm.ret((int)(System.currentTimeMillis()-tick0)); break;
            case "vGetTimeDate": case "vGetTimeDateUTC": writeDateTime(vm.arg(0)); vm.ret(0); break;
            // vGetRandom retorna um valor CRU [0,32767] (estilo rand()/RAND_MAX). O ARGUMENTO é IGNORADO:
            // os jogos fazem o próprio módulo (Bouncy: min + rnd%(max-min+1); outros: rnd%100 etc).
            // Se modássemos aqui (r%arg), o módulo do jogo depois virava no-op -> loop infinito (Bouncy travava).
            case "vGetRandom": { rngState=rngState*6364136223846793005L+1442695040888963407L; vm.ret((int)((rngState>>>33)&0x7fff)); break; }
            case "vSetRandom": rngState = (vm.arg(0)&0xffffffffL); break;
            case "vGetCaps": vm.ret(getCaps(vm.arg(0), vm.arg(1))); break;
            case "vSysCtl": vm.ret(0); break;
            case "vTerminateVMGP": vm.stopped=true; break;
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
            case "vBeep": break;
            // ---------- graficos ----------
            case "vClearScreen": dlog("clearScreen fore="+colStr(vm.arg(0))); Arrays.fill(scr.fb, getColor(vm.arg(0))); vm.ret(0); break;
            case "vFlipScreen": if(DRAWLOG) dlog("--- FLIP (frame "+drawLogFrame+") ---"); scr.present(); vm.ret(0); break;
            case "vSetForeColor": foreColor=vm.arg(0); break;   // cor crua (indice ou RGB555 se bit31)
            case "vSetBackColor": backColor=vm.arg(0); break;
            case "vSetPaletteEntry": scr.setPaletteRgb555(vm.arg(0), vm.arg(1)); break;
            case "vGetPaletteEntry": vm.ret(Screen.argbToRgb555(scr.palette[vm.arg(0)&0xff])); break;
            case "vSetPalette": setPalette(vm.arg(0), vm.arg(1), vm.arg(2)); break;
            case "vSetTransferMode": transferMode=vm.arg(0); break;
            case "vSetClipWindow": dlog("setClipWindow ("+(short)vm.arg(0)+","+(short)vm.arg(1)+")-("+(short)vm.arg(2)+","+(short)vm.arg(3)+")"); scr.setClip((short)vm.arg(0),(short)vm.arg(1),(short)vm.arg(2),(short)vm.arg(3)); break;
            case "vFillRect": fillRect((short)vm.arg(0),(short)vm.arg(1),(short)vm.arg(2),(short)vm.arg(3)); break;
            case "vDrawLine": drawLine((short)vm.arg(0),(short)vm.arg(1),(short)vm.arg(2),(short)vm.arg(3)); break;
            case "vPlot": break;                               // no-op (fiel ao nofun)
            case "vGetPixel": vm.ret(0); break;
            case "vUpdateSpriteMap": drawMap(); if(slotSprite!=null) for(int i=0;i<spriteCount;i++) if(slotSprite[i]!=0) drawObject(slotX[i],slotY[i],slotSprite[i]); break;
            // ---------- input ----------
            case "vGetButtonData": vm.ret(scr.getButtons()); break;
            // ---------- sprites ----------
            case "vSpriteInit": spriteCount=vm.arg(0)&0xff; slotSprite=new int[spriteCount]; slotX=new short[spriteCount]; slotY=new short[spriteCount]; vm.ret(1); break;
            case "vSpriteClear": if(slotSprite!=null) Arrays.fill(slotSprite,0); break;
            case "vSpriteSet": { int s=vm.arg(0)&0xff; if(slotSprite!=null&&s<spriteCount){ slotSprite[s]=vm.arg(1); slotX[s]=(short)vm.arg(2); slotY[s]=(short)vm.arg(3);} break; }
            case "vUpdateSprite": if(slotSprite!=null) for(int i=0;i<spriteCount;i++) if(slotSprite[i]!=0) drawObject(slotX[i],slotY[i],slotSprite[i]); break;
            case "vDrawObject": drawObject((short)vm.arg(0),(short)vm.arg(1),vm.arg(2)); break;
            case "vUpdateSpriteRotate": break;
            case "vSpriteBoxCollision": vm.ret(spriteBoxCollision(vm.arg(0), vm.arg(1)&0xff, vm.arg(2)&0xff)); break;
            case "vSpriteCollision": vm.ret(spriteCollision(vm.arg(0)&0xff, vm.arg(1)&0xff, vm.arg(2)&0xff)); break;
            // ---------- tilemap ----------
            case "vMapInit": mapHeaderPtr=vm.arg(0); vm.ret(1); break;
            case "vMapDispose": mapHeaderPtr=0; break;
            case "vUpdateMap": drawMap(); break;
            case "vMapSetXY": if(mapHeaderPtr!=0){ vm.write16(mapHeaderPtr+12, vm.arg(0)); vm.write16(mapHeaderPtr+14, vm.arg(1)); } break;
            // vMapGetAttribute/Tile(byte x, byte y): args sao BYTES no nofun -> mascarar &0xFF (corrige x "negativo" ao virar esquerda)
            case "vMapGetAttribute": vm.ret(mapAttr(vm.arg(0)&0xff, vm.arg(1)&0xff)); break;
            case "vMapGetTile": vm.ret(mapTile(vm.arg(0)&0xff, vm.arg(1)&0xff)); break;
            // ---------- texto (fonte do sistema via Java) ----------
            case "vSelectFont": scr.selectFont(vm.arg(0)); vm.ret(vm.arg(1)); break;
            case "vSetActiveFont": vm.ret(0); break;   // fonte bitmap custom: usamos a do sistema
            case "vTextOut":  scr.drawText((short)vm.arg(0), (short)vm.arg(1), readStr(vm.arg(2), false), getColor(foreColor)); vm.ret(0); break;
            case "vTextOutU": scr.drawText((short)vm.arg(0), (short)vm.arg(1), readStr(vm.arg(2), true),  getColor(foreColor)); vm.ret(0); break;
            case "vPrint":    scr.drawText((short)vm.arg(1), (short)vm.arg(2), readStr(vm.arg(3), false), getColor(foreColor)); vm.ret(0); break;
            // extent = (altura << 16) | largura (o jogo le srli #16 p/ altura, &0xFFFF p/ largura)
            case "vTextExtent":  vm.ret(textExtent(readStr(vm.arg(0), false))); break;
            case "vTextExtentU": vm.ret(textExtent(readStr(vm.arg(0), true))); break;
            case "vCharExtent":  vm.ret(textExtent(new String(Character.toChars(vm.arg(0)&0xffff)))); break;
            // vMsgBox(flags, mensagem, titulo): flags&0x80=tem titulo. Modal, retorna botao (1=OK).
            case "vMsgBoxU": vm.ret(scr.showMessageBox((vm.arg(0)&0x80)!=0?readStr(vm.arg(2),true):null, readStr(vm.arg(1),true))); break;
            case "vMsgBox":  vm.ret(scr.showMessageBox((vm.arg(0)&0x80)!=0?readStr(vm.arg(2),false):null, readStr(vm.arg(1),false))); break;
            // ---------- string ----------
            case "vStrLen": { int p=vm.arg(0),n=0; while(vm.read8(p+n)!=0)n++; vm.ret(n); break; }
            case "vStrCpy": { int dst=vm.arg(0),src=vm.arg(1),i=0; int b; do{ b=vm.read8(src+i); vm.write8(dst+i,b); i++; }while(b!=0); vm.ret(dst); break; }
            case "vitoa": vm.ret(itoa(vm.arg(0),vm.arg(1),true)); break;
            case "vutoa": vm.ret(itoa(vm.arg(0),vm.arg(1),false)); break;
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
            case "vDeleteTexture": r3d.deleteTexture(vm.arg(0)); vm.ret(0); break;
            case "vDeleteAllTextures": r3d.deleteAllTextures(); vm.ret(0); break;
            case "vFreeTexture": vm.ret(0); break;                                               // no-op (igual ao nofun)
            case "vRenderPrimitive": r3d.renderPrimitive(vm.arg(0),vm.arg(1)); vm.ret(1); break;
            case "vRenderPrimitiveIndexed": r3d.renderPrimitiveIndexed(vm.arg(0),vm.arg(1),vm.arg(2),vm.arg(3)); vm.ret(1); break;
            case "vDrawPolygon": r3d.drawPolygon(vm.arg(0),vm.arg(1),vm.arg(2)); break;   // 3D (3 NativeVertexGST)
            case "vDrawBillboard": r3d.drawBillboard(vm.arg(0)); break;                   // quad texturizado camera-facing
            case "vDrawFlatPolygon": fillFlatPoly(vm.arg(0)); break;                       // 2D flat tri (foreColor)
            case "vGetInteger": if(vm.arg(1)!=0) vm.write32(vm.arg(1),2048); vm.ret(1); break;
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
            case "vCopyRect": break;                                        // no-op (igual ao nofun)
            case "vSprintf": vm.write8(vm.arg(0),0); vm.ret(0); break;       // no-op; null-termina o buffer destino
            case "vMusicInit": case "vMusicLoad": vm.ret(1); break;          // handle dummy (sem musica, sem crash)
            case "vMusicCtrl": case "vMusicCtrlEx": case "vMusicDispose": case "vMusicClose": vm.ret(0); break;
            // luzes/material/fog: no-op (nosso rasterizador usa cor difusa por vertice, nao ilumina)
            case "vSetMaterial": case "vSetMaterial2": case "vResetLights": case "vSetLight":
            case "vSetAmbientLight": case "vSetFogColor": case "vSetDisplayWindow": break;
            // math fixed-point
            case "vMul": vm.ret(r3d.vMul(vm.arg(0),vm.arg(1))); break;
            case "vDiv": vm.ret(r3d.vDiv(vm.arg(0),vm.arg(1))); break;
            case "vSqrt": vm.ret(r3d.vSqrt(vm.arg(0))); break;
            case "vSin": vm.ret(r3d.vSin(vm.arg(0))); break;
            case "vCos": vm.ret(r3d.vCos(vm.arg(0))); break;
            case "vTan": vm.ret(r3d.vTan(vm.arg(0))); break;
            default:
                if(loggedMissing.add(name)) System.out.println("  [SDK nao implementado] "+name);
                vm.ret(0);
        }
    }

    // ---------------- implementacoes ----------------
    int alloc(int size, boolean clear){
        int sz=(size+7)&~7; int p=nextHeap;
        if(p+sz>vm.heapEnd) return 0;
        nextHeap+=sz;
        if(clear) for(int i=0;i<sz;i++) vm.write8(p+i,0);
        return p;
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
    static final int SEOPTION = 217;
    int testKey(int key){
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
            case '#': return (b&F2)!=0?1:0;
            default: return 0;
        }
    }
    // vScanKeys(): ASCII da tecla atual (reverso do testKey; nofun InputDriver.KeyScan)
    int scanKeys(){
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
        if(scr.inClip(x,y)) scr.fb[y*scr.w+x]=argb;
    }
    // vFillRect(x0,y0,x1,y1) - cantos; cor = foreColor.
    // NORMALIZA cantos invertidos: jogos pseudo-3D (XxX/V-Rally) desenham as BANDAS do terreno via
    // fillRects com cantos em ordem DESCENDENTE (x0>x1 e/ou y0>y1). O nofun rejeita (`if x0>=x1||y0>=y1`)
    // -> as faixas somem; o emulador OFICIAL normaliza e desenha. Normalizamos p/ reproduzir o terreno
    // pseudo-3D com faixas claras/escuras seguindo a perspectiva da pista.
    void fillRect(int x0,int y0,int x1,int y1){
        if(x0>x1){ int t=x0; x0=x1; x1=t; }
        if(y0>y1){ int t=y0; y0=y1; y1=t; }
        if(x0==x1||y0==y1) return;                      // area zero de fato (nada a desenhar)
        int c=getColor(foreColor);
        dlog("fillRect ("+x0+","+y0+")-("+x1+","+y1+") fore="+colStr(foreColor)+" clip=["+scr.clipX0+","+scr.clipY0+"-"+scr.clipX1+","+scr.clipY1+"] v150="+version150);
        if(!version150){
            // SDK <1.50 (nofun Graphics.cs): vFillRect IGNORA o clip -> so limita as bordas da TELA.
            // Sem isto, XxX/V-Rally clipam ao topo (y<79) e o fillRect do chao (y>=100) some -> smearing.
            int cx0=Math.max(0,x0), cy0=Math.max(0,y0), cx1=Math.min(scr.w,x1), cy1=Math.min(scr.h,y1);
            for(int yy=cy0;yy<cy1;yy++){ int row=yy*scr.w; for(int xx=cx0;xx<cx1;xx++) scr.fb[row+xx]=c; }
        } else {
            for(int yy=y0;yy<y1;yy++) for(int xx=x0;xx<x1;xx++) px(xx,yy,c);   // SDK >=1.50: respeita o clip
        }
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
            if(in) scr.fb[py*scr.w+px]=c;
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
        int w=vm.read16(sp+6), h=vm.read16(sp+8);
        if(w==0||h==0||w>2048||h>2048) return;
        if(DRAWLOG && dobjBudget<400){ dobjBudget++; dlog("drawObject @("+x+","+y+") fmt="+format+" "+w+"x"+h+" palOff="+palOff+" tm="+transferMode); }
        int pix=sp+10, bpp=bppOf(format), mask=(1<<bpp)-1;
        int ox=x-cx, oy=y-cy;
        boolean flipX=(transferMode&2)!=0, flipY=(transferMode&4)!=0;   // TransferMode.FlipX=2/FlipY=4
        // transparencia (indice 0 = transparente) SO no modo Transparent (bit0); Block(0)=opaco -> fundos aparecem
        boolean transp=(transferMode&1)!=0;
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
                    // indice 0 = cor-chave transparente. RGB332 (fmt7): SEMPRE transparente, mesmo em modo
                    // Block — os sprites do HC2 pos-transicao de fase ficam com transferMode=0 mas continuam
                    // "PNG transparentes" no aparelho real. Demais formatos respeitam o transferMode (nao fura fundos opacos).
                    if((format==7 || transp) && idx==0) continue;
                    if(format==1||format==2||format==0){                        // grayscale/mono
                        int g=Math.round(idx*255f/mask); argb=0xff000000|(g<<16)|(g<<8)|g;
                    } else if(format==7){
                        argb=scr.rgb332[idx&0xff];                               // RGB332 = cor DIRETA; palOff NAO se aplica (nao e palette format)
                    } else {
                        argb=scr.palette[(idx+palOff)&0xff];                     // formatos indexados: paleta mutavel
                    }
                } else {                                                        // 16bpp direto
                    int v=vm.read16(pix+pi*2)&0xffff;
                    if(transp && v==0) continue;
                    argb=(format==8)? rgb565(v) : Screen.rgb555ToArgb(v);
                }
                scr.fb[dy*scr.w+dx]=argb;
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
        int hp=mapHeaderPtr; if(hp==0) return;
        int flag=vm.read8(hp+0);
        int mapW=vm.read8(hp+2), mapH=vm.read8(hp+3);
        int animSpeed=vm.read8(hp+4);
        int xPan=(short)vm.read16(hp+8), yPan=(short)vm.read16(hp+10);
        int xPos=(short)vm.read16(hp+12), yPos=(short)vm.read16(hp+14);

        int mapData=vm.read32(hp+16), tileData=vm.read32(hp+20);
        if(mapW==0||mapH==0||mapData==0||tileData==0) return;
        // ponteiros invalidos (fora da memoria da VM) -> nao desenha lixo (ex: menu do HC1 com mapData bogus)
        if(mapData<vm.base || mapData>=vm.base+vm.mem.length || tileData<vm.base || tileData>=vm.base+vm.mem.length) return;
        boolean hasAttr = flag!=0; int cell = hasAttr?2:1;
        final int TW=8, TH=8, tileBytes=TW*TH;   // RGB332 8bpp = 64 bytes/tile
        int fmt = vm.read8(hp+1);
        int[] pal = (fmt==7) ? scr.rgb332 : scr.palette;   // format 7 = RGB332 direto (paleta fixa); HC2 depende disso
        boolean blackTransp = (flag & 1)!=0;               // TilemapFlags.Transparent: preto = transparente
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
                if((attr&0x40)!=0) t += (frameAnimate%2);        // anima 2 frames
                else if((attr&0x80)!=0) t += (frameAnimate%4);   // anima 4 frames
                int tp=tileData + t*tileBytes;
                int sx=startDrawX+rx*TW, sy=startDrawY+ry*TH;
                for(int j=0;j<TH;j++){ int dy=sy+j; if(dy<scr.clipY0||dy>=scr.clipY1) continue;
                    for(int i=0;i<TW;i++){ int dx=sx+i; if(dx<scr.clipX0||dx>=scr.clipX1) continue;
                        int v=vm.read8(tp+j*TW+i)&0xff;
                        int argb=pal[v];
                        if(blackTransp && (argb&0xffffff)==0) continue;   // preto = transparente (mostra o fundo)
                        scr.fb[dy*scr.w+dx]=argb;
                    }
                }
            }
        }
        if(animSpeed!=0 && (++framePassed>animSpeed)){ frameAnimate=(frameAnimate+1)&3; framePassed=0; }
    }
    int frameAnimate=0, framePassed=0;

    int mapAttr(int x,int y){
        int hp=mapHeaderPtr; if(hp==0) return 0;
        if(vm.read8(hp+0)==0) return 0;                     // flag==0 => sem atributos
        int mapW=vm.read8(hp+2), mapH=vm.read8(hp+3);
        if(x<0||y<0||x>=mapW||y>=mapH) return 0;
        int mapData=vm.read32(hp+16);
        return vm.read8(mapData+(y*mapW+x)*2+1)&0xff;
    }
    int mapTile(int x,int y){
        int hp=mapHeaderPtr; if(hp==0) return 0;
        int mapW=vm.read8(hp+2), mapH=vm.read8(hp+3);
        if(x<0||y<0||x>=mapW||y>=mapH) return 0;
        int cell = vm.read8(hp+0)==0?1:2;
        return vm.read8(vm.read32(hp+16)+(y*mapW+x)*cell)&0xff;
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

    int itoa(int val,int buf,boolean signed){
        String s = signed? Integer.toString(val) : Long.toString(val&0xffffffffL);
        int i=0; for(;i<s.length();i++) vm.write8(buf+i, s.charAt(i)); vm.write8(buf+i,0); return buf;
    }

    // vGetCaps(queryType, buffer): preenche a struct e retorna 1. Video: {u16 size,flags,width,height}
    int getCaps(int queryType,int buf){
        if(buf==0) return 0;
        // CapsQueryType (nofun): Video=0, Input=1, Sound=2, Communication=3, System=4
        switch(queryType){
            case 0:  // Video: {u16 size,flags,width,height}. flags = VideoCapsFlag.All (Render3D|ChangingOrientation)
                vm.write16(buf+0, 8);
                vm.write16(buf+2, 0x3000);
                vm.write16(buf+4, scr.w);
                vm.write16(buf+6, scr.h);
                return 1;
            case 4:  // System: {u16 size,flags, u32 deviceId, u32 vendorFlags}. deviceId=(model<<16)|vendor
                vm.write16(buf+0, 12);
                vm.write16(buf+2, 0x25);                       // Unicode|Vibrate|OpenURL
                vm.write32(buf+4, (deviceModel<<16)|deviceVendor);
                vm.write32(buf+8, 0);
                return 1;
            case 2:  // Sound: {u16 size,flags, u32 config}. vendor=3 -> HC2 liga o som (pool[65]=1)
                vm.write16(buf+0, 8);
                vm.write16(buf+2, 0xFFFF);
                vm.write32(buf+4, 3);
                return 1;
            case 3:  // Communication: {u16 size,flags}
                vm.write16(buf+0, 8);
                vm.write16(buf+2, 0);
                vm.write32(buf+4, 0);
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
        if(type==7){                                  // Resource (dados do .mpn, somente leitura)
            int resNum = mode>>>16;
            if(resNum >= vm.exe.resourceCount()) return -1;
            int off = vm.exe.resourceOffset(resNum), len = vm.exe.resourceLength(resNum);
            byte[] data = new byte[len];
            System.arraycopy(vm.exe.all, off, data, 0, len);
            int fd = nextFd++;
            streamData.put(fd, data); streamPos.put(fd, new int[]{0});
            return fd;
        }
        // type 0 = File: save game em disco ("resume" = estado do jogo, "highscore" = recordes)
        String nm = readStr(namePtr, false);
        if(SAVELOG) System.out.println("[SAVE] open File '"+nm+"' mode=0x"+Integer.toHexString(mode)
            +((mode&SF_WRITE)!=0?" WRITE":" read"));
        return fileOpen(nm, mode);
    }

    // Flags de stream (StreamFlags.cs do nofun): Read=0x100, Write=0x200, Binary=0x400,
    // Create=0x800, Trunc=0x1000, MustNotExistBefore=0x2000.
    static final int SF_READ=0x100, SF_WRITE=0x200, SF_CREATE=0x800, SF_TRUNC=0x1000;

    // Abre um save em disco (pasta saveDir). Devolve o conteudo atual num buffer editavel; gravaveis
    // sao persistidos JA na abertura e a cada write (nao dependem do vStreamClose -> save robusto
    // mesmo se o jogo/janela fechar sem fechar o stream). Semantica = nofun VMWrapperIStream.
    int fileOpen(String name, int mode){
        boolean write  = (mode & SF_WRITE)!=0;
        boolean create = (mode & SF_CREATE)!=0;
        boolean trunc  = (mode & SF_TRUNC)!=0;
        java.io.File f = saveFile(name);
        byte[] data;
        if(create || trunc){             // Create/Trunc: comeca vazio (regrava do zero)
            data = new byte[0];
        } else if(f.isFile()){           // arquivo existe: carrega conteudo
            try { data = readAllBytes(f); } catch(Exception e){ return -1; }
        } else if(write){                // gravavel sem existir -> OpenOrCreate (nofun): cria vazio
            data = new byte[0];
        } else {                         // leitura de save inexistente -> falha (jogo sabe: sem "Continue")
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
        } catch(Exception e){ System.err.println("[save] falha ao gravar "+f+": "+e); }
    }

    // mapeia o nome logico do jogo ("resume"/"highscore") p/ um arquivo seguro em saveDir
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
            if(trace) System.out.println("  [save] gravado "+f+" ("+(data!=null?data.length:0)+" bytes)");
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
            MidiBridge.play(bytes, (flags & 0x100)!=0);
            return 1;
        } catch(Throwable e){ return 0; }   // sem dispositivo MIDI: nao trava o jogo
    }
    public void stopSound(){
        try { MidiBridge.stop(); } catch(Throwable e){}
        try { if(vsound!=null) vsound.dispose(); } catch(Throwable e){}
    }

    // ---------------- save state (snapshot da VM: funciona p/ QUALQUER jogo) ----------------
    static final int STATE_MAGIC = 0x4D505631;    // "MPV1" (antigo, sem tick)
    static final int STATE_MAGIC2 = 0x4D505632;   // "MPV2" (com base de tempo)
    static final int STATE_MAGIC3 = 0x4D505633;   // "MPV3" (+framebuffer, p/ fundo persistente das corridas)
    public void saveState(java.io.File f) throws java.io.IOException {
        java.io.DataOutputStream o = new java.io.DataOutputStream(
            new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)));
        try {
            o.writeInt(STATE_MAGIC3);
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
        } finally { o.close(); }
    }
    public void loadState(java.io.File f) throws java.io.IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(
            new java.io.BufferedInputStream(new java.io.FileInputStream(f)));
        try {
            int magic=in.readInt();
            if(magic!=STATE_MAGIC && magic!=STATE_MAGIC2 && magic!=STATE_MAGIC3) throw new java.io.IOException("estado invalido");
            int nr=in.readInt(); for(int i=0;i<nr;i++){ int v=in.readInt(); if(i<vm.R.length) vm.R[i]=v; }
            int ml=in.readInt(); byte[] m=new byte[ml]; in.readFully(m);
            System.arraycopy(m,0,vm.mem,0,Math.min(ml,vm.mem.length));
            nextHeap=in.readInt(); foreColor=in.readInt(); backColor=in.readInt(); transferMode=in.readInt();
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
            if(magic==STATE_MAGIC2 || magic==STATE_MAGIC3){ long savedTick=in.readLong(); tick0=System.currentTimeMillis()-savedTick; }
            else { tick0=System.currentTimeMillis()-3600000L; }
            if(magic==STATE_MAGIC3){                          // restaura o framebuffer (fundo persistente)
                int fl=in.readInt();
                for(int i=0;i<fl;i++){ int v=in.readInt(); if(i<scr.fb.length) scr.fb[i]=v; }
            }
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
