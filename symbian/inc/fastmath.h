/* fastmath.h - funcoes matematicas em float SEM depender de libm.
 *
 * O GCCE do Symbian nao garante <math.h> (precisaria de OpenC/estlib), e o nucleo da
 * VM e portavel. Estas implementacoes bastam para o rasterizador 3D: precisao da ordem
 * de 1e-6, suficiente para transformacao de vertices e sombreado.
 */
#ifndef WOLPHUN_FASTMATH_H
#define WOLPHUN_FASTMATH_H

#include "vmcommon.h"

#define WP_PI   3.14159265358979323846f
#define WP_2PI  6.28318530717958647692f

static inline float wpFloor(float x){
    int i = (int)x;
    return (x < 0.0f && (float)i != x) ? (float)(i-1) : (float)i;
}
static inline float wpAbs(float x){ return x < 0.0f ? -x : x; }

/* raiz quadrada: estimativa por bits + duas iteracoes de Newton-Raphson */
static inline float wpSqrt(float x){
    if (x <= 0.0f) return 0.0f;
    union { float f; u32 i; } u;
    u.f = x;
    u.i = 0x5f3759dfu - (u.i >> 1);        /* 1/sqrt(x) aproximado */
    float r = u.f;
    r = r * (1.5f - 0.5f*x*r*r);
    r = r * (1.5f - 0.5f*x*r*r);
    r = r * (1.5f - 0.5f*x*r*r);
    return x * r;
}

/* seno: reducao para [-pi,pi] + polinomio minimax de grau 7 */
static inline float wpSin(float x){
    /* reduz para [-pi, pi] */
    float k = x * (1.0f/WP_2PI);
    x -= WP_2PI * wpFloor(k + 0.5f);
    float x2 = x*x;
    return x * (1.0f
        + x2 * (-1.66666666e-1f
        + x2 * ( 8.33333333e-3f
        + x2 * (-1.98412698e-4f
        + x2 * ( 2.75573192e-6f)))));
}
static inline float wpCos(float x){ return wpSin(x + WP_PI*0.5f); }
static inline float wpTan(float x){
    float c = wpCos(x);
    if (wpAbs(c) < 1e-7f) return (wpSin(x) >= 0.0f) ? 1e7f : -1e7f;
    return wpSin(x) / c;
}

/* log2 e exp2 por manipulacao de expoente + polinomio na mantissa */
static inline float wpLog2(float x){
    if (x <= 0.0f) return -1e30f;
    union { float f; u32 i; } u; u.f = x;
    int e = (int)((u.i >> 23) & 0xff) - 127;
    u.i = (u.i & 0x007fffffu) | 0x3f800000u;      /* mantissa em [1,2) */
    float m = u.f;
    /* polinomio para log2(m), m em [1,2) */
    float p = -3.4436006e-2f;
    p = p*m +  3.1821337e-1f;
    p = p*m + -1.2315303f;
    p = p*m +  2.5988452f;
    p = p*m + -3.3241990f;
    p = p*m +  3.1157899f;
    return p + (float)e;
}
static inline float wpExp2(float x){
    if (x < -126.0f) return 0.0f;
    if (x >  127.0f) return 3.4e38f;
    float fl = wpFloor(x);
    int   e  = (int)fl;
    float f  = x - fl;                            /* parte fracionaria em [0,1) */
    /* polinomio para 2^f */
    float p = 1.3534167e-2f;
    p = p*f + 5.1700381e-2f;
    p = p*f + 2.4154861e-1f;
    p = p*f + 6.9300383e-1f;
    p = p*f + 1.0f;
    union { float f; u32 i; } u;
    u.i = (u32)((e + 127) << 23);
    return p * u.f;
}
static inline float wpPow(float x,float y){
    if (x <= 0.0f) return 0.0f;
    return wpExp2(y * wpLog2(x));
}
static inline float wpExp(float x){ return wpExp2(x * 1.44269504f); }

#endif /* WOLPHUN_FASTMATH_H */
