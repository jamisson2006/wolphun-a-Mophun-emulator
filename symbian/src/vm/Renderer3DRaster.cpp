/* Renderer3DRaster.cpp - parte do Renderer3D: emissao de primitivas, recorte no
 * near-plane e rasterizacao de triangulos (z-buffer, gouraud, textura
 * perspective-correct, fog e blending).
 * Port de src/wolphun/vm/Renderer3D.java. Ver Renderer3D.h.
 */
#include "Renderer3D.h"
#include "Pip2.h"
#include "Screen.h"
#include "fastmath.h"

static void mulM(const float* a,const float* b,float* r){
    for (int i=0;i<4;i++)
        for (int j=0;j<4;j++){
            float s=0;
            for (int k=0;k<4;k++) s += a[i*4+k]*b[k*4+j];
            r[i*4+j]=s;
        }
}
static void xf(const float* m,float x,float y,float z,float w,float* out){
    out[0]=m[0]*x+m[1]*y+m[2]*z+m[3]*w;
    out[1]=m[4]*x+m[5]*y+m[6]*z+m[7]*w;
    out[2]=m[8]*x+m[9]*y+m[10]*z+m[11]*w;
    out[3]=m[12]*x+m[13]*y+m[14]*z+m[15]*w;
}
static int clampB(int v){ return v<0?0:(v>255?255:v); }

/* buffers de vertice reutilizados: alocar ~100 KB por primitiva mataria o aparelho */
void Renderer3D::ensureVertCap(int count){
    if (count <= bCap) return;
    int ncap = bCap ? bCap : 256;
    while (ncap < count) ncap *= 2;
    delete[] bClip; delete[] bCol; delete[] bUv;
    bClip = new float[ncap*4];
    bCol  = new int[ncap];
    bUv   = new float[ncap*2];
    bCap  = ncap;
}

int Renderer3D::idxAt(int idxPtr,int i) const {
    return idxPtr==0 ? i : (int)(i16)vm->read16(idxPtr + i*2);
}

/* VERTEXLIST: v@0, uv@4, diff@8, spec@12, normal@16, count@20 */
void Renderer3D::renderPrimitive(int meshPtr,int topology){
    int vertsP=vm->read32(meshPtr), uvsP=vm->read32(meshPtr+4);
    int diffP=vm->read32(meshPtr+8), normP=vm->read32(meshPtr+16);
    int count=vm->read16(meshPtr+20)&0xffff;
    if (vertsP==0 || count<3) return;
    renderIndexed(vertsP, uvsP, diffP, normP, count, 0, 0, topology);
}

void Renderer3D::renderPrimitiveIndexed(int idxPtr,int idxCount,int meshPtr,int topology){
    int vertsP=vm->read32(meshPtr), uvsP=vm->read32(meshPtr+4);
    int diffP=vm->read32(meshPtr+8), normP=vm->read32(meshPtr+16);
    int count=vm->read16(meshPtr+20)&0xffff;
    if (vertsP==0 || idxPtr==0 || idxCount<3) return;
    renderIndexed(vertsP, uvsP, diffP, normP, count, idxPtr, idxCount, topology);
}

void Renderer3D::renderIndexed(int vertsP,int uvsP,int diffP,int normP,int count,
                               int idxPtr,int idxCount,int topology){
    ensureZ();
    ensureVertCap(count);
    float mvp[16];
    mulM(projection, current, mvp);
    bool tex = (uvsP!=0 && texturePtr!=0 && textureW>0);
    /* T&L so quando o jogo habilita LIGHTINGENABLE e fornece normais */
    bool lit = lightingEnable && normP!=0;

    for (int i=0;i<count;i++){
        int vp = vertsP + i*12;
        xf(mvp, f2f(vm->read32(vp)), f2f(vm->read32(vp+4)), f2f(vm->read32(vp+8)),
           1.0f, &bClip[i*4]);
        u32 base = (diffP!=0) ? diffuseArgb(diffP+i*4) : 0xffffffffu;
        bCol[i] = (int)(lit ? shadeVertex(vp, normP+i*12, base) : base);
        if (tex){
            bUv[i*2]   = uv2f((i16)vm->read16(uvsP+i*4));
            bUv[i*2+1] = uv2f((i16)vm->read16(uvsP+i*4+2));
        } else {
            bUv[i*2]=0.0f; bUv[i*2+1]=0.0f;
        }
    }

    /* O index list pode conter VARIAS strips/fans separadas por -1 (0xFFFF), conforme o
       SDK ("Multiple strips and fans are supported using indexnr -1 as delimiter"). Cada
       sub-run REINICIA a paridade da strip e o pivot do fan; sem isso os triangulos apos
       o -1 saiam com winding invertido e o backface culling os apagava -> faces do
       carro/mapa "sumindo" ao mover. */
    int n = (idxPtr!=0) ? idxCount : count;
    int runStart = 0;
    while (runStart < n){
        int runEnd = runStart;
        while (runEnd < n && idxAt(idxPtr,runEnd) >= 0) runEnd++;   /* -1 ou fim termina o run */
        int len = runEnd - runStart;
        if (topology == 0x10){            /* TriangleStrip: paridade LOCAL ao run */
            for (int k=0; k+2<len; k++){
                int a=idxAt(idxPtr,runStart+k);
                int b=idxAt(idxPtr,runStart+k+1);
                int c=idxAt(idxPtr,runStart+k+2);
                if (k & 1){ int t=b; b=c; c=t; }
                emitTri(a,b,c,count,tex);
            }
        } else if (topology == 0x20){     /* TriangleFan: pivot = 1o vertice DO run */
            int a = idxAt(idxPtr,runStart);
            for (int k=1; k+1<len; k++)
                emitTri(a, idxAt(idxPtr,runStart+k), idxAt(idxPtr,runStart+k+1), count, tex);
        } else {                          /* TriangleList */
            for (int k=0; k+2<len; k+=3)
                emitTri(idxAt(idxPtr,runStart+k), idxAt(idxPtr,runStart+k+1),
                        idxAt(idxPtr,runStart+k+2), count, tex);
        }
        runStart = runEnd + 1;            /* pula o delimitador -1 */
    }
}

/* emite 1 triangulo (com bounds-check dos indices contra a contagem de vertices) */
void Renderer3D::emitTri(int a,int b,int c,int count,bool tex){
    if (a<0||b<0||c<0||a>=count||b>=count||c>=count) return;
    clipRaster(&bClip[a*4], &bClip[b*4], &bClip[c*4],
               (u32)bCol[a], (u32)bCol[b], (u32)bCol[c],
               &bUv[a*2], &bUv[b*2], &bUv[c*2], tex);
}

/* vDrawPolygon(v1,v2,v3): NativeVertexGST (position Vector4@0, diffuse@16, spec@20, uv@24) */
void Renderer3D::drawPolygon(int p1,int p2,int p3){
    ensureZ();     /* garante z-buffer (jogos que usam vDrawPolygon sem vRenderPrimitive antes) */
    float mvp[16];
    mulM(projection, current, mvp);
    float cl[3][4], uv[3][2];
    u32 col[3];
    int ps[3] = {p1,p2,p3};
    bool tex = (texturePtr!=0 && textureW>0);
    for (int i=0;i<3;i++){
        int p = ps[i];
        xf(mvp, f2f(vm->read32(p)), f2f(vm->read32(p+4)), f2f(vm->read32(p+8)), 1.0f, cl[i]);
        col[i] = diffuseArgb(p+16);
        if (tex){
            uv[i][0]=uv2f((i16)vm->read16(p+24));
            uv[i][1]=uv2f((i16)vm->read16(p+26));
        } else { uv[i][0]=0.0f; uv[i][1]=0.0f; }
    }
    clipRaster(cl[0],cl[1],cl[2], col[0],col[1],col[2], uv[0],uv[1],uv[2], tex);
}

/* vDrawBillboard(NativeBillboard): quad TEXTURIZADO que sempre encara a camera.
   NativeBillboard(56B): pos@0(12B), w@12, h@16, uv0@20, uv1@24, uv2@28, uv3@32,
   color0-3@36-48, rot@52, pivot@54.
   Transforma o centro pela matriz ATUAL (view) -> monta 4 cantos alinhados a tela ->
   projeta e rasteriza 2 triangulos com a textura ativa. */
void Renderer3D::drawBillboard(int bb){
    if (bb==0) return;
    ensureZ();
    float px=vx(bb), py=vy(bb), pz=vz(bb);
    float w=f2f(vm->read32(bb+12)), h=f2f(vm->read32(bb+16));
    float c[4];
    xf(current, px,py,pz, 1.0f, c);                 /* centro em view space */
    float hw=w*0.5f, hh=h*0.5f;
    float vs[4][3] = {
        {c[0]-hw, c[1]+hh, c[2]}, {c[0]+hw, c[1]+hh, c[2]},
        {c[0]+hw, c[1]-hh, c[2]}, {c[0]-hw, c[1]-hh, c[2]}
    };
    float cl[4][4];
    for (int i=0;i<4;i++) xf(projection, vs[i][0],vs[i][1],vs[i][2], 1.0f, cl[i]);
    float uv[4][2];
    const int uo[4] = {20,24,28,32};
    for (int i=0;i<4;i++){
        uv[i][0]=uv2f((i16)vm->read16(bb+uo[i]));
        uv[i][1]=uv2f((i16)vm->read16(bb+uo[i]+2));
    }
    bool tex = (texturePtr!=0 && textureW>0);
    const u32 W = 0xffffffffu;
    int savedCull = cullMode; bool savedT = transp3d;
    cullMode = 0; transp3d = true;    /* billboard: nunca cull + indice 0 transparente
                                         (e o recorte da arvore/sprite) */
    clipRaster(cl[0],cl[1],cl[2], W,W,W, uv[0],uv[1],uv[2], tex);
    clipRaster(cl[0],cl[2],cl[3], W,W,W, uv[0],uv[2],uv[3], tex);
    cullMode = savedCull; transp3d = savedT;
}

/* ---------------- recorte no near-plane ---------------- */
static void lerp4(const float* a,const float* b,float t,float* o){
    o[0]=a[0]+(b[0]-a[0])*t; o[1]=a[1]+(b[1]-a[1])*t;
    o[2]=a[2]+(b[2]-a[2])*t; o[3]=a[3]+(b[3]-a[3])*t;
}
static u32 lerpCol(u32 a,u32 b,float t){
    int ar=(a>>16)&0xff, ag=(a>>8)&0xff, ab=a&0xff;
    int r=(int)(ar+(((int)((b>>16)&0xff))-ar)*t);
    int g=(int)(ag+(((int)((b>>8)&0xff))-ag)*t);
    int bl=(int)(ab+(((int)(b&0xff))-ab)*t);
    return 0xff000000u|((u32)clampB(r)<<16)|((u32)clampB(g)<<8)|(u32)clampB(bl);
}

/* Recorta o triangulo contra o NEAR-plane (z_clip>=0) e rasteriza o resultado. Sem isto,
   triangulos que cruzam o near eram DESCARTADOS (geometria "some" ao chegar perto) e
   vertices com w minusculo projetavam p/ coords extremas (o "triangulo preto").
   Sutherland-Hodgman: interpola posicao(clip4)+cor+uv no cruzamento d=z_clip=0;
   saida 0/3/4 vertices -> fan de triangulos. */
void Renderer3D::clipRaster(const float* c0,const float* c1,const float* c2,
                            u32 col0,u32 col1,u32 col2,
                            const float* uv0,const float* uv1,const float* uv2,bool tex){
    /* caso comum: todos em frente do near -> rasteriza direto, sem copiar nada */
    if (c0[2]>=0 && c1[2]>=0 && c2[2]>=0){
        rasterTri(c0,c1,c2,col0,col1,col2,uv0,uv1,uv2,tex);
        return;
    }
    if (c0[2]<0 && c1[2]<0 && c2[2]<0) return;     /* totalmente atras do near */

    const float* cs[3] = {c0,c1,c2};
    u32 cols[3] = {col0,col1,col2};
    const float* uvs[3] = {uv0,uv1,uv2};
    float oc[4][4], ouv[4][2];
    u32 ocol[4];
    int n=0;
    for (int i=0;i<3 && n<4;i++){
        int j=(i+1)%3;
        float di=cs[i][2], dj=cs[j][2];
        bool ini = di>=0, inj = dj>=0;
        if (ini){
            oc[n][0]=cs[i][0]; oc[n][1]=cs[i][1]; oc[n][2]=cs[i][2]; oc[n][3]=cs[i][3];
            ocol[n]=cols[i];
            ouv[n][0]=uvs[i][0]; ouv[n][1]=uvs[i][1];
            n++;
        }
        if (ini!=inj && n<4){
            float t = di/(di-dj);
            lerp4(cs[i],cs[j],t,oc[n]);
            ocol[n]=lerpCol(cols[i],cols[j],t);
            ouv[n][0]=uvs[i][0]+(uvs[j][0]-uvs[i][0])*t;
            ouv[n][1]=uvs[i][1]+(uvs[j][1]-uvs[i][1])*t;
            n++;
        }
    }
    for (int i=1;i+1<n;i++)
        rasterTri(oc[0],oc[i],oc[i+1], ocol[0],ocol[i],ocol[i+1], ouv[0],ouv[i],ouv[i+1], tex);
}

/* ---------------- rasterizacao ---------------- */
/* SUBDIVISAO PERSPECTIVA.
   A correcao de perspectiva exige u = un/iws em CADA pixel, e divisao e a operacao mais
   cara que existe neste alvo: o S60 3rd compila com SOFTVFPMODE, logo __aeabi_fdiv e uma
   chamada de funcao de ~200 ciclos. Medido no Rally Pro Contest, 88% do tempo total esta
   no laco por pixel, e a divisao e a maior fatia isolada dele.
   A saida classica (Quake, e o que os rasterizadores comerciais de ARM fazem): dividir so
   a cada KSpan pixels e interpolar u/v LINEARMENTE no meio. O erro e o desvio entre a
   hiperbole real e a corda que liga os dois extremos -- invisivel em spans curtos, e os
   triangulos aqui sao pequenos (media medida: 56 pixels por triangulo no Rally).
   KSpan=8 troca 8 divisoes por 1: a reciproca do fim de um span e a do inicio do
   seguinte, entao ela e CARREGADA, nao recalculada.
   O 8 nao e chute: e o que a VM OFICIAL da mophun usa. No disassembly dela o reseed do
   span e guardado por `tst rX, #7` e o avanco e `add r1, r1, r3, lsl #3` -- span de 8.
   Com 8 o desvio da aproximacao afim cai pela metade e, como a reciproca agora custa
   pouco perto do resto, o troco e barato. */
static const int KSpan = 8;
/* iws pode chegar perto de zero em poligonos quase paralelos ao olho; sem piso a
   reciproca viraria inf e contaminaria o span INTEIRO (antes so sujava um pixel). */
static const float KIwsMin = 1e-9f;
/* reciprocas de 1..KSpan: o span final de cada linha e mais curto, e dividir por ele
   anularia o ganho. Indice 0 nunca e usado. */
static const float KInvLen[KSpan+1] = {
    0.0f, 1.0f/1, 1.0f/2, 1.0f/3, 1.0f/4, 1.0f/5, 1.0f/6, 1.0f/7, 1.0f/8
};

/* ================== PONTO FIXO ==================
   O laco por pixel nao tem mais NENHUM float. Motivo medido: o S60 3rd compila com
   SOFTVFPMODE e cada operacao float e uma chamada __aeabi_*; a VM oficial da mophun nao
   importa UM UNICO helper de float de precisao simples (nem fadd, nem fmul, nem fdiv) --
   o pipeline 3D dela e inteiro de ponta a ponta. Aqui a conversao e por ETAPAS: o laco
   por pixel ja e inteiro; o SETUP por triangulo e por span continua em float, porque
   roda 1x por span de 8 pixels (e nao 1x por pixel) e e onde mora o risco de escala.
   Formatos: barycentricas e UV em 16.16, z em 10.22 (KZShift), fog em 16.16.
   Os limites abaixo existem para que nenhuma conta do laco possa estourar o i32 mesmo
   com triangulo degenerado -- ver a prova de nao-estouro das cores junto ao teste de
   aresta. */
static inline i32 fxClampRound(float f, float lim){
    if (f >  lim) f =  lim;
    else if (f < -lim) f = -lim;
    return (i32)(f >= 0.0f ? (f + 0.5f) : (f - 0.5f));
}
/* LIMITES DE PASSO, um por grandeza.
   Eles existem so para garantir que nenhuma soma do laco estoure o i32; nao sao
   "arredondamento". Dimensiona-los por baixo e um erro que ja cometi aqui: com o passo
   de z preso em 0,25 por pixel, as paredes e a pista do Carmageddon (que mudam de
   profundidade depressa) tinham o z truncado, o teste de profundidade recusava o
   fragmento e 45% dos pixels do quadro sumiam. A conta de cada limite esta ao lado. */
static const float KStepB   = 2097152.0f;    /* barycentrica: 32,0 em 16.16  */
static const float KStepZ   = 4194304.0f;    /* z: 1,0 em 10.22 (o NDC inteiro cabe) */
static const float KStepUV  = 16777216.0f;   /* un/vn/iws na escala SIW */
static const float KStepLim = KStepB;
static const float KSeedLim = 1073741824.0f; /* semente de UV */
static const float KSeedLimZ= 536870912.0f;  /* semente de z: 128,0 em 10.22 */
static const float KSeedLimB= 16777216.0f;   /* semente de barycentrica */
static inline i32 fx16(float f, float lim){ return fxClampRound(f*65536.0f, lim); }
static inline i32 fxZ (float f, float lim){ return fxClampRound(f*4194304.0f, lim); }
static const i32 FX_ONE = 65536;
/* 1/len em 16.16, para dividir pelo comprimento do span (o ultimo da linha e curto) */
static const i32 KInvLenFx[KSpan+1] = { 0, 65536, 32768, 21845, 16384, 13107, 10922, 9362, 8192 };
static inline i32 mulInvLen(i32 d,int len){ return (i32)(((i64)d * KInvLenFx[len]) >> 16); }
/* o cruzamento de uma aresta quase horizontal corre centenas de pixels por linha; satura
   bem fora da tela, onde a resposta ("linha inteira" ou "linha vazia") ja esta certa */
/* satura o acumulador DA LINHA. Com o acumulador preso em +-lim e o passo preso em
   KStep*, 352 somas dentro da linha nao alcancam 2^31. */
static inline i32 sat(i32 v, i32 lim){ return v > lim ? lim : (v < -lim ? -lim : v); }
static const i32 KSatB = 16777216;     /* 2^24: +  352*2^21 = 7,6e8  */
static const i32 KSatZ = 536870912;    /* 2^29: +  352*2^22 = 2,0e9  */
static const i32 KSatU = 536870912;    /* 2^29 */
static inline i32 clampKE(i32 v){
    if (v >  536870912) return  536870912;
    if (v < -536870912) return -536870912;
    return v;
}
/* satura em +-2^30 ao estreitar de 64 p/ 32 bits: so acontece em triangulo degenerado,
   e saturar degrada a textura num canto em vez de dar lixo no quadro inteiro */
static inline i32 narrow30(i64 v){
    if (v >  1073741824LL) return  1073741824;
    if (v < -1073741824LL) return -1073741824;
    return (i32)v;
}

void Renderer3D::rasterTri(const float* c0,const float* c1,const float* c2,
                           u32 col0,u32 col1,u32 col2,
                           const float* uv0,const float* uv1,const float* uv2,bool tex){
    if (c0[3]<=1e-4f || c1[3]<=1e-4f || c2[3]<=1e-4f) return;   /* near-clip simples */
    trisDrawn++;
    float iw0=1.0f/c0[3], iw1=1.0f/c1[3], iw2=1.0f/c2[3];
    float x0=vpL+(c0[0]*iw0*0.5f+0.5f)*vpW, y0=vpT+(0.5f-c0[1]*iw0*0.5f)*vpH, z0=c0[2]*iw0;
    float x1=vpL+(c1[0]*iw1*0.5f+0.5f)*vpW, y1=vpT+(0.5f-c1[1]*iw1*0.5f)*vpH, z1=c1[2]*iw1;
    float x2=vpL+(c2[0]*iw2*0.5f+0.5f)*vpW, y2=vpT+(0.5f-c2[1]*iw2*0.5f)*vpH, z2=c2[2]*iw2;

    float area = (x1-x0)*(y2-y0) - (x2-x0)*(y1-y0);
    if (area == 0.0f) return;
    /* backface culling honrando cullMode do jogo (1=CW, 2=CCW, 0=None). Descarta faces de
       tras -> tira "triangulos pretos" que o jogo queria esconder. */
    if (cullMode==1 && area>0) return;
    if (cullMode==2 && area<0) return;

    int minX=(int)wpFloor(x0<x1?(x0<x2?x0:x2):(x1<x2?x1:x2));
    int maxX=(int)wpFloor((x0>x1?(x0>x2?x0:x2):(x1>x2?x1:x2))) + 1;
    int minY=(int)wpFloor(y0<y1?(y0<y2?y0:y2):(y1<y2?y1:y2));
    int maxY=(int)wpFloor((y0>y1?(y0>y2?y0:y2):(y1>y2?y1:y2))) + 1;
    int lo, hi;
    lo = (vpL > scr->clipX0) ? vpL : scr->clipX0;  if (minX < lo) minX = lo;
    hi = (vpL+vpW < scr->clipX1) ? (vpL+vpW) : scr->clipX1;  if (maxX > hi) maxX = hi;
    lo = (vpT > scr->clipY0) ? vpT : scr->clipY0;  if (minY < lo) minY = lo;
    hi = (vpT+vpH < scr->clipY1) ? (vpT+vpH) : scr->clipY1;  if (maxY > hi) maxY = hi;
    if (minX>=maxX || minY>=maxY) return;

    /* SHADEMODE FLAT: usa o vertice provocante (v0) p/ todo o triangulo */
    if (shadeMode==1){ col1=col0; col2=col0; }
    int r0=(col0>>16)&0xff, g0=(col0>>8)&0xff, b0=col0&0xff, a0=(int)(col0>>24);
    int r1=(col1>>16)&0xff, g1=(col1>>8)&0xff, b1=col1&0xff, a1=(int)(col1>>24);
    int r2=(col2>>16)&0xff, g2=(col2>>8)&0xff, b2=col2&0xff, a2=(int)(col2>>24);

    /* O blend com o color-buffer e comandado pelo COLORBUFFER_BLENDMODE (estado 20)
       SOZINHO: os tres tutoriais de blending do SDK (alpha3d, alphaadd3d,
       modulateblend3d) setam SO o estado 20 e NUNCA tocam em ALPHAENABLE(12). Exigir o 12
       fazia esses casos sairem opacos (no Carmageddon 3D farois/pedestres/mao do
       motorista apareciam com fundo solido). Mantido o caminho inverso: o Rally liga SO o
       ALPHAENABLE (sem estado 20) p/ a sombra do carro -- por isso o OR. */
    bool blend = (colorBlend!=0) || alphaEnable;
    int fr=(fogColor>>16)&0xff, fg=(fogColor>>8)&0xff, fb=fogColor&0xff;
    float inv = 1.0f/area;

    /* RASTERIZACAO INCREMENTAL.
       w0 e w1 sao funcoes LINEARES de (x,y), entao recalcula-las do zero em cada pixel e
       desperdicio: bastam as derivadas, constantes no triangulo, e uma soma por passo.
       Isto importa muito neste alvo: o S60 3rd compila com SOFTVFPMODE (ARM11 sem FPU
       utilizavel), ou seja cada operacao `float` e uma CHAMADA DE FUNCAO. O laco antigo
       gastava ~12 multiplicacoes + 8 subtracoes por pixel so para achar w0/w1/depth/iws;
       agora sao 4 somas. Medido no Nokia E63: jogos 3D rodavam a ~5 fps.
         d(w0)/dx = (y1-y2)*inv     d(w0)/dy = (x2-x1)*inv
         d(w1)/dx = (y2-y0)*inv     d(w1)/dy = (x0-x2)*inv
       depth e iws sao combinacoes lineares de w0/w1, logo tambem sao incrementais.
       Os incrementos ficam na clausula do `for` de proposito: assim os varios `continue`
       do corpo (z-test, texel transparente) continuam avancando os acumuladores. */
    const float dw0dx = (y1-y2)*inv;
    const float dw1dx = (y2-y0)*inv;
    /* w2 = 1-w0-w1  =>  dw2 = -(dw0+dw1) */
    const float ddepthdx = dw0dx*z0 + dw1dx*z1 - (dw0dx+dw1dx)*z2;
    const float diwsdx   = dw0dx*iw0 + dw1dx*iw1 - (dw0dx+dw1dx)*iw2;
    const bool  needIws  = (tex || fogEnable);
    /* ESCALA DO RESEED INTEIRO.
       iws (=1/w) dentro do triangulo e combinacao convexa de iw0..iw2, logo nunca passa
       do maior deles: da para escolher UMA escala por triangulo que mantenha iws_fx em
       (0, 2^17] e, com isso, trocar a divisao de ponto flutuante do span por uma divisao
       INTEIRA -- que e exatamente o que a VM oficial da mophun faz (`uidivmod` com
       numerador 2^30, uma por span de 8 pixels).
       Identidade usada: com un e iws na MESMA escala,
           (un_fx * (2^30/iws_fx)) >> 14  ==  (un/iws) em 16.16.
       2^17 e o meio-termo medido entre duas perdas opostas: escala maior da mais
       resolucao ao passo por pixel de iws, mas encolhe a reciproca e piora a textura
       perto da camera; menor faz o contrario. */
    const float iwMx = (iw0>iw1?(iw0>iw2?iw0:iw2):(iw1>iw2?iw1:iw2));
    const float SIW  = (iwMx > 1e-20f) ? (131072.0f/iwMx) : 1.0f;
    /* r (=2^30/iws_fx) de volta para profundidade de vista (1/iws), so para o fog */
    const float KRtoView = SIW / 1073741824.0f;
    /* numeradores de u e v (u = un/iws): tambem lineares em w0/w1 */
    const float ku0 = uv0[0]*iw0, ku1 = uv1[0]*iw1, ku2 = uv2[0]*iw2;
    const float kv0 = uv0[1]*iw0, kv1 = uv1[1]*iw1, kv2 = uv2[1]*iw2;
    const float dundx = dw0dx*ku0 + dw1dx*ku1 - (dw0dx+dw1dx)*ku2;
    const float dvndx = dw0dx*kv0 + dw1dx*kv1 - (dw0dx+dw1dx)*kv2;

    /* Cada LINHA e semeada com o valor EXATO (poucas operacoes por linha, irrelevantes) e
       so o avanco em X e incremental. Acumular tambem em Y fazia o desvio de ponto
       flutuante somar pelo triangulo inteiro: medido, 11% dos bytes do quadro mudavam
       porque u/v derivavam o bastante para cair no texel vizinho. Semeando por linha, o
       erro fica limitado a uma varredura (<=176 passos) e o quadro volta a bater. */
    /* LIMITES EXATOS DA VARREDURA.
       O laco andava a CAIXA ENVOLVENTE inteira e descartava com `continue` o que caisse
       fora do triangulo. Medido no Rally Pro Contest: de 479,8 milhoes de iteracoes do
       laco, so 153,0 milhoes (31,9%) estao DENTRO -- 68% do trabalho era jogado fora, e
       cada iteracao descartada ainda pagava 6 somas + 2 subtracoes de ponto flutuante,
       que no ARM11 sem FPU sao 8 CHAMADAS DE FUNCAO. Era a maior fatia isolada do custo,
       maior que a divisao.
       Como w_i e linear em x, a aresta i cruza zero num x conhecido: basta resolver
       w_i(x)=0 e intersectar os tres intervalos. As derivadas sao constantes no
       triangulo, entao as reciprocas saem UMA VEZ (3 divisoes por triangulo) e cada linha
       custa 3 multiplicacoes. O teste w<0 continua no corpo como rede de seguranca contra
       arredondamento -- agora ele quase nunca dispara, mas sai barato. */
    const float dw2dx = -(dw0dx + dw1dx);
    const float dwdx[3] = { dw0dx, dw1dx, dw2dx };
    float recip[3];
    {   /* LIMIAR DE ARESTA "CHATA". Era 1e-12, so para evitar divisao por zero -- e esse
           valor QUEBRA o caminhamento por linha: uma aresta quase horizontal (dwdx ~
           1e-11 por erro de arredondamento num quad do chao) tem cruzamento a 1e11
           pixels e passo por linha maior ainda; com o passo saturado, o cruzamento
           LEVA VARIAS LINHAS para atravessar a tela e os limites saem errados no caminho.
           Medido no Carmageddon 3D: 17% das linhas davam vazias por engano e as
           iteracoes do laco caiam 42% -- metade do chao sumia.
           O limiar certo e geometrico: abaixo de 1/8192, w_e nao muda o bastante ao longo
           da linha para trocar de sinal, logo a aresta nao restringe x -- ela so diz
           "linha inteira" ou "linha vazia", que e exatamente o ramo chato. */
        const float KFlat = 1.0f/8192.0f;
        for (int e=0;e<3;e++)
            recip[e] = (dwdx[e]>KFlat || dwdx[e]<-KFlat) ? (1.0f/dwdx[e]) : 0.0f;
    }
    const int rowW = maxX - minX;

    const i32 IDW0 = fx16(dw0dx, KStepB);
    const i32 IDW1 = fx16(dw1dx, KStepB);
    const i32 IDZ  = fxZ (ddepthdx, KStepZ);
    /* o caminho bilinear continua em float (raro: so com filtro ligado). Resolvido UMA
       vez por triangulo para nao virar um `if` por pixel. */
    const bool texLinear = tex && (texFilterForce==1
        || (texFilterForce==0 && (filterMode==2 || filterMode==8 || filterMode==32)));
    const float texWf = tex ? (float)textureW : 1.0f;
    const float texHf = tex ? (float)textureH : 1.0f;
    const float invFxW = 1.0f/(65536.0f*texWf), invFxH = 1.0f/(65536.0f*texHf);
    /* WRAP POR MASCARA, decidido 1x por triangulo. Era a MAIOR despesa por pixel que
       restava e eu nao a tinha visto: wrapCoord fazia `((c%size)+size)%size`, ou seja
       DOIS __aeabi_idivmod por coordenada e QUATRO por pixel texturizado. Medido no
       Rally: 137,0 milhoes de pixels texturizados = ~548 milhoes de divisoes inteiras
       numa janela de 40M instrucoes, mais que todo o resto do rasterizador somado.
       Com lado potencia de dois (o que o SDK do mophun exige das texturas 3D) o wrap e
       um AND. CLAMP e MIRROR continuam pelo caminho antigo. */
    const int texMW = textureW - 1, texMH = textureH - 1;
    const bool fastWrap = tex && wrapMode!=2 && wrapMode!=4
                       && textureW>0 && textureH>0
                       && (textureW & texMW)==0 && (textureH & texMH)==0;
    /* passos do reseed, na escala SIW. O texW/texH ja entra aqui: assim o laco so
       precisa de um shift para virar texel, e nada multiplica por textureW por pixel. */
    const i32 DIWS = fxClampRound(diwsdx*SIW,              KStepUV);
    const i32 DUN  = fxClampRound(dundx*texWf*SIW,         KStepUV);
    const i32 DVN  = fxClampRound(dvndx*texHf*SIW,         KStepUV);


    /* DERIVADAS EM Y: tudo que a linha precisa tambem e linear em y, entao a LINHA
       tambem anda por soma -- e em inteiro. Isto fecha a conversao: depois disto nao
       sobra float nenhum dentro do triangulo, so no setup dele.
         d(w0)/dy = (x2-x1)*inv     d(w1)/dy = (x0-x2)*inv
       Em float eu tinha PROIBIDO acumular em y (o erro somava pelo triangulo inteiro e
       u/v caiam no texel vizinho). Em ponto fixo o incremento e um inteiro EXATO: o erro
       nao e de arredondamento sucessivo, e so a quantizacao do passo, <= 0,5 ulp por
       linha. Em 11,6 linhas (media medida por triangulo no Rally) isso e invisivel. */
    const float dw0dy = (x2-x1)*inv;
    const float dw1dy = (x0-x2)*inv;
    const float dwqdy = -(dw0dy + dw1dy);
    const i32 IDW0DY = fx16(dw0dy, KStepB);
    const i32 IDW1DY = fx16(dw1dy, KStepB);
    const i32 IDZDY  = fxZ (dw0dy*z0 + dw1dy*z1 + dwqdy*z2, KStepZ);
    const i32 DIWSDY = fxClampRound((dw0dy*iw0 + dw1dy*iw1 + dwqdy*iw2)*SIW, KStepUV);
    const i32 DUNDY  = fxClampRound((dw0dy*ku0 + dw1dy*ku1 + dwqdy*ku2)*texWf*SIW, KStepUV);
    const i32 DVNDY  = fxClampRound((dw0dy*kv0 + dw1dy*kv1 + dwqdy*kv2)*texHf*SIW, KStepUV);

    /* LIMITES DA LINHA POR CAMINHAMENTO DE ARESTA.
       A aresta e cruza zero em k_e = -w_e/dwdx_e (k = coluna relativa a minX). Como
       w_e(y+1) = w_e(y) + dwdy_e, o cruzamento anda SEMPRE o mesmo tanto por linha:
       k_e(y+1) = k_e(y) - dwdy_e/dwdx_e. Logo some a divisao por linha: 3 divisoes no
       setup do triangulo e, por linha, 3 somas inteiras. */
    const float fy0 = minY + 0.5f, fx0 = minX + 0.5f;
    const float ws0r = ((x1-fx0)*(y2-fy0) - (x2-fx0)*(y1-fy0)) * inv;
    const float ws1r = ((x2-fx0)*(y0-fy0) - (x0-fx0)*(y2-fy0)) * inv;
    const float wsr[3] = { ws0r, ws1r, 1.0f - ws0r - ws1r };
    const float dwdy3[3] = { dw0dy, dw1dy, dwqdy };
    const i32 KEClamp = 536870912;       /* +-8192 px em 16.16: ja fora de qualquer tela */
    i32 KE[3], DKE[3];
    for (int e=0;e<3;e++){
        if (recip[e]==0.0f){ KE[e]=0; DKE[e]=0; continue; }
        KE[e]  = fxClampRound(-wsr[e]*recip[e]*65536.0f, (float)KEClamp);
        /* o passo pode atravessar TODA a faixa de KE numa linha so -- tem de poder,
           senao o cruzamento fica para tras (foi esse o bug acima) */
        DKE[e] = fxClampRound(-dwdy3[e]*recip[e]*65536.0f, 1073741824.0f);
    }

    /* incrementos por pixel, ja em inteiro: sao constantes no triangulo */
    /* acumuladores DA LINHA (valor na coluna minX), todos inteiros */
    i32 BW0R = fx16(ws0r, (float)KSatB);
    i32 BW1R = fx16(ws1r, (float)KSatB);
    i32 BZR  = fxZ (ws0r*z0 + ws1r*z1 + wsr[2]*z2, (float)KSatZ);
    i32 IWSR = needIws ? fxClampRound((ws0r*iw0 + ws1r*iw1 + wsr[2]*iw2)*SIW, (float)KSatU) : 1;
    i32 UNR  = tex ? fxClampRound((ws0r*ku0 + ws1r*ku1 + wsr[2]*ku2)*texWf*SIW, (float)KSatU) : 0;
    i32 VNR  = tex ? fxClampRound((ws0r*kv0 + ws1r*kv1 + wsr[2]*kv2)*texHf*SIW, (float)KSatU) : 0;

    for (int py=minY; py<maxY; py++,
         BW0R=sat(BW0R+IDW0DY,KSatB), BW1R=sat(BW1R+IDW1DY,KSatB),
         BZR =sat(BZR +IDZDY, KSatZ),
         IWSR=sat(IWSR+DIWSDY,KSatU), UNR=sat(UNR+DUNDY,KSatU), VNR=sat(VNR+DVNDY,KSatU),
         KE[0]=clampKE(KE[0]+DKE[0]), KE[1]=clampKE(KE[1]+DKE[1]), KE[2]=clampKE(KE[2]+DKE[2])){
        const i32 wsE[3] = { BW0R, BW1R, FX_ONE - BW0R - BW1R };
        int klo = 0, khi = rowW - 1;
        bool vazia = false;
        for (int e=0;e<3;e++){
            /* derivada nula: a aresta e horizontal, o sinal vale para a linha toda */
            if (DKE[e]==0 && recip[e]==0.0f){ if (wsE[e] < 0){ vazia = true; break; } continue; }
            if (dwdx[e] > 0.0f){ int k = (int)((KE[e] + 65535) >> 16); if (k>klo) klo=k; } /* ceil */
            else               { int k = (int)( KE[e] >> 16);          if (k<khi) khi=k; } /* floor */
        }
        if (vazia || klo > khi) continue;
        const int sx = minX + klo, ex = minX + khi + 1;

        /* desloca da coluna minX para a coluna inicial do span: 1 multiplicacao inteira */
        i32 bw0 = BW0R + IDW0*klo;
        i32 bw1 = BW1R + IDW1*klo;
        i32 bdepth = BZR + IDZ*klo;
        i32 iwsC = needIws ? (IWSR + DIWS*klo) : 1;
        i32 unC  = tex ? (UNR + DUN*klo) : 0;
        i32 vnC  = tex ? (VNR + DVN*klo) : 0;
        if (iwsC < 1) iwsC = 1;
        i32 rC = needIws ? (i32)((1073741824u + (u32)iwsC/2) / (u32)iwsC) : 0;  /* 2^30/iws */
        i32 iuC = tex ? narrow30(((i64)unC * rC) >> 14) : 0;
        i32 ivC = tex ? narrow30(((i64)vnC * rC) >> 14) : 0;
        /* acumuladores do laco: INTEIROS. iu/iv em 16.16 de TEXEL (ja multiplicados por
           textureW/H no reseed, para o laco so precisar de um shift). ifog em 16.16, o
           que tambem tira o wpExp() do fog de dentro do laco -- ele agora sai 2x por
           span e o resto e interpolacao linear, que e o que o fog e visualmente. */
        i32 iu=0, iv=0, idudx=0, idvdx=0;
        i32 ifog=FX_ONE, idfog=0;
        int spanLeft = 0;
        for (int pxi=sx; pxi<ex; pxi++,
             bw0+=IDW0, bw1+=IDW1, bdepth+=IDZ, iu+=idudx, iv+=idvdx, ifog+=idfog,
             spanLeft--){
            /* troca de span. O decremento fica na clausula do `for` junto dos demais
               acumuladores, para que os varios `continue` do corpo (fora do triangulo,
               z-test, texel transparente) nao desalinhem a contagem. */
            if (needIws && spanLeft<=0){
                int len = ex - pxi; if (len > KSpan) len = KSpan;
                spanLeft = len;
                i32 iwsN = iwsC + DIWS*len;  if (iwsN < 1) iwsN = 1;
                /* A UNICA divisao do span, e agora e INTEIRA: no ARM11 custa dezenas de
                   ciclos contra os ~200 de um __aeabi_fdiv. */
                const i32 rN  = (i32)((1073741824u + (u32)iwsN/2) / (u32)iwsN);
                const i32 unN = unC + DUN*len;
                const i32 vnN = vnC + DVN*len;
                if (tex){
                    const i32 uN = narrow30(((i64)unN * rN) >> 14);
                    const i32 vN = narrow30(((i64)vnN * rN) >> 14);
                    iu = iuC;  idudx = mulInvLen(uN - iuC, len);
                    iv = ivC;  idvdx = mulInvLen(vN - ivC, len);
                    iuC = uN;  ivC = vN;
                }
                if (fogEnable){
                    /* o fog e a unica coisa que ainda quer float aqui, porque fogFactor
                       tem wpExp(); mas sao 2 chamadas por SPAN, nao por pixel */
                    const float fA = fogFactor(KRtoView * (float)rC);
                    const float fB = fogFactor(KRtoView * (float)rN);
                    ifog  = fxClampRound(fA*65536.0f, 131072.0f);
                    idfog = fxClampRound((fB-fA)*65536.0f*KInvLen[len], KStepLim);
                }
                iwsC=iwsN; rC=rN; unC=unN; vnC=vnN;
            }
            /* TESTE DE ARESTA EM INTEIRO. O OR e negativo se e so se algum dos tres for
               negativo: um teste de sinal no lugar de tres comparacoes de float.
               E dele sai a garantia de nao-estouro das cores logo abaixo: bw2 e definido
               como FX_ONE-bw0-bw1, entao se os TRES sao >=0 a soma e exatamente FX_ONE e
               cada um esta em [0,65536]; 3 x 65536 x 255 cabe folgado no i32. */
            const i32 bw2 = FX_ONE - bw0 - bw1;
            if ((bw0|bw1|bw2) < 0) continue;
            int zi = py*zW + pxi;
            /* primitivas transparentes (ex.: a SOMBRA do carro) sao decals COPLANARES com
               o chao: com o teste estrito LESS elas z-fightam e somem. Usa LEQUAL p/
               passar sobre o coplanar. */
            if (zEnable){
                /* `bdepth - KZEps` em vez de `zbuf + KZEps`: somar no lado do buffer
                   estouraria quando ele ainda vale KZFar (0x7fffffff). */
                bool pass = blend ? ((bdepth - KZEps) <= zbuf[zi]) : zTest(bdepth, zbuf[zi]);
                if (!pass) continue;
            }
            int rr,gg,bb,fragA;
            if (tex){      /* UV ja corrigido: exato nas pontas do span, linear no meio */
                /* NEAREST em inteiro: o 16.16 ja esta em TEXEL, entao basta o shift
                   aritmetico (que e floor tambem para negativo, igual ao wpFloor que
                   estava aqui). O bilinear fica no caminho float: e raro e tem 4 texels
                   com pesos -- converter aquilo renderia pouco e arriscaria o filtro. */
                u32 t;
                if (texLinear)      t = texSample(iu*invFxW, iv*invFxH);
                else if (fastWrap)  t = texelAt((iu>>16) & texMW, (iv>>16) & texMH);
                else                t = texelAt(wrapCoord(iu>>16, textureW),
                                                wrapCoord(iv>>16, textureH));
                if ((t>>24)==0) continue;   /* texel transparente -> nao pinta NEM escreve z */
                int tr=(t>>16)&0xff, tg=(t>>8)&0xff, tb=t&0xff;
                /* alpha do fragmento TEXTURIZADO = alpha do TEXEL (DECAL), modulado pelo
                   vertice so em MODULATE. Formatos opacos (RGB332/565/555/888) tem
                   alpha=255 -> fragmento OPACO mesmo com ALPHAENABLE ligado e alpha de
                   vertice baixo. Sem isto, o CEU do Joe's Treasure Quest (quad RGB332,
                   DECAL, vertice alpha=0x80) saia a 50% e o frame anterior vazava. */
                int ta = (int)(t>>24);
                fragA = (texBlendMode==2) ? (ta*((bw0*a0+bw1*a1+bw2*a2)>>16)/255) : ta;
                switch (texBlendMode){
                    case 2: {    /* MODULATE */
                        int vr=(bw0*r0+bw1*r1+bw2*r2)>>16, vg=(bw0*g0+bw1*g1+bw2*g2)>>16, vb=(bw0*b0+bw1*b1+bw2*b2)>>16;
                        rr=tr*vr/255; gg=tg*vg/255; bb=tb*vb/255; break; }
                    case 4: {    /* ADD */
                        int vr=(bw0*r0+bw1*r1+bw2*r2)>>16, vg=(bw0*g0+bw1*g1+bw2*g2)>>16, vb=(bw0*b0+bw1*b1+bw2*b2)>>16;
                        rr=tr+vr; gg=tg+vg; bb=tb+vb; break; }
                    default: rr=tr; gg=tg; bb=tb; break;    /* DECAL/REPLACE */
                }
            } else {
                rr=(bw0*r0+bw1*r1+bw2*r2)>>16;
                gg=(bw0*g0+bw1*g1+bw2*g2)>>16;
                bb=(bw0*b0+bw1*b1+bw2*b2)>>16;
                fragA=(bw0*a0+bw1*a1+bw2*a2)>>16;   /* alpha do VERTICE (ex.: sombra do Rally) */
            }
            if (fogEnable){
                i32 f = ifog; if (f<0) f=0; else if (f>FX_ONE) f=FX_ONE;
                rr=(rr*f+fr*(FX_ONE-f))>>16; gg=(gg*f+fg*(FX_ONE-f))>>16; bb=(bb*f+fb*(FX_ONE-f))>>16;
            }
            rr=clampB(rr); gg=clampB(gg); bb=clampB(bb);
            if (blend){
                int sa = fragA; if (sa<0) sa=0; else if (sa>255) sa=255;
                u32 dst = scr->fb[py*scr->w + pxi];
                int dr=(dst>>16)&0xff, dg=(dst>>8)&0xff, db=dst&0xff;
                /* sem estado 20 explicito o default e ALPHA (SrcAlpha/InvSrcAlpha): o Rally
                   desenha a SOMBRA do carro como poligono preto alpha=0x80 e SO liga
                   ALPHAENABLE -- antes saia preto opaco, agora mistura 50%. */
                switch (colorBlend!=0 ? colorBlend : 2){
                    case 1: rr=clampB(dr+rr*sa/255); gg=clampB(dg+gg*sa/255); bb=clampB(db+bb*sa/255); break; /* ALPHAADD */
                    case 2: rr=(rr*sa+dr*(255-sa))/255; gg=(gg*sa+dg*(255-sa))/255; bb=(bb*sa+db*(255-sa))/255; break; /* ALPHA */
                    case 3: rr=rr*dr/255; gg=gg*dg/255; bb=bb*db/255; break;    /* MODULATE */
                    default: break;                                             /* REPLACE */
                }
            }
            /* transparente (sombra) nao escreve z -> nao oclui o carro */
            if (zEnable && !blend) zbuf[zi]=bdepth;
            scr->fb[py*scr->w + pxi] = 0xff000000u|((u32)rr<<16)|((u32)gg<<8)|(u32)bb;
            pixels3d++;
        }
    }
}
