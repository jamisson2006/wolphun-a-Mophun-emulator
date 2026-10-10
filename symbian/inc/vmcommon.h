/* vmcommon.h - tipos e helpers comuns do nucleo da VM (portavel C++98).
 *
 * O nucleo da VM (Vmgp, Pip2, Sdk, Screen, Renderer3D) e escrito em C++ PORTAVEL:
 * nada de Symbian, nada de STL e NADA DE LIBC. Compila com o GCCE do device E com um
 * g++ de desktop (para testar sem o aparelho). So a "casca" (app S60, menus, tela,
 * teclado) usa a API nativa do Symbian.
 *
 * Por que sem libc: o GCCE do Symbian so oferece <stdint.h>/<string.h> com OpenC/estlib,
 * que seria uma dependencia extra no aparelho. As quatro funcoes que usavamos
 * (memcpy/memset/strcmp/strlen) estao aqui como wpMemCopy/wpMemSet/wpStrCmp/wpStrLen.
 *
 * Semantica Java->C++ importante:
 *  - Java `>>>` (shift logico) -> cast para u32 e `>>`.
 *  - Java int tem overflow 2-complemento definido -> compilar com -fwrapv.
 *  - `(int)(byte)x` (sign-extend) -> (i32)(i8)x.
 */
#ifndef WOLPHUN_VMCOMMON_H
#define WOLPHUN_VMCOMMON_H

/* ---- tipos de largura fixa, sem depender de <stdint.h> ---- */
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

/* ---- substitutos de libc (ver nota acima) ---- */
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

/* leitura little-endian de um buffer de bytes (igual a Vmgp.u16/u32 do Java). */
static inline u16 rdU16(const u8* d, int o){
    return (u16)((d[o] & 0xff) | ((d[o+1] & 0xff) << 8));
}
static inline u32 rdU32(const u8* d, int o){
    return (u32)((d[o] & 0xffu) | ((d[o+1] & 0xffu) << 8)
               | ((d[o+2] & 0xffu) << 16) | ((d[o+3] & 0xffu) << 24));
}

#endif /* WOLPHUN_VMCOMMON_H */
