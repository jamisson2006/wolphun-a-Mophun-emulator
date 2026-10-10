/* MophunCrypt.cpp - see MophunCrypt.h */
#include "MophunCrypt.h"
#include "MophunKeys.h"

/* ============================ 1024-bit bignum ============================
 * The Java version used BigInteger; Symbian has nothing equivalent without OpenC, so
 * what follows is the minimum needed to compute meta^3 mod N: compare, subtract,
 * multiply and reduce modulo N. Numbers are 32 limbs of 32 bits, limb 0 being the
 * least significant -- the same little-endian order as the bytes in the file.
 * Everything lives in locals: no allocation, no dependencies.
 */
static const int L = kMophunLimbs;          /* 32 limbs = 1024 bits */

/* -1, 0 or +1 */
static int bnCmp(const u32* a, const u32* b, int n)
{
    for (int i = n - 1; i >= 0; i--)
        if (a[i] != b[i]) return (a[i] > b[i]) ? 1 : -1;
    return 0;
}

/* a -= b, assuming a >= b */
static void bnSub(u32* a, const u32* b, int n)
{
    u32 borrow = 0;
    for (int i = 0; i < n; i++)
    {
        u64 t = (u64)a[i] - (u64)b[i] - (u64)borrow;
        a[i] = (u32)t;
        /* if a borrow happened, the high 32 bits become 0xFFFFFFFF */
        borrow = (u32)((t >> 32) & 1u);
    }
}

/* prod[2L] = a[L] * b[L] */
static void bnMul(const u32* a, const u32* b, u32* prod)
{
    int i;
    for (i = 0; i < 2 * L; i++) prod[i] = 0;
    for (i = 0; i < L; i++)
    {
        const u32 ai = a[i];
        if (ai == 0) continue;
        u64 carry = 0;
        for (int j = 0; j < L; j++)
        {
            u64 t = (u64)ai * (u64)b[j] + (u64)prod[i + j] + carry;
            prod[i + j] = (u32)t;
            carry = t >> 32;
        }
        int k = i + L;
        while (carry != 0 && k < 2 * L)
        {
            u64 t = (u64)prod[k] + carry;
            prod[k] = (u32)t;
            carry = t >> 32;
            k++;
        }
    }
}

/* out[L] = prod[2L] mod n[L], by bit-by-bit shift and subtract.
   It is the easiest method to get right -- no quotient estimation -- and it runs
   exactly once per ROM: 2048 iterations, imperceptible even on the phone's ARM. */
static void bnMod(const u32* prod, const u32* n, u32* out)
{
    u32 r[L + 1];
    u32 ne[L + 1];
    int i;
    for (i = 0; i <= L; i++) r[i] = 0;
    for (i = 0; i < L; i++) ne[i] = n[i];
    ne[L] = 0;

    for (int bit = 2 * L * 32 - 1; bit >= 0; bit--)
    {
        /* r = (r << 1) | the next bit of the product */
        u32 carry = (prod[bit >> 5] >> (bit & 31)) & 1u;
        for (i = 0; i <= L; i++)
        {
            u32 next = r[i] >> 31;
            r[i] = (r[i] << 1) | carry;
            carry = next;
        }
        if (bnCmp(r, ne, L + 1) >= 0) bnSub(r, ne, L + 1);
    }
    for (i = 0; i < L; i++) out[i] = r[i];
}

/* out = (a * b) mod n */
static void bnMulMod(const u32* a, const u32* b, const u32* n, u32* out)
{
    u32 prod[2 * L];
    bnMul(a, b, prod);
    bnMod(prod, n, out);
}

/* out = a^3 mod n. With exponent 3 that is two multiplies, so no exponentiation loop. */
static void bnModPow3(const u32* a, const u32* n, u32* out)
{
    u32 t[L];
    bnMulMod(a, a, n, t);
    bnMulMod(t, a, n, out);
}

/* ============================ 32-bit XTEA ============================
 * A direct translation of MophunDecrypt's decryptblock (Source.cpp), which the Java
 * version already reproduced register by register. In Java every operation ended in
 * "& M" to simulate 32 bits inside a long; here the variables ALREADY are u32, so the
 * truncation is automatic and the masks disappear. The sequence is identical.
 */
static u32 decryptblock(u32 block, const u32* key)
{
    u32 r0, r1, r2, r3, r4, r5, r6, r7, r8, r9, r10;
    r4 = block;
    r10 = key[0]; r8 = key[1]; r7 = key[2]; r9 = key[3];

    r0 = r4 << 16; r5 = r0 >> 16;
    r0 = r5 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r5 << 16; r0 = r3 ^ (r1 >> 21);
    r3 = r0 + r5;
    r1 = r7 + 0x5400u; r0 = r1 + 0xFu; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r4 = (r4 >> 16) - r1;

    r0 = r4 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r4 << 16; r0 = r3 ^ (r1 >> 21);
    r3 = r0 + r4;
    r1 = r7 + 0xDA00u; r0 = r1 + 0x56u; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r5 = r5 - r1;

    r0 = r5 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r5 << 16; r0 = r3 ^ (r1 >> 21);
    r3 = r0 + r5;
    r1 = r9 + 0xDA00u; r0 = r1 + 0x56u; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r4 = r4 - r1;

    r0 = r4 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r4 << 16; r0 = r3 ^ (r1 >> 21);
    r3 = r0 + r4;
    r1 = r8 + 0x6000u; r0 = r1 + 0x9Du; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r6 = r5 - r1;

    r0 = r6 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r6 << 16; r0 = r3 ^ (r1 >> 21);
    r1 = r10 + 0x6000u; r3 = r0 + r6; r0 = r1 + 0x9Du; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r0 = r10 + 0xE600u; r5 = r4 - r1; r1 = r0 + 0xE4u;
    r0 = r5 << 16; r4 = r1 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r5 << 16; r0 = r3 ^ (r1 >> 21);
    r2 = r0 + r5;  r1 = r2 ^ (r4 >> 16);
    r6 = r6 - r1;

    r0 = r6 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r6 << 16; r0 = r3 ^ (r1 >> 21);
    r2 = r0 + r6;  r1 = r2 ^ (r4 >> 16);
    r4 = r5 - r1;

    r0 = r4 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r4 << 16; r0 = r3 ^ (r1 >> 21);
    r3 = r0 + r4;
    r1 = r9 + 0x6D00u; r0 = r1 + 0x2Bu; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r6 = r6 - r1;

    r0 = r6 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r6 << 16; r0 = r3 ^ (r1 >> 21);
    r3 = r0 + r6;
    r1 = r8 + 0x6D00u; r0 = r1 + 0x2Bu; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r5 = r4 - r1;

    r0 = r7 + 0xF300u; r1 = r0 + 0x72u; r4 = r1 << 16;
    r0 = r5 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r5 << 16; r0 = r3 ^ (r1 >> 21);
    r2 = r0 + r5;  r1 = r2 ^ (r4 >> 16);
    r6 = r6 - r1;

    r0 = r6 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r6 << 16; r0 = r3 ^ (r1 >> 21);
    r2 = r0 + r6;  r1 = r2 ^ (r4 >> 16);
    r4 = r5 - r1;

    r0 = r4 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r4 << 16; r0 = r3 ^ (r1 >> 21);
    r1 = r8 + 0x7900u; r3 = r0 + r4; r0 = r1 + 0xB9u; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r5 = r6 - r1;

    r0 = r5 << 16; r1 = r0 >> 16;
    r2 = r1 << 4;  r0 = r2 << 16; r3 = r0 >> 16;
    r1 = r5 << 16; r0 = r3 ^ (r1 >> 21);
    r1 = r9 + 0x7900u; r3 = r0 + r5; r0 = r1 + 0xB9u; r2 = r0 << 16; r1 = r3 ^ (r2 >> 16);
    r3 = r4 - r1;

    r0 = r3 << 16; r4 = r0 >> 16;
    r1 = r4 << 4;  r0 = r1 << 16; r2 = r0 >> 16;
    r1 = r3 << 16; r0 = r2 ^ (r1 >> 21);
    r3 = r0 + r3;
    r1 = r10 << 16; r0 = r3 ^ (r1 >> 16);
    r2 = r5 - r0;

    r1 = r2 << 16; r0 = r1 >> 16;
    r0 = r0 | (r4 << 16);
    return r0;
}

/* ============================ the META resource and the key ============================ */

/* The resource table starts at resOff: a list of u32 offsets terminated by a 0.
   The "META" resource has a 9-byte header before its 0x98 useful bytes. */
static const u8* findMeta(const u8* d, int len, int resOff)
{
    if (resOff < 0 || resOff + 4 > len) return 0;
    int o = resOff;
    while (o + 4 <= len)
    {
        u32 v = rdU32(d, o);
        if (v == 0) break;
        const i64 s = (i64)resOff + (i64)v;
        if (s >= 0 && s + 9 + 0x98 <= (i64)len)
        {
            const u8* p = d + (int)s;
            if (p[0] == 'M' && p[1] == 'E' && p[2] == 'T' && p[3] == 'A')
                return p + 9;
        }
        o += 4;
    }
    return 0;
}

/* RSA: meta^3 mod N -> confere o padding -> extrai os 4 u32 da chave simetrica. */
static bool findKey(const u8* meta, u32* keyOut)
{
    u32 x[L];
    int i;
    for (i = 0; i < L; i++) x[i] = rdU32(meta, i * 4);      /* meta[0 .. 0x80) em LE */

    for (int m = 0; m < kMophunModulusCount; m++)
    {
        const u32* N = kMophunModuli[m];
        if (bnCmp(N, x, L) <= 0) continue;                  /* precisa de N > meta */

        u32 r[L];
        bnModPow3(x, N, r);

        u8 rb[128];
        for (i = 0; i < L; i++)
        {
            rb[i*4 + 0] = (u8)(r[i]);
            rb[i*4 + 1] = (u8)(r[i] >> 8);
            rb[i*4 + 2] = (u8)(r[i] >> 16);
            rb[i*4 + 3] = (u8)(r[i] >> 24);
        }

        /* padding do RSA: do byte 0x26 ao 0x7F so pode haver 0x00, 0x01 ou 0xFF.
           E isso que diz se o modulo tentado e o certo. */
        bool pad = true;
        for (i = 0x26; i < 0x80; i++)
        {
            const int b = rb[i];
            if (b != 0x00 && b != 0x01 && b != 0xFF) { pad = false; break; }
        }
        if (!pad) continue;

        for (int k = 0; k < 4; k++) keyOut[k] = rdU32(rb, 0x14 + k * 4);
        return true;
    }
    return false;
}

/* ============================ API ============================ */

bool MophunCrypt::looksEncrypted(const u8* d, int len)
{
    if (len < 44 || d[0]!='V' || d[1]!='M' || d[2]!='G' || d[3]!='P') return false;
    const int codeSize = (int)rdU32(d, 12);
    int n = len - 40;
    if (codeSize < n) n = codeSize;
    if (n > 4096) n = 4096;
    if (n <= 64) return false;

    bool seen[256];
    for (int i = 0; i < 256; i++) seen[i] = false;
    int distinct = 0;
    for (int i = 0; i < n; i++)
    {
        const u8 b = d[40 + i];
        if (!seen[b]) { seen[b] = true; distinct++; }
    }
    return distinct > 190;
}

bool MophunCrypt::hasKey(const u8* d, int len)
{
    if (len < 44 || d[0]!='V' || d[1]!='M' || d[2]!='G' || d[3]!='P') return false;
    const int codeSize = (int)rdU32(d, 12);
    const int dataSize = (int)rdU32(d, 16);
    if (codeSize < 0 || dataSize < 0) return false;
    const i64 resOff = 40 + (i64)codeSize + (i64)dataSize;
    if (resOff > (i64)len) return false;
    const u8* meta = findMeta(d, len, (int)resOff);
    if (!meta) return false;
    u32 key[4];
    return findKey(meta, key);
}

bool MophunCrypt::decryptInPlace(u8* d, int len)
{
    if (len < 44 || d[0]!='V' || d[1]!='M' || d[2]!='G' || d[3]!='P') return false;
    const int codeSize = (int)rdU32(d, 12);
    const int dataSize = (int)rdU32(d, 16);
    if (codeSize <= 0 || dataSize < 0) return false;
    const i64 resOff = 40 + (i64)codeSize + (i64)dataSize;
    if (resOff > (i64)len) return false;

    const u8* meta = findMeta(d, len, (int)resOff);
    if (!meta) return false;
    u32 key[4];
    if (!findKey(meta, key)) return false;

    /* so a secao de codigo e cifrada, palavra a palavra */
    int end = codeSize - (codeSize % 4);
    if (40 + end > len) end = ((len - 40) / 4) * 4;
    for (int i = 0; i < end; i += 4)
    {
        const u32 w = rdU32(d, 40 + i);
        const u32 r = decryptblock(w, key);
        d[40 + i + 0] = (u8)(r);
        d[40 + i + 1] = (u8)(r >> 8);
        d[40 + i + 2] = (u8)(r >> 16);
        d[40 + i + 3] = (u8)(r >> 24);
    }
    return true;
}
