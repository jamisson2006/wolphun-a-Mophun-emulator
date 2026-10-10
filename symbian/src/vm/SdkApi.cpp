/* SdkApi.cpp - parte do Sdk: o DESPACHO das chamadas do SDK mophun.
 * Port do switch(name) de src/wolphun/vm/Sdk.java, agora por id resolvido.
 * Ver Sdk.h / Sdk.cpp (tabela de nomes) / SdkDraw.cpp / SdkIO.cpp.
 */
#include "Sdk.h"

/* vmgp.h: comando do vSysCtl que liga o motor de vibracao */
static const int KSysCtlSetVibrate = 1;
#include "Pip2.h"
#include "Screen.h"
#include "SdkIds.h"
#include "Renderer3D.h"

/* seno/cosseno em ponto fixo 16.16 sem libm: tabela de 1/4 de onda, 256 passos por
   volta (o SDK usa angulo de 0..4095 = 360 graus; ang2rad do Renderer3D). */
static const i32 kSin16[65] = {   /* sin(i * 90/64 graus) * 65536 */
        0,  1608,  3215,  4821,  6424,  8022,  9616, 11204,
    12785, 14359, 15924, 17479, 19024, 20557, 22078, 23586,
    25080, 26558, 28020, 29466, 30893, 32303, 33692, 35062,
    36410, 37736, 39040, 40320, 41576, 42806, 44011, 45190,
    46341, 47464, 48559, 49624, 50660, 51665, 52639, 53581,
    54491, 55368, 56212, 57022, 57798, 58538, 59244, 59914,
    60547, 61145, 61705, 62228, 62714, 63162, 63572, 63944,
    64277, 64571, 64827, 65043, 65220, 65358, 65457, 65516,
    65536
};
/* ang: unidades do SDK (4096 = volta completa) */
static i32 sin16(int ang){
    int a = ang & 4095;
    int q = a >> 10;            /* quadrante 0..3 */
    int i = a & 1023;           /* 0..1023 dentro do quadrante */
    int idx = (i * 64) / 1024;  /* 0..63 */
    int rem = (i * 64) % 1024;
    i32 v0, v1;
    switch (q){
        case 0: v0 = kSin16[idx];      v1 = kSin16[idx+1];      break;
        case 1: v0 = kSin16[64-idx];   v1 = kSin16[64-idx-1];   break;
        case 2: v0 = -kSin16[idx];     v1 = -kSin16[idx+1];     break;
        default:v0 = -kSin16[64-idx];  v1 = -kSin16[64-idx-1];  break;
    }
    return v0 + (i32)(((i64)(v1-v0) * rem) / 1024);   /* interpolacao linear */
}
static i32 cos16(int ang){ return sin16(ang + 1024); }

void Sdk::invokeId(int id){
    switch (id){
    /* ---------- memoria ---------- */
    case A_alloc:      vm->ret(alloc(vm->arg(0), false)); break;
    case A_allocClear: vm->ret(alloc(vm->arg(0), true));  break;
    case A_free:       freePtr(vm->arg(0)); vm->ret(0);   break;
    /* vMemFree/vMaxFreeBlock/vMemAvail: bytes livres = bump restante + free list. O Da
       Vinci Code chama vMemFree() antes de carregar o nivel 3D (retornar 0 dava tela
       preta); o Deep Abyss compara antes/depois de alocar+liberar temporarios e sem
       reclamar o valor caia -> "MEMORYLEAK". */
    case A_memFree:    vm->ret((vm->heapEnd - nextHeap) + freeBytes); break;

    /* ---------- sistema ---------- */
    case A_getTickCount:
        vm->ret((int)((scr && scr->host) ? (scr->host->TickMs() - tick0) : 0));
        break;
    /* segundos desde 1970 UTC. O Football Pro usa p/ seed/relogio; cair no default 0
       quebrava a logica de tempo. */
    case A_getTime:
        vm->ret((int)((scr && scr->host) ? scr->host->EpochSeconds() : 0));
        break;
    case A_getTimeDate: writeDateTime(vm->arg(0)); vm->ret(0); break;
    /* vGetRandom: 16 bits (VRAND_MAX=0xffff) devolvidos com SINAL ESTENDIDO p/ 32 bits,
       como o emulador oficial. Devolvendo so 15 bits [0,32767] (nunca com bit 15), jogos
       que checam o bit-de-sinal p/ um coin-flip (Honey Cave 1: dano/recuperacao de vida)
       NUNCA disparavam. O ARGUMENTO e IGNORADO (os jogos fazem o proprio modulo; modar
       aqui tornava o modulo do jogo um no-op -> Bouncy travava). */
    case A_getRandom:
        rngState = rngState*6364136223846793005ULL + 1442695040888963407ULL;
        vm->ret((i32)(i16)(u16)(rngState >> 33));
        break;
    case A_setRandom:  rngState = (u32)vm->arg(0); break;
    case A_getCaps:    vm->ret(getCaps(vm->arg(0), vm->arg(1))); break;
    /* vSysCtl(cmd, arg). SYSCTL_SETVIBRATE(1): bit0 = liga/desliga, bits 16-31 = duracao
       em ms -- e o que o macro vSetVibrate(on,time) do vmgp.h monta:
       vSysCtl(1, on | (time<<16)). O Honey Cave 2 usa isso ao tomar dano: medido na
       versao Android, manda cmd=1 arg=0x012C0001, ou seja ligar por 300 ms.
       Era no-op aqui: o aparelho nunca vibrava, embora o vGetCaps ja ANUNCIASSE o bit
       de vibracao (0x25 em SdkIO.cpp). Os demais comandos continuam ignorados. */
    case A_sysCtl:
        if (vm->arg(0) == KSysCtlSetVibrate && vibra)
        {
            const u32 a  = (u32)vm->arg(1);
            const int ms = (int)((a >> 16) & 0xffff);
            if (a & 1) vibra->Vibrate(ms > 0 ? ms : 200);  /* ligar sem duracao: pulso curto */
            else       vibra->StopVibra();
        }
        vm->ret(0);
        break;
    case A_terminate:  vm->stopped = true; break;

    /* ---- Tarefas (multitarefa cooperativa; semantica = nofun) ---- */
    case A_setStackSize: {
        int v = (vm->arg(0)+3) & ~3;
        taskStackSize = (v < 4096) ? 4096 : v;
        vm->ret(taskStackSize);
        break;
    }
    case A_createTask: {
        int entry=vm->arg(0), p0=vm->arg(1), p1=vm->arg(2), p2=vm->arg(3);
        int blk = alloc(taskStackSize, false);
        if (blk==0){ vm->ret(-1); break; }
        int spTop = blk + taskStackSize;            /* pilha cresce p/ baixo */
        vm->ret(vm->createTask(entry, p0, p1, p2, spTop, blk));
        break;
    }
    case A_disposeTask: vm->disposeTask(vm->arg(0)); vm->ret(0); break;
    case A_taskAlive:   vm->ret(vm->taskAlive(vm->arg(0)) ? 1 : 0); break;
    case A_thisTask:    vm->ret(vm->thisTaskId()); break;
    case A_send: {
        Pip2::Task* t = vm->findTaskPub(vm->arg(0));
        if (t) t->recv = vm->arg(1);
        vm->ret(0); break;
    }
    case A_receiveAny: {
        Pip2::Task* t = vm->findTaskPub(vm->arg(0));
        vm->ret(t ? t->recv : 0); break;
    }
    case A_receive: {
        Pip2::Task* t = vm->currentTaskPub();
        vm->ret(t ? t->recv : 0); break;
    }

    /* ---- utilitarios ---- */
    case A_abs: { int v=vm->arg(0); vm->ret(v<0?-v:v); break; }
    /* ordem de bytes de rede (init de comunicacao; Martial Arts 3D) */
    case A_htons: { int v=vm->arg(0)&0xffff; vm->ret(((v&0xff)<<8)|((v>>8)&0xff)); break; }
    case A_htonl: { u32 v=(u32)vm->arg(0);
        vm->ret((i32)(((v&0xff)<<24)|(((v>>8)&0xff)<<16)|(((v>>16)&0xff)<<8)|((v>>24)&0xff)));
        break; }

    /* ---- strings UNICODE (pip_wchar_t = 16 bits) ----
       O SDK documenta "Return: Pointer to the terminating null-character", confirmando
       a convencao ponteiro-FIM de toda a familia de strings do mophun. */
    case A_strLenU: { int p=vm->arg(0), n=0; while (vm->read16(p+n*2)!=0) n++; vm->ret(n); break; }
    case A_strCpyU: { int dst=vm->arg(0), src=vm->arg(1), i=0, w;
        for (;;){ w=vm->read16(src+i*2); vm->write16(dst+i*2, w); if (w==0) break; i++; }
        vm->ret(dst+i*2); break; }
    case A_strToU:  { int dst=vm->arg(0), src=vm->arg(1), i=0, b;
        for (;;){ b=vm->read8(src+i); vm->write16(dst+i*2, b); if (b==0) break; i++; }
        vm->ret(dst+i*2); break; }
    case A_streamReady: vm->ret(streamGet(vm->arg(0)) ? vm->arg(1) : 0); break;
    case A_strCmp: {
        int a=vm->arg(0), b=vm->arg(1), i=0, r=0;
        for (;;){ int ca=vm->read8(a+i), cb=vm->read8(b+i);
                  if (ca!=cb){ r=ca-cb; break; } if (ca==0) break; i++; }
        vm->ret(r); break;
    }

    /* ---------- identidade / DRM / versao (valores do nofun) ---------- */
    case A_checkCert:    vm->ret(1); break;
    case A_checkIMEI:    vm->ret(1); break;
    case A_checkNetwork: vm->ret(1); break;
    case A_getVMGPInfo:  vm->ret((2<<16)|apiMinor); break;      /* API v2.xx */
    case A_uid:          vm->ret((i32)0xDEADBEEF); break;
    case A_frameTickCount: vm->ret((int)scr->flips); break;     /* frames = flips */
    case A_yieldToSystem:  vm->ret(0); break;
    case A_waitVBL:        vm->ret(0); break;                   /* pacing no vFlipScreen */

    /* ---------- entrada ---------- */
    case A_testKey:       vm->ret(testKey(vm->arg(0))); break;
    case A_scanKeys:      vm->ret(scanKeys()); break;
    case A_getButtonData: vm->ret(scr->getButtons()); break;
    /* vBeep passa pelo mixer: ha jogos (Mooktown Tennis, Alpine Ski) cujo audio e SO
       isto, e eles nem chamam vSoundInit. */
    case A_beep:
        if (soundEnabled){
            vsound.beep(vm->arg(0), vm->arg(1));
            if (audio) audio->Start();
        }
        break;

    /* ---------- graficos ---------- */
    case A_clearScreen: scr->fill(getColor(vm->arg(0))); vm->ret(0); break;
    /* vFlipScreen: apresenta e limpa o z-buffer do frame. O z-buffer do mophun e
       scratch por-frame, mas jogos como Rally Pro Contest NUNCA chamam vSetZBuffer nem
       vClearScreen -- sem limpar aqui, o z acumulava a menor profundidade ja vista por
       pixel e a geometria nova falhava o z-test e SUMIA ao mover a camera. */
    case A_flipScreen:  scr->present(); if (r3d) r3d->clearDepthPerFrame(); vm->ret(0); break;
    case A_setForeColor: foreColor = vm->arg(0); break;   /* cor crua (idx ou RGB555 se bit31) */
    case A_setBackColor: backColor = vm->arg(0); break;
    case A_setPaletteEntry: scr->setPaletteRgb555(vm->arg(0), vm->arg(1)); break;
    case A_getPaletteEntry: vm->ret(Screen::argbToRgb555(scr->palette[vm->arg(0)&0xff])); break;
    case A_setPalette: setPalette(vm->arg(0), vm->arg(1), vm->arg(2)); break;
    /* vSetTransferMode DEVOLVE o modo ANTERIOR (vmgp.h). Nao devolver nada deixava o
       padrao "old=vSetTransferMode(X); ...; vSetTransferMode(old);" restaurar lixo (na
       pratica 0) e o modo ficava preso em MODE_BLOCK -- no HoneyCave 2 o aro branco do
       icone de mel ganhava mordidas pretas. */
    case A_setTransferMode: { int prev=transferMode; transferMode=vm->arg(0); vm->ret(prev); break; }
    case A_setClipWindow:
        scr->setClip((i16)vm->arg(0),(i16)vm->arg(1),(i16)vm->arg(2),(i16)vm->arg(3));
        break;
    case A_fillRect: fillRect((i16)vm->arg(0),(i16)vm->arg(1),(i16)vm->arg(2),(i16)vm->arg(3)); break;
    case A_drawLine: drawLine((i16)vm->arg(0),(i16)vm->arg(1),(i16)vm->arg(2),(i16)vm->arg(3)); break;
    /* vPlot(x,y): UM pixel na foreColor, "The operation is clipped" (vmgp.h). Era no-op:
       o Hyper Apocalypse desenha TODO o campo de estrelas ponto a ponto (144.480 vPlot
       numa partida) -> fundo do espaco 100% preto. */
    case A_plot: px((i16)vm->arg(0), (i16)vm->arg(1), getColor(foreColor)); break;
    case A_getPixel: vm->ret(0); break;
    case A_updateSpriteMap: drawMap(); drawSpriteSlots(); break;

    /* ---------- sprites ---------- */
    case A_spriteInit: {
        delete[] slotSprite; delete[] slotX; delete[] slotY;
        spriteCount = vm->arg(0)&0xff;
        int n = spriteCount>0 ? spriteCount : 1;
        slotSprite = new int[n]; slotX = new i16[n]; slotY = new i16[n];
        for (int i=0;i<n;i++){ slotSprite[i]=0; slotX[i]=0; slotY[i]=0; }
        vm->ret(1); break;
    }
    case A_spriteClear: if (slotSprite) for (int i=0;i<spriteCount;i++) slotSprite[i]=0; break;
    case A_spriteSet: {
        int s = vm->arg(0)&0xff;
        if (slotSprite && s<spriteCount){
            slotSprite[s]=vm->arg(1);
            slotX[s]=(i16)vm->arg(2);
            slotY[s]=(i16)vm->arg(3);
        }
        break;
    }
    case A_updateSprite: drawSpriteSlots(); break;
    case A_drawObject:   drawObject((i16)vm->arg(0),(i16)vm->arg(1),vm->arg(2)); break;
    case A_drawTile:     drawTile(vm->arg(0),vm->arg(1),(i16)vm->arg(2),(i16)vm->arg(3)); break;
    case A_updateSpriteRotate: break;
    case A_spriteBoxCollision: vm->ret(spriteBoxCollision(vm->arg(0), vm->arg(1)&0xff, vm->arg(2)&0xff)); break;
    case A_spriteCollision:    vm->ret(spriteCollision(vm->arg(0)&0xff, vm->arg(1)&0xff, vm->arg(2)&0xff)); break;

    /* ---------- tilemap ---------- */
    case A_mapInit: mapHeaderPtr=vm->arg(0); mhValid=false; mapSync(); vm->ret(1); break;
    /* vMapHeaderUpdate: re-aponta o motor de tiles p/ outro header, igual ao vMapInit.
       Sem isto o Shooting Star trocava de mapa e seguiamos lendo o header ANTIGO (ja
       liberado, mapData=0) -> drawMap abortava, o fundo nunca era repintado e o frame
       anterior ficava na tela (smearing). */
    case A_mapHeaderUpdate: mapHeaderPtr=vm->arg(0); mhValid=false; mapSync(); vm->ret(1); break;
    case A_mapDispose: mapHeaderPtr=0; mhValid=false; break;
    case A_updateMap:  drawMap(); break;
    /* escreve na struct do jogo (se ainda viva) E na copia -- e a copia que o drawMap usa */
    case A_mapSetXY:
        if (mapHeaderPtr!=0){
            vm->write16(mapHeaderPtr+12, vm->arg(0));
            vm->write16(mapHeaderPtr+14, vm->arg(1));
        }
        mhXPos = vm->arg(0)&0xffff;
        mhYPos = vm->arg(1)&0xffff;
        break;
    /* args sao BYTES no nofun -> mascarar &0xFF (corrige x "negativo" ao virar a esquerda) */
    case A_mapGetAttribute: vm->ret(mapAttr(vm->arg(0)&0xff, vm->arg(1)&0xff)); break;
    case A_mapGetTile:      vm->ret(mapTile(vm->arg(0)&0xff, vm->arg(1)&0xff)); break;
    /* ABI canonica x=P0,y=P1,v=P2 (confirmado por disassembly: MOVB a0=x; MOVB a1=y;
       LDQ a2=v; calll). Mascarar &0xFF pois o jogo carrega x/y com MOVB (so o byte baixo;
       os bits altos sao lixo do valor anterior do registrador). */
    case A_mapSetTile:      mapSetTile(vm->arg(0)&0xff, vm->arg(1)&0xff, vm->arg(2)&0xff); break;
    case A_mapSetAttribute: mapSetAttr(vm->arg(0)&0xff, vm->arg(1)&0xff, vm->arg(2)&0xff); break;

    /* ---------- texto ---------- */
    case A_selectFont:    scr->selectFont(vm->arg(0), vm->arg(1)); vm->ret(vm->arg(1)); break;
    case A_setActiveFont: vm->ret(setActiveFont(vm->arg(0))); break;
    /* vTextOut/vTextOutU desenham com a fonte DO SISTEMA, nao com a fonte ativa do
       jogo. Ja tentei rotear para vPrintImpl e e ERRADO: o Honey Cave 2 ingles, que
       hoje sai perfeito, virou lixo ilegivel. Documentado aqui para nao se tentar de
       novo. (O HC2 RUSSO sai errado por outro motivo, ainda em aberto: ele manda
       codigos proprios -- CP1251 menos 0x90 -- e chama vSelectFont antes de cada
       string.) */
    case A_textOut: {
        char b[512];
        scr->drawText((i16)vm->arg(0), (i16)vm->arg(1),
                      readStr(vm->arg(2), false, b, (int)sizeof(b)), getColor(foreColor));
        vm->ret(0); break;
    }
    case A_textOutU: {
        char b[512];
        scr->drawText((i16)vm->arg(0), (i16)vm->arg(1),
                      readStr(vm->arg(2), true, b, (int)sizeof(b)), getColor(foreColor));
        vm->ret(0); break;
    }
    case A_print: {
        char b[512];
        vPrintImpl(vm->arg(0), (i16)vm->arg(1), (i16)vm->arg(2),
                   readStr(vm->arg(3), false, b, (int)sizeof(b)));
        vm->ret(0); break;
    }
    /* extent = (altura << 16) | largura (o jogo le srli #16 p/ altura, &0xFFFF p/ largura) */
    case A_textExtent:  { char b[512]; vm->ret(textExtent(readStr(vm->arg(0), false, b, (int)sizeof(b)))); break; }
    case A_textExtentU: { char b[512]; vm->ret(textExtent(readStr(vm->arg(0), true,  b, (int)sizeof(b)))); break; }
    case A_charExtent:  { char b[2]; b[0]=(char)(vm->arg(0)&0xff); b[1]=0; vm->ret(textExtent(b)); break; }
    /* vMsgBox(flags, msg, ...): flags&0x80 = tem titulo. Sendo ellipsis, o TITULO vem na
       PILHA (MophunAsmRef 1.3.1), nao em $p2 -- antes liamos um ponteiro errado. */
    case A_msgBox: case A_msgBoxU: {
        bool uni = (id==A_msgBoxU);
        char tb[256]; char mb[512];
        const char* title = 0;
        if ((vm->arg(0) & 0x80)!=0)
            title = readStr(vm->read32(vm->getReg(Pip2::SP)), uni, tb, (int)sizeof(tb));
        vm->ret(scr->messageBox(title, readStr(vm->arg(1), uni, mb, (int)sizeof(mb))));
        break;
    }

    /* ---------- string ---------- */
    case A_strLen: { int p=vm->arg(0), n=0; while (vm->read8(p+n)!=0) n++; vm->ret(n); break; }
    /* vStrCpy devolve o PONTEIRO PARA O FIM (o '\0' escrito), NAO o inicio como o strcpy
       do C. Os jogos encadeiam: o printf interno do Da Vinci Code faz out=vStrCpy(out,arg)
       e continua escrevendo a partir do retorno; devolvendo o inicio, o texto seguinte
       SOBRESCREVIA o que acabou de ser copiado. */
    case A_strCpy: { int dst=vm->arg(0), src=vm->arg(1), i=0, b;
        for (;;){ b=vm->read8(src+i); vm->write8(dst+i,b); if (b==0) break; i++; }
        vm->ret(dst+i); break; }
    /* vStrCat(s1,s2): concatena; devolve o ponteiro para o '\0' final, como o vStrCpy */
    case A_strCat: { int d1=vm->arg(0), s2=vm->arg(1), e=0;
        while (vm->read8(d1+e)!=0) e++;
        int i=0, b2;
        for (;;){ b2=vm->read8(s2+i); vm->write8(d1+e+i,b2); if (b2==0) break; i++; }
        vm->ret(d1+e+i); break; }
    case A_itoa: vm->ret(itoa(vm->arg(0),vm->arg(1),true,  vm->arg(2)&0xff, vm->arg(3)&0xff)); break;
    case A_utoa: vm->ret(itoa(vm->arg(0),vm->arg(1),false, vm->arg(2)&0xff, vm->arg(3)&0xff)); break;
    case A_atoi: vm->ret(vatoi(vm->arg(0), vm->arg(1))); break;
    case A_sprintf:   vm->ret(sprintfReg(vm->arg(0), vm->arg(1))); break;
    case A_sprintfVa: vm->ret(sprintfVa(vm->arg(0), vm->arg(1), vm->arg(2))); break;

    /* ---------- descompressao ---------- */
    /* vDecompress(source, dest, streamHandle, readBufSize): source==0 => do stream */
    case A_decompress:
        vm->ret(vm->arg(0)==0 ? decompressStream(vm->arg(2), vm->arg(1))
                              : decompress(vm->arg(0), vm->arg(1)));
        break;
    case A_decompHdr: vm->ret(decompHdr(vm->arg(0), vm->arg(1))); break;

    /* ---------- streams ---------- */
    case A_streamOpen:  vm->ret(streamOpen(vm->arg(0), vm->arg(1))); break;
    case A_streamRead:  vm->ret(streamRead(vm->arg(0), vm->arg(1), vm->arg(2))); break;
    case A_streamWrite: vm->ret(streamWrite(vm->arg(0), vm->arg(1), vm->arg(2))); break;
    case A_streamSeek:  vm->ret(streamSeek(vm->arg(0), vm->arg(1), vm->arg(2))); break;
    case A_streamClose: streamClose(vm->arg(0)); vm->ret(0); break;
    case A_playResource: vm->ret(playResource(vm->arg(0), vm->arg(1), vm->arg(2))); break;

    /* ---------- som ---------- */
    /* Modulo VSound REAL (mixer proprio, ver VSound.cpp). A casca so precisa escoar
       as amostras; o mapeamento abaixo e o mesmo do emulador de PC. */
    case A_soundInit:
        vsound.enabled = soundEnabled;
        vm->ret(soundEnabled ? vsound.init() : -1);
        if (soundEnabled && audio) audio->Start();
        break;
    case A_soundGetHandle:     vm->ret(soundEnabled ? vsound.getHandle(vm->arg(0)) : -1); break;
    case A_soundCtrl:          vm->ret(vsound.ctrl(vm->arg(0), vm->arg(1), 0)); break;
    case A_soundCtrlEx:        vm->ret(vsound.ctrl(vm->arg(0), vm->arg(1), vm->arg(2))); break;
    case A_soundDispose:       vm->ret(vsound.dispose()); if (audio) audio->Stop(); break;
    case A_soundDisposeHandle: vm->ret(vsound.disposeHandle(vm->arg(0))); break;
    case A_soundUpload:        vm->ret(0); break;
    case A_musicInit: vm->ret(0); break;   /* SND_OK: 1 fazia o jogo achar que falhou */
    case A_musicLoad: vm->ret(1); break;   /* handle dummy (sem tocar, sem crash) */
    case A_musicNoop: vm->ret(0); break;

    /* ---------- misc ---------- */
    case A_copyRect:     copyRect(vm->arg(0), vm->arg(1), vm->arg(2), vm->arg(3)); break;
    case A_findRGBIndex: vm->ret(findRGBIndex(vm->arg(0))); break;
    case A_setDisplayWindow: break;
    case A_getSupportedRenderStates: vm->ret((i32)0xFFFFFFFF); break;  /* permissivo */

    /* ---- ponto fixo 16.16 (familia "16"/"F16"; o SDK 2.5 nao documenta, veio depois).
       O resto do SDK usa base 2^14; estas usam 2^16. Sem elas caiam no default (=0) e
       TODA a matematica de posicao do jogo virava zero (Worms World Party chama vMul16
       ~9 mil vezes). ---- */
    case A_mul16: vm->ret((i32)(((i64)vm->arg(0) * vm->arg(1)) >> 16)); break;
    case A_div16: { int b=vm->arg(1); vm->ret(b==0 ? 0 : (i32)((((i64)vm->arg(0))<<16)/b)); break; }
    case A_sinF16: vm->ret(sin16(vm->arg(0))); break;
    case A_cosF16: vm->ret(cos16(vm->arg(0))); break;
    /* vLimit(valor, min, max): satura no intervalo */
    case A_limit: { int v=vm->arg(0), lo=vm->arg(1), hi=vm->arg(2);
        vm->ret(v<lo ? lo : (v>hi ? hi : v)); break; }

    /* ---------- 3D: pipeline VMGP3D (modulo Renderer3D) ----------
       r3d e criado pela casca quando o jogo e 3D (importa vInit3D). Se nao houver
       renderer (jogo 2D que toca uma dessas por engano), devolve valor neutro. */
    case A_init3D: break;
    case A_setViewport:    if(r3d) r3d->setViewport(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)); break;
    case A_setRenderState: if(r3d) r3d->setRenderState(vm->arg(0),vm->arg(1)); break;
    case A_setZBuffer:     if(r3d) r3d->clearDepth(); break;
    case A_setTexture:     vm->ret(r3d ? r3d->setTexture(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)) : 0); break;
    case A_createTexture:  vm->ret(r3d ? r3d->createTexture(vm->arg(0)) : 0); break;
    case A_setActiveTexture: if(r3d) r3d->setActiveTexture(vm->arg(0)); vm->ret(0); break;
    case A_setFunctionalTexture: vm->ret(0); break;   /* texturas procedurais: raras, no-op (= nofun) */
    case A_deleteTexture:  if(r3d) r3d->deleteTexture(vm->arg(0)); vm->ret(0); break;
    case A_deleteAllTextures: if(r3d) r3d->deleteAllTextures(); vm->ret(0); break;
    case A_freeTexture:    vm->ret(0); break;         /* no-op (igual ao nofun) */
    case A_renderPrimitive:        if(r3d) r3d->renderPrimitive(vm->arg(0),vm->arg(1)); vm->ret(1); break;
    case A_renderPrimitiveIndexed: if(r3d) r3d->renderPrimitiveIndexed(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)); vm->ret(1); break;
    case A_drawPolygon:    if(r3d) r3d->drawPolygon(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_drawBillboard:  if(r3d) r3d->drawBillboard(vm->arg(0)); break;
    case A_drawFlatPolygon: fillFlatPoly(vm->arg(0)); break;   /* 2D flat tri (foreColor) */
    case A_getInteger:
        if (vm->arg(1)!=0) vm->write32(vm->arg(1), r3d ? r3d->getInteger(vm->arg(0)) : 0);
        vm->ret(1); break;
    /* matrizes */
    case A_setMatrixMode:      if(r3d) r3d->setMatrixMode(vm->arg(0)); break;
    case A_matrixIdentity:     if(r3d) r3d->matrixIdentity(); break;
    case A_matrixSetCurrent:   if(r3d) r3d->matrixSetCurrent(vm->arg(0)); break;
    case A_matrixGetCurrent:   if(r3d) r3d->matrixGetCurrent(vm->arg(0)); break;
    case A_matrixMultiply:     if(r3d) r3d->matrixMultiply(vm->arg(0)); break;
    case A_matrixSetProjection:if(r3d) r3d->matrixSetProjection(vm->arg(0)); break;
    case A_matrixSetLight:     if(r3d) r3d->matrixSetLight(vm->arg(0)); break;
    case A_matrixTranslate:    if(r3d) r3d->matrixTranslate(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_matrixScale:        if(r3d) r3d->matrixScale(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_matrixRotateX:      if(r3d) r3d->matrixRotateX((i16)vm->arg(0)); break;
    case A_matrixRotateY:      if(r3d) r3d->matrixRotateY((i16)vm->arg(0)); break;
    case A_matrixRotateZ:      if(r3d) r3d->matrixRotateZ((i16)vm->arg(0)); break;
    case A_matrixRotateVector: if(r3d) r3d->matrixRotateVector(vm->arg(0),(i16)vm->arg(1)); break;
    case A_matrixPerspective:  if(r3d) r3d->matrixPerspective(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)); break;
    case A_matrixOrtho:        if(r3d) r3d->matrixOrtho(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)); break;
    case A_matrixLookAt:       if(r3d) r3d->matrixLookAt(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_matrixInvert:       if(r3d) r3d->matrixInvert(); break;
    case A_matrixTranspose:    if(r3d) r3d->matrixTranspose(); break;
    case A_matrixMultiply3x3:  if(r3d) r3d->matrixMultiply3x3(vm->arg(0)); break;
    /* vetores / frustum / colisao */
    case A_vectorAdd:       if(r3d) r3d->vectorAdd(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_vectorSub:       if(r3d) r3d->vectorSub(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_vectorMul:       if(r3d) r3d->vectorMul(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_vectorNormalize: if(r3d) r3d->vectorNormalize(vm->arg(0)); break;
    case A_crossProduct:    if(r3d) r3d->crossProduct(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_dotProduct:      vm->ret(r3d ? r3d->dotProduct(vm->arg(0),vm->arg(1)) : 0); break;
    case A_vectorTransformV3: if(r3d) r3d->vectorTransformV3(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_vectorTransformV4: if(r3d) r3d->vectorTransformV4(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_vectorProjectV3:   if(r3d) r3d->vectorProjectV3(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_vectorProjectV4:   if(r3d) r3d->vectorProjectV4(vm->arg(0),vm->arg(1),vm->arg(2)); break;
    case A_boxInViewFrustum:  vm->ret(r3d ? r3d->boxInViewFrustum(vm->arg(0)) : 1); break;
    case A_createPlaneFromPoly: if(r3d) r3d->createPlaneFromPoly(vm->arg(0),vm->arg(1)); break;
    case A_collisionPointBox: vm->ret(r3d ? r3d->collisionPointBox(vm->arg(0),vm->arg(1)) : 0); break;
    case A_collisionBoxBox:   vm->ret(r3d ? r3d->collisionBoxBox(vm->arg(0),vm->arg(1)) : 0); break;
    case A_collisionVectorPoly:  vm->ret(r3d ? r3d->collisionVectorPoly(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)) : 0); break;
    case A_collisionVectorPlane: vm->ret(r3d ? r3d->collisionVectorPlane(vm->arg(0),vm->arg(1),vm->arg(2)) : 0); break;
    /* iluminacao T&L (so tem efeito com LIGHTINGENABLE / estado 17) */
    case A_setLight:        if(r3d) r3d->setLight(vm->arg(0), vm->arg(1)); break;
    case A_setMaterial:     if(r3d) r3d->setMaterial(vm->arg(0)); break;
    case A_setMaterial2:    if(r3d) r3d->setMaterial2(vm->arg(0)); break;
    case A_setAmbientLight: if(r3d) r3d->setAmbientLight(vm->arg(0)); break;
    case A_setCameraPos:    if(r3d) r3d->setCameraPos(vm->arg(0)); break;
    case A_resetLights:     if(r3d) r3d->resetLights(); break;
    case A_lightPoint:      if(r3d) r3d->lightPoint(vm->arg(0)); break;
    case A_setFogColor:     if(r3d) r3d->setFogColor(vm->arg(0)); break;
    case A_getZBufferValue: vm->ret(r3d ? r3d->getZBufferValue(vm->arg(0),vm->arg(1)) : 0); break;
    case A_vectorArrayAdd:   if(r3d) r3d->vectorArrayAdd(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)); break;
    case A_vectorArrayDelta: if(r3d) r3d->vectorArrayDelta(vm->arg(0),vm->arg(1),vm->arg(2),vm->arg(3)); break;
    /* matematica de ponto fixo base 2^14 (familia 3D) */
    case A_pow:  vm->ret(r3d ? r3d->vPow(vm->arg(0),vm->arg(1)) : 0); break;
    case A_mul:  vm->ret(r3d ? r3d->vMul(vm->arg(0),vm->arg(1)) : 0); break;
    case A_div:  vm->ret(r3d ? r3d->vDiv(vm->arg(0),vm->arg(1)) : 0); break;
    case A_sqrt: vm->ret(r3d ? r3d->vSqrt(vm->arg(0)) : 0); break;
    case A_sin:  vm->ret(r3d ? r3d->vSin(vm->arg(0)) : 0); break;
    case A_cos:  vm->ret(r3d ? r3d->vCos(vm->arg(0)) : 0); break;
    case A_tan:  vm->ret(r3d ? r3d->vTan(vm->arg(0)) : 0); break;

    default:
        stubCalls++;
        vm->stubCalls++;
        vm->ret(0);
        break;
    }
}
