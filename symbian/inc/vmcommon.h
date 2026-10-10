/* vmcommon.h - common types and helpers for the VM core (portable C++98).
 *
 * The VM core (Vmgp, Pip2, Sdk, Screen, Renderer3D) is written in PORTABLE C++: no
 * Symbian, no STL and NO LIBC. It compiles both with the device's GCCE and with a
 * desktop g++, so it can be tested without a phone. Only the "shell" (the S60 app,
 * menus, screen, keyboard) uses the native Symbian API.
 *
 * Why no libc: Symbian's GCCE only offers <stdint.h>/<string.h> through OpenC/estlib,
 * which would be an extra dependency on the phone. The four functions we needed
 * (memcpy/memset/strcmp/strlen) live here as wpMemCopy/wpMemSet/wpStrCmp/wpStrLen.
 *
 * Java->C++ semantics that matter:
 *  - Java `>>>` (logical shift) -> cast to u32 and `>>`.
 *  - Java int overflow is defined as two's complement -> build with -fwrapv.
 *  - `(int)(byte)x` (sign-extend) -> (i32)(i8)x.
 */
#ifndef WOLPHUN_VMCOMMON_H
#define WOLPHUN_VMCOMMON_H

/* ---- fixed-width types, without depending on <stdint.h> ---- */
typedef unsigned char      u8;
typedef signed   char      i8;
typedef unsigned short     u16;
typedef signed   short     i16;
typedef unsigned int       u32;
typedef signed   int       i32;
#if defined(_MSC_VER)
typedef unsigned __int64   u64;
typedef signed   __int64   i64;
#else
typedef unsigned long long u64;
typedef signed   long long i64;
#endif

/* ---- libc replacements (see the note above) ---- */
static inline void* wpMemCopy(void* dst, const void* src, int n){
    u8* d = (u8*)dst; const u8* s = (const u8*)src;
    for (int i=0;i<n;i++) d[i]=s[i];
    return dst;
}
static inline void* wpMemSet(void* dst, int v, int n){
    u8* d = (u8*)dst;
    for (int i=0;i<n;i++) d[i]=(u8)v;
    return dst;
}
static inline int wpStrCmp(const char* a, const char* b){
    while (*a && *a==*b){ a++; b++; }
    return (int)(u8)*a - (int)(u8)*b;
}
static inline int wpStrLen(const char* s){
    int n=0; while (s[n]) n++;
    return n;
}

/* little-endian reads from a byte buffer (same as Java's Vmgp.u16/u32). */
static inline u16 rdU16(const u8* d, int o){
    return (u16)((d[o] & 0xff) | ((d[o+1] & 0xff) << 8));
}
static inline u32 rdU32(const u8* d, int o){
    return (u32)((d[o] & 0xffu) | ((d[o+1] & 0xffu) << 8)
               | ((d[o+2] & 0xffu) << 16) | ((d[o+3] & 0xffu) << 24));
}

#endif /* WOLPHUN_VMCOMMON_H */
