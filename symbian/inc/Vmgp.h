/* Vmgp.h - loader/parser for a decrypted Mophun VMGP (.mpn) executable.
 * Ported from src/wolphun/vm/Vmgp.java. Exposes the sections (code/data), the pool
 * table (relocations/imports), strings and resources (sprites/tiles/maps/...).
 *
 * File layout: header(40) | code | data | resource | pool | string
 *
 * ERRORS: the Java constructor threw; here the Vmgp::Create() factory returns NULL and
 * fills in *err instead -- no exceptions, which suits Symbian/GCCE.
 *
 * STUB at this stage: scanEmbeddedZip (the embedded-ZIP VFS) is a no-op. Only 3D games
 * that bundle their assets in a ZIP (The Da Vinci Code, for instance) need it; 2D games
 * and most simple 3D ones do not. It will be ported in a dedicated step (inflate).
 */
#ifndef WOLPHUN_VMGP_H
#define WOLPHUN_VMGP_H

#include "vmcommon.h"

class Vmgp {
public:
    enum { HEADER_SIZE = 40 };

    struct PoolItem {
        int type;        /* 0=End 1=Local 2=Import 3=Global 4=SecRel 5=Sw16 6=Sw32 7=Const32 8=SymAdd */
        int itemTarget;  /* 1=code 2=data 4=bss */
        int meta;        /* string offset (imports/globals) or metadata */
        int target;      /* target offset */
    };

    /* ---- header ---- */
    u32 heapRaw;     /* raw field @4 */
    u64 heapSize;    /* heapRaw << 2 (bytes) */
    int flags;
    i64 stackSize;   /* u16 @10 << 2 (bytes) */
    int codeSize, dataSize, bssSize, resSize, poolCount, stringSize;

    /* ---- buffer and section offsets ---- */
    u8* all;         /* the whole file, owned by this object */
    int allLen;
    bool wasEncrypted;  /* the ROM had DRM and was decrypted on load */
    int codeOff, dataOff, resOff, poolOff, strOff;

    /* ---- pool ---- */
    PoolItem* pool;  /* poolCount entries */

    /* the heap the game asked for, aligned to 16 (nofun's formula). */
    u64 heapBytesRequested() const { return (heapSize + 15u) & ~(u64)15u; }

    /* factory: NULL on error, with *err pointing at a static message. `d` is copied. */
    static Vmgp* Create(const u8* d, int len, const char** err);
    ~Vmgp();

    int    resourceCount()  const { return resCount; }
    int    resourceOffset(int i) const { return resStart[i]; }
    int    resourceLength(int i) const { return resLen[i]; }

    /* a string from the string section at the given offset (points inside `all`, NUL-terminated). */
    const char* getString(int off) const;

    /* true if the executable IMPORTS the symbol (pool type 2 = Import). */
    bool importsSymbol(const char* name) const;

    /* ---- embedded VFS (STUB at this stage) ---- */
    bool  hasArchive()  const { return archiveZip != 0; }
    const u8* archiveBytes() const { return archiveZip; }
    int   archiveLen()  const { return archiveZipLen; }
    const u8* vfsGet(const char* name, int* outLen) const; /* NULL while stubbed */

private:
    Vmgp();
    bool parse(const char** err);
    void scanEmbeddedZip(); /* STUB: no-op for now */

    int* resStart;
    int* resLen;
    int  resCount;

    u8* archiveZip;      /* NULL = no embedded ZIP */
    int archiveZipLen;
};

#endif /* WOLPHUN_VMGP_H */
