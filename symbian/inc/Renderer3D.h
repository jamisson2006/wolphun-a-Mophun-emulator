/* Renderer3D.h - the software rasterizer for Mophun's 3D subsystem (VMGP3D).
 * Ported from src/wolphun/vm/Renderer3D.java.
 *
 * Matrix stack (14-bit fixed point, column-major by default), vertex transformation,
 * near-plane clipping, and triangle filling with a z-buffer, Gouraud shading and
 * perspective-correct texturing.  clip = projection * current * v.
 *
 * Formats: positions and matrices are Fixed/16384; UVs are /1024; an angle is
 * (d & 0xFFF)/4096 of a turn.
 *
 * Differences from the Java version:
 *  - NO libm: sqrt/sin/cos/tan/pow/exp/floor are implemented here (fastmath), because
 *    Symbian's GCCE does not guarantee math.h without OpenC.
 *  - All the environment-variable diagnostics and the save-state code are gone.
 *  - No supersampling (rs=1): the z-buffer is screen-sized.
 *  - Vertex buffers are REUSED, growing on demand, instead of being allocated per call
 *    -- allocating ~100 KB per primitive would kill a phone.
 *  - The HashMap of managed textures became a growing array indexed by handle.
 */
#ifndef WOLPHUN_RENDERER3D_H
#define WOLPHUN_RENDERER3D_H

#include "vmcommon.h"

class Pip2;
class Screen;

class Renderer3D {
public:
    Renderer3D(Pip2* vm, Screen* scr);
    ~Renderer3D();

    /* ---------- fixed point and the SDK's maths ---------- */
    static float f2f(int fx){ return fx / 16384.0f; }
    static int   ff(float v){ return (int)(v * 16384.0f); }
    static float uv2f(i16 u){ return u / 1024.0f; }
    /* The SDK says "Degree, range 0 - 4095", so a FULL turn is 4096 units and the
       divisor is 4096, NOT 4095. With 4095 every angle came out 0.024% too large and the
       error accumulated in sky-dome tessellation and in the camera. */
    static float ang2rad(int d){ return (d & 0xFFF) / 4096.0f * 6.28318530718f; }
    static float ang2deg(int d){ return (d & 0xFFF) / 4096.0f * 360.0f; }

    int vMul(int a,int b){ return (int)((((i64)a)*b) >> 14); }
    int vDiv(int a,int b){ return b==0 ? 0 : (int)((((i64)a)<<14)/b); }
    int vPow(int val,int exp);
    int vSqrt(int v);
    int vSin(int v);
    int vCos(int v);
    int vTan(int v);

    /* ---------- vectors (NativeVector3D, 12 bytes) ---------- */
    void vectorAdd(int d,int a,int b);
    void vectorSub(int d,int a,int b);
    void vectorMul(int d,int a,int b);
    void vectorNormalize(int d);
    void crossProduct(int d,int a,int b);
    int  dotProduct(int a,int b);
    void vectorTransformV3(int dest,int src,int count);
    void vectorTransformV4(int dst,int src,int count);
    void vectorProjectV3(int dst,int src,int count){ projectImpl(dst,src,count,false); }
    void vectorProjectV4(int dst,int src,int count){ projectImpl(dst,src,count,true); }
    void vectorArrayAdd(int d,int s1,int s2,int count);
    void vectorArrayDelta(int d,int s1,int s2,int param);

    /* ---------- frustum, planes and collision ---------- */
    int  boxInViewFrustum(int b);
    void createPlaneFromPoly(int dest,int poly);
    int  collisionPointBox(int point,int box);
    int  collisionBoxBox(int b1,int b2);
    int  collisionVectorPoly(int collPos,int lineV,int polyV,int plane);
    int  collisionVectorPlane(int collPos,int lineV,int plane){ return collisionVectorPoly(collPos,lineV,0,plane); }

    /* ---------- matrices ---------- */
    void setMatrixMode(int mode){ if (mode==0 || mode==0x2000) matrixOrientation = mode; }
    void matrixIdentity();
    void matrixSetCurrent(int ptr);
    void matrixGetCurrent(int ptr);
    void matrixMultiply(int ptr);
    void matrixSetProjection(int ptr);
    void matrixSetLight(int ptr);
    void matrixTranslate(int x,int y,int z);
    void matrixScale(int x,int y,int z);
    void matrixRotateX(int d);
    void matrixRotateY(int d);
    void matrixRotateZ(int d);
    void matrixRotateVector(int axisPtr,int d);
    void matrixPerspective(int w,int h,int near,int far);
    void matrixOrtho(int w,int h,int near,int far);
    void matrixLookAt(int eyeP,int atP,int upP);
    void matrixInvert();
    void matrixTranspose();
    void matrixMultiply3x3(int ptr);

    /* ---------- state ---------- */
    void setViewport(int l,int t,int w,int h){ vpL=l; vpT=t; vpW=w; vpH=h; }
    void setRenderState(int state,int value);
    void setFogColor(int rgb){ fogColor = rgb & 0xffffff; }
    int  getInteger(int param);
    int  getZBufferValue(int x,int y);
    void clearDepth();
    /* Clears the z-buffer ONLY if it has been allocated, i.e. 3D is active. Called on
       every vFlipScreen: Mophun's z-buffer is per-frame scratch, but games like Rally
       Pro Contest NEVER call vSetZBuffer or vClearScreen. Without clearing it, z
       accumulated the smallest depth ever seen per pixel, so new geometry failed the
       z-test and VANISHED. */
    void clearDepthPerFrame();

    /* ---------- textures ---------- */
    int  setTexture(int dataPtr,int format,int lods,int mipCount);
    int  createTexture(int texPtr);
    void setActiveTexture(int handle);
    void deleteTexture(int handle);
    void deleteAllTextures();

    /* ---------- lighting ---------- */
    void setAmbientLight(int rgb);
    void setCameraPos(int p);
    void resetLights();
    void setLight(int index,int p);
    void setMaterial(int p);
    void setMaterial2(int p);
    void lightPoint(int argsPtr);

    /* ---------- drawing ---------- */
    void renderPrimitive(int meshPtr,int topology);
    void renderPrimitiveIndexed(int idxPtr,int idxCount,int meshPtr,int topology);
    void drawPolygon(int p1,int p2,int p3);
    void drawBillboard(int bb);

    /* texture filter forced from Settings: 0 = follow the game, 1 = LINEAR, 2 = NEAREST */
    int texFilterForce;

    /* instrumentation for diagnostics; the cost is negligible */
    i64 trisDrawn, pixels3d, drawCalls;

private:
    enum { MAXL = 8 };
    struct Tex { int format, w, h, dataPtr; u32* palette; int palCount; bool used; };

    Pip2*   vm;
    Screen* scr;

    /* row-major float[16] matrices (m[r*4+c]); v' = M*v */
    float current[16], projection[16], light[16];
    int   matrixOrientation;        /* 0x2000 = ColumnMajor, the default */

    int   vpL, vpT, vpW, vpH;
    /* scale of the integer z: 1.0 = 1<<22 */
    enum { KZShift = 22 };
    static const i32 KZFar = 0x7fffffff;   /* empty */
    static const i32 KZEps = 1 << 6;       /* ~1.5e-5: the old +1e-5f for coplanar decals */
    /* 16 BITS WAS ALREADY TRIED HERE, as the official VM does, and REVERTED: in
       Carmageddon 3D the quantisation loses 7% of the pixels to z-fighting and doubles
       the mean colour deviation, while the gain -- less RAM traffic -- is invisible on a
       PC, where the buffer fits in cache, and cannot be measured on the phone. A
       measured cost against a supposed benefit: not worth it. If a measurement on the
       E63 ever becomes possible, the conversion is a shift of 6 and is described in this
       very comment. */

    /* INTEGER Z-BUFFER (signed 10.22, see KZShift above). It used to be float: every
       depth test was an __aeabi_fcmplt on the ARM11, one per pixel drawn -- 153 million
       of them in a 40M-instruction window of Rally. Mophun's OFFICIAL emulator uses a
       16-bit integer z; 10.22 is roomier than that and still fits in an i32.
       Precision is 2.4e-7, and z in NDC lives close to [-1,1]; the old 1e-5 epsilon of
       the blended path became KZEps. KZFar is the "empty" value. */
    i32* zbuf; int zW, zH;

    /* render states; the defaults are the behaviour verified across the game archive */
    int  cullMode;                  /* DEFAULT = CounterClockwise (2) -> culling IS ON */
    bool textureMode;
    bool transp3d;                  /* state 15: texture index 0 is transparent */
    int  shadeMode;                 /* state 7:  1=FLAT, 2=GOURAUD (default) */
    int  texBlendMode;              /* state 11: 1=DECAL, 2=MODULATE (default), 4=ADD */
    int  colorBlend;                /* state 20: 0=REPLACE,1=ALPHAADD,2=ALPHA,3=MODULATE */
    bool alphaEnable;               /* state 12 */
    int  wrapMode;                  /* state 14: 1=WRAP (default), 2=CLAMP, 4=MIRROR */
    bool zEnable;                   /* state 4 */
    int  zFunc;                     /* state 5: 2=LESS (default) */
    bool fogEnable;                 /* state 8 */
    int  fogMode;                   /* state 22: 0=EXP, 1=LINEAR */
    int  fogColor;                  /* state 21 / vSetFogColor (RGB888) */
    float fogStart, fogEnd, fogDensity;
    bool lightingEnable;            /* state 17 */
    bool specularEnable;            /* state 16 */
    int  filterMode;                /* state 13: 1=NEAREST, 2=LINEAR, 4/8/16/32=mip* */

    /* the active texture */
    int  texturePtr, textureFormat, textureW, textureH;
    u32* activePalette;             /* EMBEDDED palette (formats 16-19); 0 = the screen palette */
    int  activePalCount;

    /* managed textures (vCreateTexture -> handle) */
    Tex* managed; int managedCap, nextTexHandle;

    /* lighting */
    int   ambientScene;
    float lights[MAXL][16];
    bool  lightUsed[MAXL];
    int   matDiffR,matDiffG,matDiffB, matSpecR,matSpecG,matSpecB;
    int   matEmR,matEmG,matEmB, matAmbR,matAmbG,matAmbB;
    float matShininess;
    float camX,camY,camZ;

    /* reused vertex buffers, grown on demand */
    float* bClip;  /* count*4 */
    int*   bCol;   /* count   */
    float* bUv;    /* count*2 */
    int    bCap;
    void   ensureVertCap(int count);

    /* helpers */
    float vx(int p) const; float vy(int p) const; float vz(int p) const;
    void  putV(int p,float x,float y,float z);
    void  readMatrix(int ptr, float* out);
    void  projectImpl(int dst,int src,int count,bool v4);
    void  ensureZ();
    bool  zTest(i32 d,i32 buf) const;
    float fogFactor(float z) const;
    int   wrapCoord(int c,int size) const;
    u32   palLookup(int i) const;
    int   read4(int idx) const;
    u32   texelAt(int tx,int ty) const;
    u32   texelNearest(float u,float v) const;
    u32   texelBilinear(float u,float v) const;
    u32   texSample(float u,float v) const;
    u32*  decodeTexPalette(int format,int palPtr,int count,int* outCount);
    u32   diffuseArgb(int p) const;
    u32   shadeVertex(int vertPtr,int normalPtr,u32 baseArgb);
    int   idxAt(int idxPtr,int i) const;
    void  renderIndexed(int vertsP,int uvsP,int diffP,int normP,int count,int idxPtr,int idxCount,int topology);
    void  emitTri(int a,int b,int c,int count,bool tex);
    void  clipRaster(const float* c0,const float* c1,const float* c2,
                     u32 col0,u32 col1,u32 col2,
                     const float* uv0,const float* uv1,const float* uv2,bool tex);
    void  rasterTri(const float* c0,const float* c1,const float* c2,
                    u32 col0,u32 col1,u32 col2,
                    const float* uv0,const float* uv1,const float* uv2,bool tex);
};

#endif /* WOLPHUN_RENDERER3D_H */
