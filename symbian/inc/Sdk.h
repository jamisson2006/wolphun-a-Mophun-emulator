/* Sdk.h - the Mophun SDK API layer (vGfx*, vSprite*, vMap*, ...).
 * Ported from src/wolphun/vm/Sdk.java. Every CALLl to an import lands here through
 * invoke(); arguments arrive in p0..p3 then on the stack, the result goes in r0.
 *
 * Differences from the Java version:
 *  - Dispatch BY NAME (a switch over Strings) became resolve(name)->id plus
 *    switch(id). Pip2 resolves the id ONCE per pool entry and then calls invokeId(),
 *    so there is no strcmp on the hot path -- it matters on a 369 MHz ARM.
 *  - HashMap/ArrayList -> structures of our own (an open-addressed IntMap, dynamic
 *    arrays).
 *  - All the environment-variable diagnostic code is gone.
 *  - Files (save games and companion data) go through the SdkFileSystem interface,
 *    implemented by the shell: RFile on Symbian, stdio in the desktop test.
 *  - Audio goes through the SdkAudio interface (CMdaAudioOutputStream on Symbian).
 */
#ifndef WOLPHUN_SDK_H
#define WOLPHUN_SDK_H

#include "vmcommon.h"

class Pip2;
class Screen;
class Renderer3D;

/* ---------- the file abstraction: save games and companion data ---------- */
class SdkFileSystem {
public:
    virtual ~SdkFileSystem(){}
    /* Reads a save by its logical name ("resume", "highscore"). Returns a fresh
       buffer, which the caller must delete[], plus its length; NULL if there is none. */
    virtual u8*  ReadSave(const char* name, int* outLen) = 0;
    /* writes the whole save. */
    virtual void WriteSave(const char* name, const u8* data, int len) = 0;
    /* a COMPANION data file sitting next to the ROM, read-only, e.g. "dvc.mpc". */
    virtual u8*  ReadCompanion(const char* name, int* outLen){ (void)name; if(outLen)*outLen=0; return 0; }
};

/* ---------- the audio abstraction ---------- */
/* The platform's audio output. The Sdk does NOT push finished sound through here: the
   mixing is done by the portable VSound. The platform only opens a 16-bit stereo PCM
   channel at VSound::KOutRate and, from its own callback, calls VSound::RenderFrames to
   fill the buffer. That way the mixer is identical on PC and on the phone; only the
   draining differs. */
#include "VSound.h"

class SdkAudio {
public:
    virtual ~SdkAudio(){}

    /* --- the PCM channel, fed by VSound (vBeep and the sound module) --- */
    virtual void Start(){}      /* opens the channel, or confirms it is open; idempotent */
    virtual void Stop(){}       /* closes the channel */

    /* --- music: vPlayResource hands over a ready-made resource, typically SMF/MIDI.
       This is the path MOST games' sound takes: across the archive tested, 233 of 313
       import vPlayResource and NONE import the VSound module. On Symbian this goes
       straight to the phone's synthesizer. --- */
    virtual int  PlayMusic(const u8* data, int len, bool loop)
                 { (void)data; (void)len; (void)loop; return 1; }
    virtual void StopMusic(){}
};

/* The device's VIBRATION output, in the same shape as SdkFileSystem and SdkAudio: the
   VM core knows nothing about Symbian or Android, and each platform's shell implements
   it. 0 means no motor, or switched off in the settings -- that is the normal state,
   not an error. Same contract as the Android version's Sdk.Vibrador interface. */
class SdkVibra {
public:
    virtual ~SdkVibra(){}
    /* runs the motor for aDurationMs; calling again during a pulse is allowed */
    virtual void Vibrate(int aDurationMs) = 0;
    /* interrupts the pulse in progress */
    virtual void StopVibra() = 0;
};

/* ---------- an int->int map (open addressing, no STL) ---------- */
class IntMap {
public:
    IntMap();
    ~IntMap();
    void clear();
    void put(int key, int val);
    bool get(int key, int* val) const;
    bool remove(int key, int* oldVal);
    int  size() const { return count; }
    /* iteration: returns false when there is nothing left. *it starts at 0. */
    bool next(int* it, int* key, int* val) const;
private:
    void grow();
    int*  keys;
    int*  vals;
    u8*   state;      /* 0 = empty, 1 = occupied, 2 = erased */
    int   cap, count;
};

class Sdk {
public:
    Sdk(Pip2* vm, Screen* scr);
    ~Sdk();

    /* resolves an import's name to an internal id (-1 = unknown). */
    static int  resolve(const char* name);
    /* dispatches by the resolved id -- the hot path. */
    void invokeId(int id);
    /* convenience: resolve then dispatch, used when there is no cache. */
    void invoke(const char* name){ invokeId(resolve(name)); }

    /* frees a block of the VM's heap; used by the scheduler when killing a task. */
    void freePtr(int ptr);

    /* ---------- configuration coming from the shell ---------- */
    SdkFileSystem* fs;
    SdkAudio*      audio;
    SdkVibra*      vibra;           /* 0 = no vibration: no motor, or switched off */
    VSound         vsound;      /* the sound module: our own mixer, as on the PC */
    Renderer3D*    r3d;             /* the 3D module; 0 means a 2D game */
    bool soundEnabled;
    int  deviceModel, deviceVendor; /* vGetCaps System: deviceId = (model<<16)|vendor */
    int  videoFormat;               /* 7 = RGB332, 256 direct colours */
    int  apiMinor;                  /* vGetVMGPInfo = (2<<16)|apiMinor */
    bool version150;                /* the game imports vInit3D => SDK >= 1.50 */

    i64  stubCalls;                 /* unimplemented calls, for diagnostics */

    /* flushes every writable save still open; called on the way out. */
    void flushSaves();

private:
    Pip2*   vm;
    Screen* scr;

    /* ---------- the VM heap: bump allocation plus a free list ---------- */
    int  nextHeap;
    int  taskStackSize;
    IntMap liveBlocks;              /* ptr -> size */
    struct FreeBlock { int ptr, size; };
    FreeBlock* freeList;
    int freeCount, freeCap;
    int freeBytes;
    int  alloc(int size, bool clear);
    void zero(int ptr,int n);

    /* ---------- graphics state ---------- */
    int foreColor, backColor, transferMode;
    bool spriteSlotDraw;

    /* the game's own bitmap font (VMGPFONT) */
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

    /* ---------- the tilemap, a copy of MAP_HEADER ---------- */
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

    /* ---------- drawing ---------- */
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

    /* ---------- input ---------- */
    int  testKey(int key);
    int  scanKeys();

    /* ---------- text and strings ---------- */
    int  textExtent(const char* s);
    /* reads a string out of the VM's memory into a buffer (latin1 or UTF-16LE). */
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

    /* ---------- streams: resources inside the .mpn, plus files ---------- */
    enum { MAX_STREAMS = 32 };
    struct Stream {
        bool used;
        u8*  data;
        int  len, cap, pos;
        bool ownsData;      /* true = delete[] it on close */
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

    /* ---------- LZ decompression ---------- */
    int  decompress(int source,int dest);
    int  decompressStream(int handle,int dest);
    int  decompHdr(int infoPtr,int source);
};

#endif /* WOLPHUN_SDK_H */
