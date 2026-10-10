/* Vmgp.cpp - ported from src/wolphun/vm/Vmgp.java (see Vmgp.h). */
#include "Vmgp.h"
#include "MophunCrypt.h"

Vmgp::Vmgp()
: heapRaw(0), heapSize(0), flags(0), stackSize(0),
  codeSize(0), dataSize(0), bssSize(0), resSize(0), poolCount(0), stringSize(0),
  all(0), allLen(0),
  codeOff(0), dataOff(0), resOff(0), poolOff(0), strOff(0),
  pool(0), resStart(0), resLen(0), resCount(0),
  archiveZip(0), archiveZipLen(0) {}

Vmgp::~Vmgp(){
    delete[] all;
    delete[] pool;
    delete[] resStart;
    delete[] resLen;
    /* archiveZip points INTO `all` -- a slice, not a separate allocation, so do not delete it. */
}

Vmgp* Vmgp::Create(const u8* d, int len, const char** err){
    const char* dummy; if (!err) err = &dummy;
    Vmgp* v = new Vmgp();
    v->allLen = len;
    v->all = new u8[len];
    wpMemCopy(v->all, d, len);

    /* DRM: in Sony Ericsson's original ROMs the CODE section is encrypted, though the
       header is not. Without decrypting, the parse succeeds and the VM then executes
       noise: PC leaves the code area on the first instruction and the screen stays
       black forever -- which is exactly what happened on the E63 and in EKA2L1.
       Decryption happens here, on the in-memory copy; the user's file is never
       modified. */
    const bool looked    = MophunCrypt::looksEncrypted(v->all, len);
    const bool decrypted = looked && MophunCrypt::decryptInPlace(v->all, len);

    /* The parse comes first even when decryption failed: a "packed" ROM (the
       compressed S60/PPC kind) also looks like noise to the heuristic, and its message
       is more useful than the DRM one. */
    if (!v->parse(err)){ delete v; return 0; }

    if (looked && !decrypted)
    {
        /* It parsed -- the header is plaintext -- but the code is still encrypted, and
           running that would give a black screen. Better to say what happened. */
        *err = "DRM-protected ROM: no Sony Ericsson key fits this file.";
        delete v; return 0;
    }
    v->wasEncrypted = decrypted;
    return v;
}

bool Vmgp::parse(const char** err){
    const u8* d = all;
    int len = allLen;
    const char* dummy; if (!err) err = &dummy;

    if (len < HEADER_SIZE || d[0]!='V'||d[1]!='M'||d[2]!='G'||d[3]!='P'){
        *err = "Not a valid VMGP file (a decrypted .mpn)";
        return false;
    }
    heapRaw   = rdU32(d,4);
    heapSize  = ((u64)heapRaw) << 2;
    flags     = rdU16(d,8);
    stackSize = ((i64)rdU16(d,10)) << 2;
    codeSize  = (int)rdU32(d,12);
    dataSize  = (int)rdU32(d,16);
    bssSize   = (int)rdU32(d,20);
    resSize   = (int)rdU32(d,24);
    /* rdU32(d,28) = unknown / packed-end */
    poolCount = (int)rdU32(d,32);
    stringSize= (int)rdU32(d,36);

    codeOff = HEADER_SIZE;
    dataOff = codeOff + codeSize;
    resOff  = dataOff + dataSize;
    poolOff = resOff + resSize;
    strOff  = poolOff + poolCount * 8;

    /* A "packed" ROM (S60/PPC) has code+data LZ-compressed. Field @28, which is 0 in
       normal ROMs, holds the end of the compressed code+data; the sizes in the header
       are the UNCOMPRESSED ones, so poolOff lands past the end of the file. We do not
       decompress here -- a clear error instead, pointing at the "_decompressed"
       version of the ROM. */
    u32 u28 = rdU32(d,28);
    if (u28 != 0 || (i64)strOff + stringSize > (i64)len){
        *err = "Compressed ROM (packed S60/PPC) is not supported - use the \"_decompressed\" version.";
        return false;
    }

    /* pool */
    pool = new PoolItem[poolCount > 0 ? poolCount : 1];
    for (int i=0;i<poolCount;i++){
        u32 seg = rdU32(d, poolOff + i*8);
        u32 tgt = rdU32(d, poolOff + i*8 + 4);
        pool[i].type       = (int)(seg & 0xF);
        pool[i].itemTarget = (int)((seg >> 4) & 0xF);
        pool[i].meta       = (int)(seg >> 8);
        pool[i].target     = (int)tgt;
    }

    /* the resource table: offsets relative to resOff, terminated by a 0. */
    int maxRes = (poolOff - resOff) / 4;
    if (maxRes < 0) maxRes = 0;
    int* tmp = new int[maxRes > 0 ? maxRes : 1];
    int n = 0;
    int o = resOff;
    while (o + 4 <= poolOff){
        int val = (int)rdU32(d,o);
        if (val == 0) break;
        tmp[n++] = val;
        o += 4;
    }
    resCount = n;
    resStart = new int[n > 0 ? n : 1];
    resLen   = new int[n > 0 ? n : 1];
    for (int i=0;i<n;i++){
        int s = resOff + tmp[i];
        int e = (i+1 < n) ? resOff + tmp[i+1] : poolOff;
        resStart[i] = s;
        resLen[i]   = e - s;
    }
    delete[] tmp;

    scanEmbeddedZip();  /* STUB at this stage */
    return true;
}

/* STUB: the embedded-ZIP VFS will be ported in a dedicated step, since it needs
   inflate. Without it, 3D games that keep their assets in a ZIP get empty files; 2D
   games and straightforward 3D ones are unaffected. */
void Vmgp::scanEmbeddedZip(){
    archiveZip = 0;
    archiveZipLen = 0;
}

const u8* Vmgp::vfsGet(const char* /*name*/, int* outLen) const {
    if (outLen) *outLen = 0;
    return 0;
}

const char* Vmgp::getString(int off) const {
    /* strings are NUL-terminated inside `all`, so this returns a direct pointer. */
    int p = strOff + off;
    if (p < 0 || p >= allLen) return "";
    return (const char*)(all + p);
}

bool Vmgp::importsSymbol(const char* name) const {
    for (int i=0;i<poolCount;i++){
        if (pool[i].type==2 && wpStrCmp(name, getString(pool[i].meta))==0)
            return true;
    }
    return false;
}
