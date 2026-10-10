/* Renderer3D.h - rasterizador de software do subsistema 3D do mophun (VMGP3D).
 * Port de src/wolphun/vm/Renderer3D.java.
 *
 * Pilha de matrizes (Fixed 14-bit, column-major default), transformacao de vertices,
 * recorte no near-plane e preenchimento de triangulos com z-buffer, gouraud e textura
 * perspective-correct.  clip = projection * current * v.
 *
 * Formatos: posicao/matriz = Fixed/16384; UV = /1024; angulo = (d&0xFFF)/4096 volta.
 *
 * Diferencas em relacao ao Java:
 *  - SEM libm: sqrt/sin/cos/tan/pow/exp/floor sao implementados aqui (fastmath), porque
 *    o GCCE do Symbian nao garante math.h sem OpenC.
 *  - Removido todo o codigo de diagnostico por variavel de ambiente e o save-state.
 *  - Sem supersampling (rs=1): o z-buffer tem o tamanho da tela.
 *  - Buffers de vertice sao REUTILIZADOS (crescem sob demanda) em vez de alocados por
 *    chamada - alocar ~100 KB por primitiva mataria um aparelho.
 *  - HashMap de texturas gerenciadas -> array crescente indexado pelo handle.
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

    /* ---------- ponto fixo / matematica do SDK ---------- */
    static float f2f(int fx){ return fx / 16384.0f; }
    static int   ff(float v){ return (int)(v * 16384.0f); }
    static float uv2f(i16 u){ return u / 1024.0f; }
    /* SDK: "Degree, range 0 - 4095" => a volta COMPLETA sao 4096 unidades, logo o divisor
       e 4096 e NAO 4095 (com 4095 cada angulo saia 0,024% maior e o erro se acumulava na
       tesselacao de domo/ceu e na camera). */
    static float ang2rad(int d){ return (d & 0xFFF) / 4096.0f * 6.28318530718f; }
    static float ang2deg(int d){ return (d & 0xFFF) / 4096.0f * 360.0f; }

    int vMul(int a,int b){ return (int)((((i64)a)*b) >> 14); }
    int vDiv(int a,int b){ return b==0 ? 0 : (int)((((i64)a)<<14)/b); }
    int vPow(int val,int exp);
    int vSqrt(int v);
    int vSin(int v);
    int vCos(int v);
    int vTan(int v);

    /* ---------- vetores (NativeVector3D 12B) ---------- */
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

    /* ---------- frustum / planos / colisao ---------- */
    int  boxInViewFrustum(int b);
    void createPlaneFromPoly(int dest,int poly);
    int  collisionPointBox(int point,int box);
    int  collisionBoxBox(int b1,int b2);
    int  collisionVectorPoly(int collPos,int lineV,int polyV,int plane);
    int  collisionVectorPlane(int collPos,int lineV,int plane){ return collisionVectorPoly(collPos,lineV,0,plane); }

    /* ---------- matrizes ---------- */
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

    /* ---------- estado ---------- */
    void setViewport(int l,int t,int w,int h){ vpL=l; vpT=t; vpW=w; vpH=h; }
    void setRenderState(int state,int value);
    void setFogColor(int rgb){ fogColor = rgb & 0xffffff; }
    int  getInteger(int param);
    int  getZBufferValue(int x,int y);
    void clearDepth();
    /* limpa o z-buffer SO se ja foi alocado (3D ativo). Chamado a cada vFlipScreen: o
       z-buffer do mophun e scratch por-frame, mas jogos como Rally Pro Contest NUNCA
       chamam vSetZBuffer nem vClearScreen. Sem limpar, o z acumulava a menor
       profundidade ja vista por pixel -> geometria nova falhava o z-test e SUMIA. */
    void clearDepthPerFrame();

    /* ---------- texturas ---------- */
    int  setTexture(int dataPtr,int format,int lods,int mipCount);
    int  createTexture(int texPtr);
    void setActiveTexture(int handle);
    void deleteTexture(int handle);
    void deleteAllTextures();

    /* ---------- iluminacao ---------- */
    void setAmbientLight(int rgb);
    void setCameraPos(int p);
    void resetLights();
    void setLight(int index,int p);
    void setMaterial(int p);
    void setMaterial2(int p);
    void lightPoint(int argsPtr);

    /* ---------- desenho ---------- */
    void renderPrimitive(int meshPtr,int topology);
    void renderPrimitiveIndexed(int idxPtr,int idxCount,int meshPtr,int topology);
    void drawPolygon(int p1,int p2,int p3);
    void drawBillboard(int bb);

    /* filtro de textura forcado pelas Configuracoes: 0=segue o jogo, 1=LINEAR, 2=NEAREST */
    int texFilterForce;

    /* instrumentacao (diagnostico; custo desprezivel) */
    i64 trisDrawn, pixels3d, drawCalls;

private:
    enum { MAXL = 8 };
    struct Tex { int format, w, h, dataPtr; u32* palette; int palCount; bool used; };

    Pip2*   vm;
    Screen* scr;

    /* matrizes row-major float[16] (m[r*4+c]); v' = M*v */
    float current[16], projection[16], light[16];
    int   matrixOrientation;        /* 0x2000 = ColumnMajor (default) */

    int   vpL, vpT, vpW, vpH;
    /* escala do z inteiro: 1.0 = 1<<22 */
    enum { KZShift = 22 };
    static const i32 KZFar = 0x7fffffff;   /* vazio */
    static const i32 KZEps = 1 << 6;       /* ~1,5e-5: o antigo +1e-5f dos decals coplanares */
    /* JA TENTEI 16 BITS AQUI (como faz a VM oficial) e REVERTI: no Carmageddon 3D o
       quantizado derruba 7% dos pixels por z-fighting e dobra o desvio medio de cor,
       enquanto o ganho -- menos trafego de RAM -- e invisivel no PC (o buffer cabe no
       cache dele) e eu nao tenho como medir no aparelho. Custo medido contra beneficio
       suposto: nao compensa. Se um dia houver medicao no E63, a conversao e um shift de
       6 e esta descrita neste comentario. */

    /* Z-BUFFER INTEIRO (10.22 com sinal, KZShift abaixo). Era float: cada teste de
       profundidade era um __aeabi_fcmplt no ARM11, um por pixel desenhado -- 153 milhoes
       deles numa janela de 40M instrucoes do Rally. O emulador OFICIAL da mophun usa
       z de 16 bits inteiro; 10.22 e mais folgado que isso e ainda cabe num i32.
       Precisao 2,4e-7 (o z em NDC vive perto de [-1,1]); o antigo epsilon de 1e-5 do
       caminho com blend virou KZEps. KZFar e o "vazio". */
    i32* zbuf; int zW, zH;

    /* render states (defaults = comportamento verificado no acervo de jogos) */
    int  cullMode;                  /* DEFAULT = CounterClockwise (2) -> culling LIGADO */
    bool textureMode;
    bool transp3d;                  /* state 15: indice 0 da textura = transparente */
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

    /* textura ativa */
    int  texturePtr, textureFormat, textureW, textureH;
    u32* activePalette;             /* paleta EMBUTIDA (formatos 16-19); 0 = paleta de tela */
    int  activePalCount;

    /* texturas gerenciadas (vCreateTexture -> handle) */
    Tex* managed; int managedCap, nextTexHandle;

    /* iluminacao */
    int   ambientScene;
    float lights[MAXL][16];
    bool  lightUsed[MAXL];
    int   matDiffR,matDiffG,matDiffB, matSpecR,matSpecG,matSpecB;
    int   matEmR,matEmG,matEmB, matAmbR,matAmbG,matAmbB;
    float matShininess;
    float camX,camY,camZ;

    /* buffers de vertice reutilizados (crescem sob demanda) */
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
