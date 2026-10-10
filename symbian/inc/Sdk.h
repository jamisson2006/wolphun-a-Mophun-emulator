/* Sdk.h - camada de APIs do SDK mophun (vGfx*, vSprite*, vMap*, ...).
 * Port de src/wolphun/vm/Sdk.java. Cada CALLl p/ um import cai aqui via
 * invoke(); args em p0..p3/pilha, retorno em r0.
 *
 * Diferencas em relacao ao Java:
 *  - Despacho por NOME (switch de String) virou resolve(nome)->id + switch(id).
 *    O Pip2 resolve o id UMA VEZ por entrada de pool e depois chama invokeId():
 *    sem strcmp no caminho quente (importa num ARM de 369 MHz).
 *  - HashMap/ArrayList -> estruturas proprias (IntMap aberto, arrays dinamicos).
 *  - Removido todo o codigo de diagnostico por variavel de ambiente.
 *  - Arquivos (save game / dados companheiros) passam pela interface SdkFileSystem,
 *    implementada pela casca (RFile no Symbian, stdio no teste de desktop).
 *  - Audio passa pela interface SdkAudio (CMdaAudioOutputStream no Symbian).
 */
#ifndef WOLPHUN_SDK_H
#define WOLPHUN_SDK_H

#include "vmcommon.h"

class Pip2;
class Screen;
class Renderer3D;

/* ---------- abstracao de arquivos (save game e dados companheiros) ---------- */
class SdkFileSystem {
public:
    virtual ~SdkFileSystem(){}
    /* le um save pelo nome logico ("resume", "highscore"). Devolve buffer novo
       (delete[] pelo chamador) e tamanho, ou NULL se nao existir. */
    virtual u8*  ReadSave(const char* name, int* outLen) = 0;
    /* grava o save inteiro. */
    virtual void WriteSave(const char* name, const u8* data, int len) = 0;
    /* arquivo de dados COMPANHEIRO ao lado da ROM (somente leitura), ex.: "dvc.mpc". */
    virtual u8*  ReadCompanion(const char* name, int* outLen){ (void)name; if(outLen)*outLen=0; return 0; }
};

/* ---------- abstracao de audio ---------- */
/* Saida de audio da plataforma. O Sdk NAO manda som pronto por aqui: quem mistura e o
   VSound (portavel). A plataforma so abre um canal PCM 16 bits estereo a VSound::KOutRate
   e, no seu proprio callback, chama VSound::RenderFrames para encher o buffer.
   Assim o mixer e identico no PC e no aparelho; so o escoamento muda. */
#include "VSound.h"

class SdkAudio {
public:
    virtual ~SdkAudio(){}

    /* --- canal PCM, alimentado pelo VSound (vBeep e o modulo de som) --- */
    virtual void Start(){}      /* abre/garante o canal (idempotente) */
    virtual void Stop(){}       /* fecha o canal */

    /* --- musica: vPlayResource entrega um recurso pronto (tipicamente SMF/MIDI).
       E por aqui que passa o som da MAIORIA dos jogos: no acervo testado, 233 de 313
       importam vPlayResource e NENHUM importa o modulo VSound. No Symbian isto vai
       direto para o sintetizador do aparelho. --- */
    virtual int  PlayMusic(const u8* data, int len, bool loop)
                 { (void)data; (void)len; (void)loop; return 1; }
    virtual void StopMusic(){}
};

/* Saida de VIBRACAO do aparelho, no mesmo molde do SdkFileSystem e do SdkAudio: o
   nucleo da VM nao conhece Symbian nem Android: quem implementa e a casca de cada
   plataforma. 0 = sem motor, ou desligado nas opcoes -- e o estado normal, nao um erro.
   Mesmo contrato da versao Android (Sdk.Vibrador: vibra/para). */
class SdkVibra {
public:
    virtual ~SdkVibra(){}
    /* liga o motor por aDurationMs; chamar de novo durante um pulso e permitido */
    virtual void Vibrate(int aDurationMs) = 0;
    /* interrompe o pulso em andamento */
    virtual void StopVibra() = 0;
};

/* ---------- mapa int->int (enderecamento aberto, sem STL) ---------- */
class IntMap {
public:
    IntMap();
    ~IntMap();
    void clear();
    void put(int key, int val);
    bool get(int key, int* val) const;
    bool remove(int key, int* oldVal);
    int  size() const { return count; }
    /* iteracao: devolve false quando acabar. *it comeca em 0. */
    bool next(int* it, int* key, int* val) const;
private:
    void grow();
    int*  keys;
    int*  vals;
    u8*   state;      /* 0=vazio 1=ocupado 2=removido */
    int   cap, count;
};

class Sdk {
public:
    Sdk(Pip2* vm, Screen* scr);
    ~Sdk();

    /* resolve o nome de um import p/ um id interno (-1 = desconhecido). */
    static int  resolve(const char* name);
    /* despacha pelo id resolvido (caminho quente). */
    void invokeId(int id);
    /* conveniencia: resolve + despacha (usado quando nao ha cache). */
    void invoke(const char* name){ invokeId(resolve(name)); }

    /* libera um bloco do heap da VM (usado pelo escalonador ao matar uma tarefa). */
    void freePtr(int ptr);

    /* ---------- configuracao vinda da casca ---------- */
    SdkFileSystem* fs;
    SdkAudio*      audio;
    SdkVibra*      vibra;           /* 0 = sem vibracao (sem motor ou desligada) */
    VSound         vsound;      /* modulo de som: mixer proprio, igual ao do PC */
    Renderer3D*    r3d;             /* modulo 3D (proxima etapa); 0 = jogo 2D */
    bool soundEnabled;
    int  deviceModel, deviceVendor; /* vGetCaps System: deviceId=(model<<16)|vendor */
    int  videoFormat;               /* 7 = RGB332 (256 cores diretas) */
    int  apiMinor;                  /* vGetVMGPInfo = (2<<16)|apiMinor */
    bool version150;                /* jogo importa vInit3D => SDK >= 1.50 */

    i64  stubCalls;                 /* chamadas nao implementadas (diagnostico) */

    /* flush de todos os saves gravaveis ainda abertos (chamado ao sair). */
    void flushSaves();

private:
    Pip2*   vm;
    Screen* scr;

    /* ---------- heap da VM: bump + free list ---------- */
    int  nextHeap;
    int  taskStackSize;
    IntMap liveBlocks;              /* ptr -> tamanho */
    struct FreeBlock { int ptr, size; };
    FreeBlock* freeList;
    int freeCount, freeCap;
    int freeBytes;
    int  alloc(int size, bool clear);
    void zero(int ptr,int n);

    /* ---------- estado grafico ---------- */
    int foreColor, backColor, transferMode;
    bool spriteSlotDraw;

    /* fonte bitmap do jogo (VMGPFONT) */
    int aFontPtr, aFontData, aFontChartbl, aFontBpp, aFontW, aFontH, aFontPal;
    void fontRefresh();
    int  setActiveFont(int ptr);
    void vPrintImpl(int mode,int x,int y,const char* str);

    u64 rngState;
    u32 tick0;

    /* ---------- sprites ---------- */
    int   spriteCount;
    int*  slotSprite;
    i16*  slotX;
    i16*  slotY;
    void  drawSpriteSlots();
    int   spriteBoxCollision(int boxPtr,int from,int to);
    int   spriteCollision(int check,int from,int to);

    /* ---------- tilemap (copia do MAP_HEADER) ---------- */
    int  mapHeaderPtr;
    int  mhFlag, mhFmt, mhW, mhH, mhAnim, mhXPan, mhYPan, mhXPos, mhYPos, mhMapData, mhTileData;
    bool mhValid;
    int  frameAnimate, framePassed;
    bool plausiblePtr(int p) const;
    void mapSync();
    void drawMap();
    int  mapAttr(int x,int y);
    int  mapTile(int x,int y);
    void mapSetTile(int x,int y,int tile);
    void mapSetAttr(int x,int y,int attr);

    /* ---------- desenho ---------- */
    u32  getColor(int c) const;
    void px(int x,int y,u32 argb);
    void fillRect(int x0,int y0,int x1,int y1);
    void drawLine(int x0,int y0,int x1,int y1);
    void fillFlatPoly(int p);
    void drawObject(int x,int y,int sp);
    void drawTile(int dataPtr,int format,int x,int y);
    void copyRect(int dS,int sS,int w,int h);
    void setPalette(int ptr,int start,int count);
    int  findRGBIndex(int rgb555);
    int  argbToRgb332(u32 argb) const;

    /* ---------- entrada ---------- */
    int  testKey(int key);
    int  scanKeys();

    /* ---------- texto / strings ---------- */
    int  textExtent(const char* s);
    /* le uma string da memoria da VM p/ um buffer (latin1 ou UTF-16LE). */
    const char* readStr(int ptr, bool unicode, char* buf, int bufLen);
    int  itoa(int val,int buf,bool sign,int len,int pad);
    int  vatoi(int strPtr,int endPtr);
    int  getCaps(int queryType,int buf);
    void writeDateTime(int ptr);

    /* printf */
    struct ArgSrc { Pip2* vm; int base; int off; bool stack; };
    static int nextArg(ArgSrc* a);
    int  formatC(const char* fmt, ArgSrc* args, char* out, int outCap);
    int  sprintfReg(int buf,int fmt);
    int  sprintfVa(int buf,int fmt,int ap);

    /* ---------- streams (recursos do .mpn + arquivos) ---------- */
    enum { MAX_STREAMS = 32 };
    struct Stream {
        bool used;
        u8*  data;
        int  len, cap, pos;
        bool ownsData;      /* true = delete[] no close */
        bool writable;
        char name[64];
    };
    Stream streams[MAX_STREAMS];
    int  streamAlloc();
    Stream* streamGet(int handle);
    int  streamOpen(int namePtr,int mode);
    int  fileOpen(const char* name,int mode);
    int  streamRead(int handle,int buf,int count);
    int  streamWrite(int handle,int buf,int count);
    int  streamSeek(int handle,int where,int whence);
    void streamClose(int handle);
    int  playResource(int data,int length,int flags);

    /* ---------- descompressao LZ ---------- */
    int  decompress(int source,int dest);
    int  decompressStream(int handle,int dest);
    int  decompHdr(int infoPtr,int source);
};

#endif /* WOLPHUN_SDK_H */
