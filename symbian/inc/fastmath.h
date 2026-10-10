/* fastmath.h - floating-point maths WITHOUT depending on libm.
 *
 * Symbian's GCCE does not guarantee <math.h> (that would need OpenC/estlib), and the VM
 * core has to stay portable. These implementations are good enough for the 3D
 * rasterizer: accuracy around 1e-6, which covers vertex transformation and shading.
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

/* square root: bit-level estimate followed by Newton-Raphson iterations */
static inline float wpSqrt(float x){
    if (x <= 0.0f) return 0.0f;
    union { float f; u32 i; } u;
    u.f = x;
    u.i = 0x5f3759dfu - (u.i >> 1);        /* approximate 1/sqrt(x) */
    float r = u.f;
    r = r * (1.5f - 0.5f*x*r*r);
    r = r * (1.5f - 0.5f*x*r*r);
    r = r * (1.5f - 0.5f*x*r*r);
    return x * r;
}

/* sine: range reduction to [-pi,pi] plus a degree-7 minimax polynomial */
static inline float wpSin(float x){
    /* reduce to [-pi, pi] */
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

/* log2 and exp2 by exponent manipulation plus a polynomial over the mantissa */
static inline float wpLog2(float x){
    if (x <= 0.0f) return -1e30f;
    union { float f; u32 i; } u; u.f = x;
    int e = (int)((u.i >> 23) & 0xff) - 127;
    u.i = (u.i & 0x007fffffu) | 0x3f800000u;      /* mantissa in [1,2) */
    float m = u.f;
    /* polynomial for log2(m), with m in [1,2) */
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
    float f  = x - fl;                            /* fractional part, in [0,1) */
    /* polynomial for 2^f */
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
