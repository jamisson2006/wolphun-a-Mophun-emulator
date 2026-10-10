/* Renderer3D.cpp - port de src/wolphun/vm/Renderer3D.java (ver Renderer3D.h).
 * Parte 1: matrizes, vetores, estado, texturas e iluminacao.
 * O rasterizador propriamente dito esta em Renderer3DRaster.cpp.
 */
#include "Renderer3D.h"
#include "Pip2.h"
#include "Screen.h"
#include "fastmath.h"

static void identMat(float* m){
    for (int i=0;i<16;i++) m[i]=0.0f;
    m[0]=m[5]=m[10]=m[15]=1.0f;
}
/* r = a*b (row-major) */
static void mulMat(const float* a,const float* b,float* r){
    for (int i=0;i<4;i++)
        for (int j=0;j<4;j++){
            float s=0;
            for (int k=0;k<4;k++) s += a[i*4+k]*b[k*4+j];
            r[i*4+j]=s;
        }
}
/* transforma (x,y,z,w) por m -> out[4] */
static void xform(const float* m,float x,float y,float z,float w,float* out){
    out[0]=m[0]*x+m[1]*y+m[2]*z+m[3]*w;
    out[1]=m[4]*x+m[5]*y+m[6]*z+m[7]*w;
    out[2]=m[8]*x+m[9]*y+m[10]*z+m[11]*w;
    out[3]=m[12]*x+m[13]*y+m[14]*z+m[15]*w;
}
static int clamp255(int v){ return v<0?0:(v>255?255:v); }
static int iround3(int v,int num,int den){ return (v*num*2 + den)/(den*2); }
static u32 rgb565a(int v){
    int r=(v>>11)&31, g=(v>>5)&63, b=v&31;
    return 0xff000000u | ((u32)iround3(r,255,31)<<16) | ((u32)iround3(g,255,63)<<8) | (u32)iround3(b,255,31);
}

Renderer3D::Renderer3D(Pip2* vm_, Screen* scr_)
: texFilterForce(0), trisDrawn(0), pixels3d(0), drawCalls(0), vm(vm_), scr(scr_),
  matrixOrientation(0x2000),
  vpL(0), vpT(0), vpW(scr_->w), vpH(scr_->h),
  zbuf(0), zW(0), zH(0),
  cullMode(2), textureMode(false), transp3d(false),
  shadeMode(2), texBlendMode(2), colorBlend(0),
  alphaEnable(false), wrapMode(1),
  zEnable(true), zFunc(2),
  fogEnable(false), fogMode(1), fogColor(0),
  fogStart(0.0f), fogEnd(1.0f), fogDensity(0.0f),
  lightingEnable(false), specularEnable(false), filterMode(1),
  texturePtr(0), textureFormat(0), textureW(0), textureH(0),
  activePalette(0), activePalCount(0),
  managed(0), managedCap(0), nextTexHandle(1),
  ambientScene(0),
  matDiffR(255),matDiffG(255),matDiffB(255),
  matSpecR(0),matSpecG(0),matSpecB(0),
  matEmR(0),matEmG(0),matEmB(0),
  matAmbR(255),matAmbG(255),matAmbB(255),
  matShininess(4.0f), camX(0),camY(0),camZ(0),
  bClip(0), bCol(0), bUv(0), bCap(0)
{
    identMat(current); identMat(projection); identMat(light);
    for (int i=0;i<MAXL;i++){ lightUsed[i]=false; for(int k=0;k<16;k++) lights[i][k]=0.0f; }
}

Renderer3D::~Renderer3D(){
    delete[] zbuf;
    delete[] bClip; delete[] bCol; delete[] bUv;
    if (managed){
        for (int i=0;i<managedCap;i++) if (managed[i].used && managed[i].palette) delete[] managed[i].palette;
        delete[] managed;
    }
    if (activePalette) { /* aponta p/ a paleta de uma Tex gerenciada OU foi liberada acima */ }
}

/* ---------------- acesso a vetores da VM ---------------- */
float Renderer3D::vx(int p) const { return f2f(vm->read32(p)); }
float Renderer3D::vy(int p) const { return f2f(vm->read32(p+4)); }
float Renderer3D::vz(int p) const { return f2f(vm->read32(p+8)); }
void  Renderer3D::putV(int p,float x,float y,float z){
    vm->write32(p,   ff(x));
    vm->write32(p+4, ff(y));
    vm->write32(p+8, ff(z));
}

/* ---------------- matematica do SDK ---------------- */
int Renderer3D::vPow(int val,int exp){
    /* expoente e um inteiro 0..255 -> exponenciacao por quadrados (exato e rapido) */
    float b = f2f(val), r = 1.0f;
    int e = exp & 0xff;
    while (e){ if (e & 1) r *= b; b *= b; e >>= 1; }
    return ff(r);
}
int Renderer3D::vSqrt(int v){ return ff(wpSqrt(f2f(v))); }
int Renderer3D::vSin(int v){ return ff(wpSin(ang2rad(v))); }
int Renderer3D::vCos(int v){ return ff(wpCos(ang2rad(v))); }
int Renderer3D::vTan(int v){ return ff(wpTan(ang2rad(v))); }

/* ---------------- vetores ---------------- */
/* add/sub sao soma/subtracao direta dos raw fixed (mesma escala) */
void Renderer3D::vectorAdd(int d,int a,int b){
    for (int i=0;i<12;i+=4) vm->write32(d+i, vm->read32(a+i)+vm->read32(b+i));
}
void Renderer3D::vectorSub(int d,int a,int b){
    for (int i=0;i<12;i+=4) vm->write32(d+i, vm->read32(a+i)-vm->read32(b+i));
}
void Renderer3D::vectorMul(int d,int a,int b){     /* componente a componente */
    putV(d, vx(a)*vx(b), vy(a)*vy(b), vz(a)*vz(b));
}
void Renderer3D::vectorNormalize(int d){
    float x=vx(d), y=vy(d), z=vz(d);
    float l = wpSqrt(x*x+y*y+z*z);
    if (l > 1e-9f) putV(d, x/l, y/l, z/l);
}
void Renderer3D::crossProduct(int d,int a,int b){
    float ax=vx(a),ay=vy(a),az=vz(a), bx=vx(b),by=vy(b),bz=vz(b);
    putV(d, ay*bz-az*by, az*bx-ax*bz, ax*by-ay*bx);
}
int Renderer3D::dotProduct(int a,int b){
    return ff(vx(a)*vx(b) + vy(a)*vy(b) + vz(a)*vz(b));
}
/* transforma pontos pela matriz ATUAL (MultiplyPoint3x4: afim, w=1) */
void Renderer3D::vectorTransformV3(int dest,int src,int count){
    float o[4];
    for (int i=0;i<count;i++){
        int s=src+i*12, d=dest+i*12;
        xform(current, vx(s),vy(s),vz(s), 1.0f, o);
        putV(d,o[0],o[1],o[2]);
    }
}
void Renderer3D::vectorTransformV4(int dst,int src,int count){
    float o[4];
    for (int i=0;i<count;i++){
        int s=src+i*12, d=dst+i*16;
        xform(current, vx(s),vy(s),vz(s), 1.0f, o);
        vm->write32(d,    ff(o[0]));
        vm->write32(d+4,  ff(o[1]));
        vm->write32(d+8,  ff(o[2]));
        vm->write32(d+12, ff(o[3]));
    }
}
/* projeta ponto (view space) -> tela. X/Y em 1/16 px, Z/W fixed (nofun VectorProjectImpl) */
void Renderer3D::projectImpl(int dst,int src,int count,bool v4){
    int ss = v4 ? 16 : 12;
    float o[4];
    for (int i=0;i<count;i++){
        int s=src+i*ss, d=dst+i*16;
        float w = v4 ? f2f(vm->read32(s+12)) : 1.0f;
        xform(projection, vx(s),vy(s),vz(s), w, o);
        if (o[3]==0.0f){
            vm->write32(d, ff(o[0])); vm->write32(d+4, ff(o[1]));
            vm->write32(d+8, ff(4));  vm->write32(d+12, 0);
        } else {
            float px = o[0]/o[3]*(vpW*0.5f) + (vpL + vpW*0.5f);
            float py = o[1]/o[3]*(-vpH*0.5f) + (vpT + vpH*0.5f);
            float pz = o[2]/o[3]*2.0f + 2.0f;
            vm->write32(d,    (int)(px*16));
            vm->write32(d+4,  (int)(py*16));
            vm->write32(d+8,  ff(pz));
            vm->write32(d+12, ff(o[3]));
        }
    }
}
void Renderer3D::vectorArrayAdd(int d,int s1,int s2,int count){
    for (int i=0;i<count;i++) vectorAdd(d+i*12, s1+i*12, s2+i*12);
}
/* d = (s2 - s1) / steps ; low16 = nr de vetores, high16 = steps */
void Renderer3D::vectorArrayDelta(int d,int s1,int s2,int param){
    int count = param & 0xffff;
    int steps = (int)(((u32)param) >> 16); if (steps==0) steps=1;
    for (int i=0;i<count;i++){
        int a=s1+i*12, b=s2+i*12, o=d+i*12;
        putV(o, (vx(b)-vx(a))/steps, (vy(b)-vy(a))/steps, (vz(b)-vz(a))/steps);
    }
}

/* ---------------- frustum / planos / colisao ---------------- */
int Renderer3D::boxInViewFrustum(int b){
    float mnx=vx(b),   mny=vy(b),   mnz=vz(b);
    float mxx=vx(b+12),mxy=vy(b+12),mxz=vz(b+12);
    float pv[16]; mulMat(projection,current,pv);
    float pts[8][3] = {
        {mnx,mny,mnz},{mnx,mny,mxz},{mxx,mny,mxz},{mxx,mny,mnz},
        {mxx,mxy,mxz},{mxx,mxy,mnz},{mnx,mxy,mnz},{mnx,mxy,mxz}
    };
    float cl[8][4];
    for (int i=0;i<8;i++) xform(pv, pts[i][0],pts[i][1],pts[i][2], 1.0f, cl[i]);
    /* caixa que ATRAVESSA a camera (algum canto em/atras do near, w<=0): o teste
       -w<=coord<=w e instavel com w negativo e pode CULAR por engano uma chunk em que
       estamos entrando -> geometria "some ao mover". Nesse caso nao culamos (o
       rasterizador ja recorta no near). So reduz culling. */
    for (int i=0;i<8;i++) if (cl[i][3] <= 1e-4f) return 1;
    for (int c=0;c<3;c++){
        bool in=false;
        for (int i=0;i<8;i++) if (cl[i][c] > -cl[i][3]){ in=true; break; }
        if (!in) return 0;
        in=false;
        for (int i=0;i<8;i++) if (cl[i][c] <  cl[i][3]){ in=true; break; }
        if (!in) return 0;
    }
    return 1;
}
/* vCreatePlaneFromPoly: normal = norm((p1-p3) x (p2-p3)), dist = dot(n,p1) */
void Renderer3D::createPlaneFromPoly(int dest,int poly){
    float p1x=vx(poly),    p1y=vy(poly),    p1z=vz(poly);
    float p2x=vx(poly+12), p2y=vy(poly+12), p2z=vz(poly+12);
    float p3x=vx(poly+24), p3y=vy(poly+24), p3z=vz(poly+24);
    float e1x=p1x-p3x, e1y=p1y-p3y, e1z=p1z-p3z;
    float e2x=p2x-p3x, e2y=p2y-p3y, e2z=p2z-p3z;
    float nx=e1y*e2z-e1z*e2y, ny=e1z*e2x-e1x*e2z, nz=e1x*e2y-e1y*e2x;
    float l = wpSqrt(nx*nx+ny*ny+nz*nz); if (l < 1e-9f) l = 1.0f;
    nx/=l; ny/=l; nz/=l;
    putV(dest,nx,ny,nz);
    vm->write32(dest+12, ff(nx*p1x + ny*p1y + nz*p1z));
}
int Renderer3D::collisionPointBox(int point,int box){
    for (int i=0;i<12;i+=4){
        int p = vm->read32(point+i);
        if (p < vm->read32(box+i) || p > vm->read32(box+12+i)) return 0;
    }
    return 1;
}
int Renderer3D::collisionBoxBox(int b1,int b2){
    for (int i=0;i<12;i+=4)
        if (vm->read32(b1+i) > vm->read32(b2+12+i) || vm->read32(b1+12+i) < vm->read32(b2+i)) return 0;
    return 1;
}
int Renderer3D::collisionVectorPoly(int collPos,int lineV,int polyV,int plane){
    float nx,ny,nz,pw;
    if (plane != 0){
        nx=vx(plane); ny=vy(plane); nz=vz(plane); pw=f2f(vm->read32(plane+12));
    } else if (polyV != 0){
        float p1x=vx(polyV),    p1y=vy(polyV),    p1z=vz(polyV);
        float p2x=vx(polyV+12), p2y=vy(polyV+12), p2z=vz(polyV+12);
        float p3x=vx(polyV+24), p3y=vy(polyV+24), p3z=vz(polyV+24);
        float e1x=p1x-p3x,e1y=p1y-p3y,e1z=p1z-p3z;
        float e2x=p2x-p3x,e2y=p2y-p3y,e2z=p2z-p3z;
        nx=e1y*e2z-e1z*e2y; ny=e1z*e2x-e1x*e2z; nz=e1x*e2y-e1y*e2x;
        float l=wpSqrt(nx*nx+ny*ny+nz*nz); if (l<1e-9f) l=1.0f;
        nx/=l; ny/=l; nz/=l;
        pw = nx*p1x + ny*p1y + nz*p1z;
    } else return 0;
    float l0x=vx(lineV), l0y=vy(lineV), l0z=vz(lineV);
    float dx=vx(lineV+12)-l0x, dy=vy(lineV+12)-l0y, dz=vz(lineV+12)-l0z;
    float mult = dx*nx + dy*ny + dz*nz;
    if (wpAbs(mult) <= 1e-6f) return 0;
    float t = (pw - (nx*l0x + ny*l0y + nz*l0z)) / mult;
    float cx=l0x+dx*t, cy=l0y+dy*t, cz=l0z+dz*t;
    if (polyV != 0){
        float a0=vx(polyV)-cx,     a1=vy(polyV)-cy,     a2=vz(polyV)-cz;
        float b0=vx(polyV+12)-cx,  b1=vy(polyV+12)-cy,  b2=vz(polyV+12)-cz;
        float c0=vx(polyV+24)-cx,  c1=vy(polyV+24)-cy,  c2=vz(polyV+24)-cz;
        float ux=b1*c2-b2*c1, uy=b2*c0-b0*c2, uz=b0*c1-b1*c0;   /* cross(b,c) */
        float vX=c1*a2-c2*a1, vY=c2*a0-c0*a2, vZ=c0*a1-c1*a0;   /* cross(c,a) */
        float wX=a1*b2-a2*b1, wY=a2*b0-a0*b2, wZ=a0*b1-a1*b0;   /* cross(a,b) */
        if (ux*vX + uy*vY + uz*vZ < 0) return 0;
        if (vX*wX + vY*wY + vZ*wZ < 0) return 0;
    }
    putV(collPos,cx,cy,cz);
    return 1;
}

/* ---------------- matrizes ---------------- */
void Renderer3D::readMatrix(int ptr, float* out){
    if (ptr==0){ for (int i=0;i<16;i++) out[i]=current[i]; return; }
    for (int r=0;r<4;r++)
        for (int c=0;c<4;c++){
            int fx = vm->read32(ptr + (r*4+c)*4);
            if (matrixOrientation==0x2000) out[r*4+c] = f2f(fx);   /* ColumnMajor */
            else                           out[c*4+r] = f2f(fx);   /* RowMajor: transpoe */
        }
}
void Renderer3D::matrixGetCurrent(int ptr){
    if (ptr==0) return;
    for (int r=0;r<4;r++)
        for (int c=0;c<4;c++){
            float v = (matrixOrientation==0x2000) ? current[r*4+c] : current[c*4+r];
            vm->write32(ptr + (r*4+c)*4, ff(v));
        }
}
void Renderer3D::matrixIdentity(){ identMat(current); }
void Renderer3D::matrixSetCurrent(int ptr){ readMatrix(ptr, current); }
void Renderer3D::matrixMultiply(int ptr){
    float op[16], r[16];
    readMatrix(ptr, op);
    mulMat(current, op, r);
    for (int i=0;i<16;i++) current[i]=r[i];
}
void Renderer3D::matrixSetProjection(int ptr){ readMatrix(ptr, projection); }
void Renderer3D::matrixSetLight(int ptr){ readMatrix(ptr, light); }
void Renderer3D::matrixTranslate(int x,int y,int z){
    float t[16], r[16];
    identMat(t); t[3]=f2f(x); t[7]=f2f(y); t[11]=f2f(z);
    mulMat(current,t,r);
    for (int i=0;i<16;i++) current[i]=r[i];
}
void Renderer3D::matrixScale(int x,int y,int z){
    float s[16], r[16];
    identMat(s); s[0]=f2f(x); s[5]=f2f(y); s[10]=f2f(z);
    mulMat(current,s,r);
    for (int i=0;i<16;i++) current[i]=r[i];
}
static void rotMat(float deg,float ax,float ay,float az,float* m){
    float n = wpSqrt(ax*ax+ay*ay+az*az);
    if (n < 1e-9f){ identMat(m); return; }
    ax/=n; ay/=n; az/=n;
    float a = deg * (WP_PI/180.0f);
    float c = wpCos(a), s = wpSin(a), t = 1.0f-c;
    identMat(m);
    m[0]=t*ax*ax+c;    m[1]=t*ax*ay-s*az; m[2]=t*ax*az+s*ay;
    m[4]=t*ax*ay+s*az; m[5]=t*ay*ay+c;    m[6]=t*ay*az-s*ax;
    m[8]=t*ax*az-s*ay; m[9]=t*ay*az+s*ax; m[10]=t*az*az+c;
}
void Renderer3D::matrixRotateX(int d){ float m[16],r[16]; rotMat(ang2deg(d),1,0,0,m); mulMat(current,m,r); for(int i=0;i<16;i++)current[i]=r[i]; }
void Renderer3D::matrixRotateY(int d){ float m[16],r[16]; rotMat(ang2deg(d),0,1,0,m); mulMat(current,m,r); for(int i=0;i<16;i++)current[i]=r[i]; }
void Renderer3D::matrixRotateZ(int d){ float m[16],r[16]; rotMat(ang2deg(d),0,0,1,m); mulMat(current,m,r); for(int i=0;i<16;i++)current[i]=r[i]; }
void Renderer3D::matrixRotateVector(int axisPtr,int d){
    float ax=f2f(vm->read32(axisPtr)), ay=f2f(vm->read32(axisPtr+4)), az=f2f(vm->read32(axisPtr+8));
    float m[16],r[16];
    rotMat(ang2deg(d), -ax, -ay, -az, m);
    mulMat(current,m,r);
    for (int i=0;i<16;i++) current[i]=r[i];
}
/* perspectiva: width/height = dimensoes do near-plane (fixed). LH, depth [0,1], w=z_view */
void Renderer3D::matrixPerspective(int wFx,int hFx,int nearFx,int farFx){
    float w=f2f(wFx), h=f2f(hFx), nearv=f2f(nearFx), farv=f2f(farFx);
    if (w==0||h==0||nearv==0||farv==nearv){ identMat(current); return; }
    float m[16];
    for (int i=0;i<16;i++) m[i]=0.0f;
    m[0]=2*nearv/w; m[5]=2*nearv/h;
    m[10]=farv/(farv-nearv); m[11]=-nearv*farv/(farv-nearv);
    m[14]=1.0f; m[15]=0.0f;
    for (int i=0;i<16;i++) current[i]=m[i];
}
void Renderer3D::matrixOrtho(int wFx,int hFx,int nearFx,int farFx){
    float w=f2f(wFx), h=f2f(hFx), nearv=f2f(nearFx), farv=f2f(farFx);
    float m[16]; identMat(m);
    if (w!=0) m[0]=2.0f/w;
    if (h!=0) m[5]=2.0f/h;
    if (farv!=nearv){ m[10]=-2.0f/(farv-nearv); m[11]=(nearv+farv)/(farv-nearv); }
    for (int i=0;i<16;i++) current[i]=m[i];
}
void Renderer3D::matrixLookAt(int eyeP,int atP,int upP){
    float ex=f2f(vm->read32(eyeP)), ey=f2f(vm->read32(eyeP+4)), ez=f2f(vm->read32(eyeP+8));
    float ax=f2f(vm->read32(atP)),  ay=f2f(vm->read32(atP+4)),  az=f2f(vm->read32(atP+8));
    float ux=f2f(vm->read32(upP)),  uy=f2f(vm->read32(upP+4)),  uz=f2f(vm->read32(upP+8));
    float zx=ax-ex, zy=ay-ey, zz=az-ez;
    float zn=wpSqrt(zx*zx+zy*zy+zz*zz); if (zn<1e-9f) return;
    zx/=zn; zy/=zn; zz/=zn;
    float xx=uy*zz-uz*zy, xy=uz*zx-ux*zz, xz=ux*zy-uy*zx;     /* xaxis = up x zaxis */
    float xn=wpSqrt(xx*xx+xy*xy+xz*xz); if (xn<1e-9f) return;
    xx/=xn; xy/=xn; xz/=xn;
    float yx=zy*xz-zz*xy, yy=zz*xx-zx*xz, yz=zx*xy-zy*xx;     /* yaxis = zaxis x xaxis */
    float la[16]; identMat(la);
    la[0]=xx; la[1]=xy; la[2]=xz;
    la[4]=yx; la[5]=yy; la[6]=yz;
    la[8]=zx; la[9]=zy; la[10]=zz;
    float tr[16]; identMat(tr); tr[3]=-ex; tr[7]=-ey; tr[11]=-ez;
    float lt[16], r[16];
    mulMat(la,tr,lt);
    mulMat(current,lt,r);
    for (int i=0;i<16;i++) current[i]=r[i];
}
static bool invert4x4(const float* m, float* inv){
    inv[0]=m[5]*m[10]*m[15]-m[5]*m[11]*m[14]-m[9]*m[6]*m[15]+m[9]*m[7]*m[14]+m[13]*m[6]*m[11]-m[13]*m[7]*m[10];
    inv[4]=-m[4]*m[10]*m[15]+m[4]*m[11]*m[14]+m[8]*m[6]*m[15]-m[8]*m[7]*m[14]-m[12]*m[6]*m[11]+m[12]*m[7]*m[10];
    inv[8]=m[4]*m[9]*m[15]-m[4]*m[11]*m[13]-m[8]*m[5]*m[15]+m[8]*m[7]*m[13]+m[12]*m[5]*m[11]-m[12]*m[7]*m[9];
    inv[12]=-m[4]*m[9]*m[14]+m[4]*m[10]*m[13]+m[8]*m[5]*m[14]-m[8]*m[6]*m[13]-m[12]*m[5]*m[10]+m[12]*m[6]*m[9];
    inv[1]=-m[1]*m[10]*m[15]+m[1]*m[11]*m[14]+m[9]*m[2]*m[15]-m[9]*m[3]*m[14]-m[13]*m[2]*m[11]+m[13]*m[3]*m[10];
    inv[5]=m[0]*m[10]*m[15]-m[0]*m[11]*m[14]-m[8]*m[2]*m[15]+m[8]*m[3]*m[14]+m[12]*m[2]*m[11]-m[12]*m[3]*m[10];
    inv[9]=-m[0]*m[9]*m[15]+m[0]*m[11]*m[13]+m[8]*m[1]*m[15]-m[8]*m[3]*m[13]-m[12]*m[1]*m[11]+m[12]*m[3]*m[9];
    inv[13]=m[0]*m[9]*m[14]-m[0]*m[10]*m[13]-m[8]*m[1]*m[14]+m[8]*m[2]*m[13]+m[12]*m[1]*m[10]-m[12]*m[2]*m[9];
    inv[2]=m[1]*m[6]*m[15]-m[1]*m[7]*m[14]-m[5]*m[2]*m[15]+m[5]*m[3]*m[14]+m[13]*m[2]*m[7]-m[13]*m[3]*m[6];
    inv[6]=-m[0]*m[6]*m[15]+m[0]*m[7]*m[14]+m[4]*m[2]*m[15]-m[4]*m[3]*m[14]-m[12]*m[2]*m[7]+m[12]*m[3]*m[6];
    inv[10]=m[0]*m[5]*m[15]-m[0]*m[7]*m[13]-m[4]*m[1]*m[15]+m[4]*m[3]*m[13]+m[12]*m[1]*m[7]-m[12]*m[3]*m[5];
    inv[14]=-m[0]*m[5]*m[14]+m[0]*m[6]*m[13]+m[4]*m[1]*m[14]-m[4]*m[2]*m[13]-m[12]*m[1]*m[6]+m[12]*m[2]*m[5];
    inv[3]=-m[1]*m[6]*m[11]+m[1]*m[7]*m[10]+m[5]*m[2]*m[11]-m[5]*m[3]*m[10]-m[9]*m[2]*m[7]+m[9]*m[3]*m[6];
    inv[7]=m[0]*m[6]*m[11]-m[0]*m[7]*m[10]-m[4]*m[2]*m[11]+m[4]*m[3]*m[10]+m[8]*m[2]*m[7]-m[8]*m[3]*m[6];
    inv[11]=-m[0]*m[5]*m[11]+m[0]*m[7]*m[9]+m[4]*m[1]*m[11]-m[4]*m[3]*m[9]-m[8]*m[1]*m[7]+m[8]*m[3]*m[5];
    inv[15]=m[0]*m[5]*m[10]-m[0]*m[6]*m[9]-m[4]*m[1]*m[10]+m[4]*m[2]*m[9]+m[8]*m[1]*m[6]-m[8]*m[2]*m[5];
    float det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12];
    if (wpAbs(det) < 1e-12f) return false;
    det = 1.0f/det;
    for (int i=0;i<16;i++) inv[i] *= det;
    return true;
}
void Renderer3D::matrixInvert(){
    float inv[16];
    if (invert4x4(current, inv)) for (int i=0;i<16;i++) current[i]=inv[i];
}
void Renderer3D::matrixTranspose(){
    float t[16];
    for (int r=0;r<4;r++) for (int c=0;c<4;c++) t[c*4+r]=current[r*4+c];
    for (int i=0;i<16;i++) current[i]=t[i];
}
/* multiplica so os componentes de rotacao (3x3) da matriz atual */
void Renderer3D::matrixMultiply3x3(int ptr){
    float m[16], r3[16], r[16];
    readMatrix(ptr, m);
    identMat(r3);
    for (int rr=0;rr<3;rr++) for (int c=0;c<3;c++) r3[rr*4+c]=m[rr*4+c];
    mulMat(current,r3,r);
    for (int i=0;i<16;i++) current[i]=r[i];
}

/* ---------------- estado ---------------- */
void Renderer3D::setRenderState(int state,int value){
    switch (state){
        case 4:  zEnable=(value!=0); break;        /* ZENABLE */
        case 5:  zFunc=value; break;               /* ZFUNCTION */
        case 6:  cullMode=value; break;            /* CULLMODE */
        case 7:  shadeMode=value; break;           /* SHADEMODE */
        case 8:  fogEnable=(value!=0); break;      /* FOGENABLE */
        case 9:  textureMode=(value!=0); break;    /* TEXTUREENABLE */
        case 11: texBlendMode=value; break;        /* TEXTURE_BLENDMODE */
        case 12: alphaEnable=(value!=0); break;    /* ALPHAENABLE */
        case 13: filterMode=value; break;          /* FILTERMODE */
        case 14: wrapMode=value; break;            /* WRAPMODE */
        case 15: transp3d=(value!=0); break;       /* TRANSPARENTENABLE */
        case 16: specularEnable=(value!=0); break; /* SPECULARENABLE */
        case 17: lightingEnable=(value!=0); break; /* LIGHTINGENABLE */
        case 20: colorBlend=value; break;          /* COLORBUFFER_BLENDMODE */
        case 21: fogColor=value&0xffffff; break;   /* FOGCOLOR */
        case 22: fogMode=value; break;             /* FOGMODE */
        case 23: fogStart=f2f(value); break;
        case 24: fogEnd=f2f(value); break;
        case 25: fogDensity=f2f(value); break;
        default: break;
    }
}
int Renderer3D::getInteger(int param){
    switch (param){
        case 1: return 8;          /* MAX_LIGHTS */
        case 2: return 4096;       /* MAX_VERTICES */
        case 3: return 256;        /* MAX_TEXTURE_SIZE */
        case 4: return 4096;       /* MAX_TEXTURE_COUNT */
        case 5: return 1<<20;      /* TEXTURE_CACHE_SIZE */
        case 6: return 16;         /* DEPTH_BITS */
        default: return 0;
    }
}
void Renderer3D::ensureZ(){
    if (!zbuf || zW!=scr->w || zH!=scr->h){
        delete[] zbuf;
        zW=scr->w; zH=scr->h;
        zbuf = new i32[zW*zH];
        for (int i=0;i<zW*zH;i++) zbuf[i]=KZFar;
    }
}
void Renderer3D::clearDepth(){ ensureZ(); for (int i=0;i<zW*zH;i++) zbuf[i]=KZFar; }
void Renderer3D::clearDepthPerFrame(){ if (zbuf) for (int i=0;i<zW*zH;i++) zbuf[i]=KZFar; }
int Renderer3D::getZBufferValue(int x,int y){
    if (!zbuf || x<scr->clipX0||x>=scr->clipX1||y<scr->clipY0||y>=scr->clipY1) return 0;
    i32 d = zbuf[y*zW+x];
    if (d >= KZFar) return 0;
    /* 10.22 -> 0..65535 (o que a API vGetZBufferValue devolve); i64 porque d*65535
       estoura o i32 */
    int v = (int)(((i64)d * 65535) >> KZShift);
    return v<0?0:(v>65535?65535:v);
}
/* teste de profundidade honrando ZFUNCTION */
bool Renderer3D::zTest(i32 d,i32 buf) const {
    switch (zFunc){
        case 1:   return false;      /* NEVER */
        case 4:   return d==buf;     /* EQUAL */
        case 8:   return d<=buf;     /* LEQUAL */
        case 16:  return d>buf;      /* GREATER */
        case 32:  return d!=buf;     /* NOTEQUAL */
        case 64:  return d>=buf;     /* GEQUAL */
        case 128: return true;       /* ALWAYS */
        default:  return d<buf;      /* LESS (2) + default */
    }
}
/* fator de fog: 1 = sem fog, 0 = fog total. z = profundidade view-space */
float Renderer3D::fogFactor(float z) const {
    float f = (fogMode==0) ? wpExp(-fogDensity*z)
                           : (fogEnd-z)/(fogEnd-fogStart);
    return f<0.0f?0.0f:(f>1.0f?1.0f:f);
}
int Renderer3D::wrapCoord(int c,int size) const {
    switch (wrapMode){
        case 2:  return c<0?0:(c>=size?size-1:c);                                   /* CLAMP */
        case 4:  { int p=((c%(2*size))+2*size)%(2*size); return p<size?p:(2*size-1-p); } /* MIRROR */
        /* WRAP: textura de lado potencia de dois (o que o SDK do mophun exige) vira UM
           AND. O `((c%size)+size)%size` custava DOIS __aeabi_idivmod -- sem FPU e sem
           divisor em hardware, cada um e dezenas de ciclos. A VM oficial faz o wrap com
           uma unica instrucao (`and rX, mascara, rY, ror rZ`). */
        default: return ((size & (size-1))==0) ? (c & (size-1)) : (((c%size)+size)%size);
    }
}

/* ---------------- texturas ---------------- */
int Renderer3D::setTexture(int dataPtr,int format,int lods,int mipCount){
    (void)mipCount;
    texturePtr=dataPtr; textureFormat=format;
    activePalette=0; activePalCount=0;     /* efemera: indexado usa a paleta de TELA */
    textureW = 1 << (lods & 0xff);
    textureH = 1 << ((lods>>8) & 0xff);
    if (textureW<1||textureW>2048||textureH<1||textureH>2048){ textureW=textureH=0; }
    return 1;
}
/* decodifica a paleta EMBUTIDA (formatos 16-19). 0 p/ formatos sem paleta propria.
   A paleta e um array de u32: RGB888 p/ 16/17; ARGB8888 p/ 18/19, onde o byte de alpha
   LIGADO marca a COR-CHAVE transparente (nao e uma opacidade). Medido no Carmageddon 3D:
   toda paleta 18/19 tem exatamente UMA entrada 0xff000000 e ela NAO fica sempre no
   indice 0 (nos pedestres esta no indice 1) -- tratar so o indice 0 como transparente
   fazia os pedestres distantes sairem como retangulos PRETOS solidos. */
u32* Renderer3D::decodeTexPalette(int format,int palPtr,int count,int* outCount){
    bool self = (format==16||format==17||format==18||format==19);
    *outCount = 0;
    if (!self || palPtr==0 || count<=0) return 0;
    if (count>256) count=256;
    u32* pal = new u32[count];
    bool argb = (format==18||format==19);
    for (int i=0;i<count;i++){
        u32 c = (u32)vm->read32(palPtr+i*4);
        pal[i] = (argb && (c & 0xff000000u)!=0) ? 0u : (0xff000000u | (c & 0xffffffu));
    }
    *outCount = count;
    return pal;
}
/* NativeTexture(32B): format@0, lodX@4, lodY@8, mipCount@12, paletteCount@16,
   reserved@20, palette@24, data@28 */
int Renderer3D::createTexture(int texPtr){
    if (texPtr==0) return 0;
    int format = vm->read32(texPtr);
    int lodX = vm->read32(texPtr+4), lodY = vm->read32(texPtr+8);
    int w = 1 << (lodX & 0x1f), h = 1 << (lodY & 0x1f);
    if (w<1||w>2048||h<1||h>2048) return 0;
    int palCount = vm->read32(texPtr+16);
    int palPtr   = vm->read32(texPtr+24);
    int dataPtr  = vm->read32(texPtr+28);

    int handle = nextTexHandle++;
    if (handle >= managedCap){
        int ncap = managedCap ? managedCap*2 : 64;
        if (ncap <= handle) ncap = handle+1;
        Tex* nm = new Tex[ncap];
        for (int i=0;i<ncap;i++){ nm[i].used=false; nm[i].palette=0; nm[i].palCount=0;
                                  nm[i].format=0; nm[i].w=0; nm[i].h=0; nm[i].dataPtr=0; }
        for (int i=0;i<managedCap;i++) nm[i]=managed[i];
        delete[] managed; managed=nm; managedCap=ncap;
    }
    Tex& t = managed[handle];
    if (t.used && t.palette) delete[] t.palette;
    t.used=true; t.format=format; t.w=w; t.h=h; t.dataPtr=dataPtr;
    t.palette = decodeTexPalette(format, palPtr, palCount, &t.palCount);
    return handle;
}
void Renderer3D::setActiveTexture(int handle){
    if (handle<0 || handle>=managedCap || !managed[handle].used) return;  /* mantem a atual */
    Tex& t = managed[handle];
    texturePtr=t.dataPtr; textureFormat=t.format;
    textureW=t.w; textureH=t.h;
    activePalette=t.palette; activePalCount=t.palCount;
}
void Renderer3D::deleteTexture(int handle){
    if (handle<0 || handle>=managedCap || !managed[handle].used) return;
    if (managed[handle].palette == activePalette){ activePalette=0; activePalCount=0; }
    delete[] managed[handle].palette;
    managed[handle].palette=0; managed[handle].palCount=0; managed[handle].used=false;
}
void Renderer3D::deleteAllTextures(){
    for (int i=0;i<managedCap;i++){
        if (managed[i].used && managed[i].palette) delete[] managed[i].palette;
        managed[i].used=false; managed[i].palette=0; managed[i].palCount=0;
    }
    activePalette=0; activePalCount=0;
}
u32 Renderer3D::palLookup(int i) const {
    if (activePalette) return (i>=0 && i<activePalCount) ? activePalette[i] : 0xff000000u;
    return (i>=0 && i<256) ? scr->palette[i] : 0xff000000u;
}
/* texel de 4bpp (nibble), LSB-first (convencao de textura 3D do mophun/nofun) */
int Renderer3D::read4(int idx) const {
    int b = vm->read8(texturePtr + (idx>>1)) & 0xff;
    return ((idx & 1)==0) ? (b & 0x0f) : (b >> 4);
}
u32 Renderer3D::texelAt(int tx,int ty) const {
    int idx = ty*textureW + tx;
    switch (textureFormat){
        case 7:  { int i=vm->read8(texturePtr+idx)&0xff; return (transp3d&&i==0)?0u:scr->rgb332[i]; }   /* RGB332 */
        case 6: case 17: case 19:
                 { int i=vm->read8(texturePtr+idx)&0xff; return (transp3d&&i==0)?0u:palLookup(i); }     /* Palette256* */
        case 5: case 16: case 18:
                 { int i=read4(idx); return (transp3d&&i==0)?0u:palLookup(i); }                         /* Palette16* */
        case 8:  return rgb565a(vm->read16(texturePtr+idx*2)&0xffff);                                   /* RGB565 */
        case 9:  return Screen::rgb555ToArgb(vm->read16(texturePtr+idx*2)&0xffff);                      /* RGB555 */
        case 10: { int b=texturePtr+idx*3;                                                              /* RGB888 */
                   return 0xff000000u | ((u32)(vm->read8(b)&0xff)<<16)
                        | ((u32)(vm->read8(b+1)&0xff)<<8) | (u32)(vm->read8(b+2)&0xff); }
        case 11: return 0xff000000u | ((u32)vm->read32(texturePtr+idx*4) & 0xffffffu);                  /* ARGB8888 */
        case 12: { int w=vm->read16(texturePtr+idx*2)&0xffff;                                           /* RGB444 */
                   int r=(w>>8)&0xf,g=(w>>4)&0xf,bl=w&0xf;
                   return 0xff000000u|((u32)(r*17)<<16)|((u32)(g*17)<<8)|(u32)(bl*17); }
        /* ARGB4444/ARGB1555: no mophun o campo de alpha e TRANSPARENCIA, nao opacidade --
           alpha LIGADO = texel transparente (mesma convencao das paletas 18/19). Medido na
           mao do motorista do Carmageddon 3D (64x64 fmt 13): os 1319 texels com alpha=0xF
           tem TODOS rgb=0 (o fundo) e os 2527 com alpha=0 carregam a luva. O teste invertido
           descartava a mao e pintava o fundo de preto opaco. */
        case 13: { int w=vm->read16(texturePtr+idx*2)&0xffff;                                           /* ARGB4444 */
                   int a=15-((w>>12)&0xf); if (a<=0) return 0u;
                   int r=(w>>8)&0xf,g=(w>>4)&0xf,bl=w&0xf;
                   return ((u32)(a*17)<<24)|((u32)(r*17)<<16)|((u32)(g*17)<<8)|(u32)(bl*17); }
        case 14: { int w=vm->read16(texturePtr+idx*2)&0xffff;                                           /* ARGB1555 */
                   if ((w & 0x8000)!=0) return 0u;
                   int r=(w>>10)&0x1f,g=(w>>5)&0x1f,bl=w&0x1f;
                   return 0xff000000u|((u32)iround3(r,255,31)<<16)|((u32)iround3(g,255,31)<<8)|(u32)iround3(bl,255,31); }
        default: { int i=vm->read8(texturePtr+idx)&0xff; return (transp3d&&i==0)?0u:palLookup(i); }
    }
}
u32 Renderer3D::texelNearest(float u,float v) const {
    int tx = wrapCoord((int)wpFloor(u*textureW), textureW);
    int ty = wrapCoord((int)wpFloor(v*textureH), textureH);
    return texelAt(tx,ty);
}
/* Bilinear com tratamento de transparencia: o texel de referencia decide se o fragmento
   e transparente (senao as bordas de sprites/ceu "sangram" cor de fora), e apenas os 4
   vizinhos OPACOS entram na media ponderada. */
u32 Renderer3D::texelBilinear(float u,float v) const {
    float fx=u*textureW-0.5f, fy=v*textureH-0.5f;
    int x0=(int)wpFloor(fx), y0=(int)wpFloor(fy);
    float ax=fx-x0, ay=fy-y0;
    int cx=wrapCoord((int)wpFloor(u*textureW), textureW);
    int cy=wrapCoord((int)wpFloor(v*textureH), textureH);
    if (texelAt(cx,cy)==0u) return 0u;
    int tx0=wrapCoord(x0,textureW), tx1=wrapCoord(x0+1,textureW);
    int ty0=wrapCoord(y0,textureH), ty1=wrapCoord(y0+1,textureH);
    u32 c00=texelAt(tx0,ty0), c10=texelAt(tx1,ty0), c01=texelAt(tx0,ty1), c11=texelAt(tx1,ty1);
    float w00=(1-ax)*(1-ay), w10=ax*(1-ay), w01=(1-ax)*ay, w11=ax*ay;
    float wsum=0,r=0,g=0,b=0,a=0;
    if (c00){ wsum+=w00; r+=((c00>>16)&0xff)*w00; g+=((c00>>8)&0xff)*w00; b+=(c00&0xff)*w00; a+=(c00>>24)*w00; }
    if (c10){ wsum+=w10; r+=((c10>>16)&0xff)*w10; g+=((c10>>8)&0xff)*w10; b+=(c10&0xff)*w10; a+=(c10>>24)*w10; }
    if (c01){ wsum+=w01; r+=((c01>>16)&0xff)*w01; g+=((c01>>8)&0xff)*w01; b+=(c01&0xff)*w01; a+=(c01>>24)*w01; }
    if (c11){ wsum+=w11; r+=((c11>>16)&0xff)*w11; g+=((c11>>8)&0xff)*w11; b+=(c11&0xff)*w11; a+=(c11>>24)*w11; }
    if (wsum <= 1e-6f) return 0u;
    int ir=clamp255((int)(r/wsum+0.5f)), ig=clamp255((int)(g/wsum+0.5f));
    int ib=clamp255((int)(b/wsum+0.5f)), ia=clamp255((int)(a/wsum+0.5f));
    return ((u32)ia<<24)|((u32)ir<<16)|((u32)ig<<8)|(u32)ib;
}
/* Amostragem honrando VMGP3D_FILTERMODE(13). O SDK lista TEXFILTER_LINEAR e, ao contrario
   de FOGENABLE/PERSPECTIVEENABLE, NAO o marca como "not supported when software renderer
   are used" -- filtrar e legitimo. */
u32 Renderer3D::texSample(float u,float v) const {
    bool linear = (texFilterForce==1)
               || (texFilterForce==0 && (filterMode==2 || filterMode==8 || filterMode==32));
    return linear ? texelBilinear(u,v) : texelNearest(u,v);
}

/* ---------------- iluminacao T&L ---------------- */
/* vSetAmbientLight(rgb): quando o bit VRGB_TAG (0x80000000) esta setado E os bits 16..30
   sao zero, os 16 bits baixos sao uma cor RGB555 (formato nativo do mophun). PROVA: o
   Anarchy Boxing 3D passa 0x80007BDE e 0x8000318C -- em RGB555 dao cinzas neutros
   (exatamente o que uma luz ambiente deve ser); lidos como 0xRRGGBB viravam azul puro e
   tingiam a cena inteira. Mas o tutorial Earth3D do SDK passa 0xff444444, que tem o bit
   31 setado e e um ARGB comum -- por isso o teste tambem exige (rgb & 0x7fff0000)==0. */
void Renderer3D::setAmbientLight(int rgb){
    if ((rgb & 0x80000000) != 0 && (rgb & 0x7fff0000) == 0){
        int v = rgb & 0xffff;
        int r5=(v>>10)&31, g5=(v>>5)&31, b5=v&31;
        ambientScene = (iround3(r5,255,31)<<16) | (iround3(g5,255,31)<<8) | iround3(b5,255,31);
    } else {
        ambientScene = rgb & 0xffffff;
    }
}
void Renderer3D::setCameraPos(int p){ camX=vx(p); camY=vy(p); camZ=vz(p); }
void Renderer3D::resetLights(){ for (int i=0;i<MAXL;i++) lightUsed[i]=false; }
/* LIGHT(40B): pos@0, dir@12, type@24(u16), diff r,g,b@26, spec@29, range@32(1/range),
   exponent@36(8:8), cutoff@38 */
void Renderer3D::setLight(int index,int p){
    if (index<0 || index>=MAXL) return;
    float* L = lights[index];
    L[0]=(float)(vm->read16(p+24)&0xffff);
    L[1]=vx(p);      L[2]=vy(p);      L[3]=vz(p);
    L[4]=vx(p+12);   L[5]=vy(p+12);   L[6]=vz(p+12);
    L[7]=(float)vm->read8(p+26);  L[8]=(float)vm->read8(p+27);  L[9]=(float)vm->read8(p+28);
    L[10]=(float)vm->read8(p+29); L[11]=(float)vm->read8(p+30); L[12]=(float)vm->read8(p+31);
    L[13]=f2f(vm->read32(p+32));
    L[14]=(vm->read16(p+36)&0xffff)/256.0f;
    L[15]=(float)(vm->read16(p+38)&0xffff);
    lightUsed[index]=true;
}
/* MATERIAL = DSCOLOR: diff{b,g,r,a}@0, spec{b,g,r,f}@4 */
void Renderer3D::setMaterial(int p){
    matDiffB=vm->read8(p);   matDiffG=vm->read8(p+1); matDiffR=vm->read8(p+2);
    matSpecB=vm->read8(p+4); matSpecG=vm->read8(p+5); matSpecR=vm->read8(p+6);
    matEmR=matEmG=matEmB=0; matAmbR=matAmbG=matAmbB=255; matShininess=4.0f;
}
/* MATERIAL2: ambient,diff,spec,emission (DCOLOR@0,4,8,12), shininess@16(fixed) */
void Renderer3D::setMaterial2(int p){
    matAmbB=vm->read8(p);     matAmbG=vm->read8(p+1);  matAmbR=vm->read8(p+2);
    matDiffB=vm->read8(p+4);  matDiffG=vm->read8(p+5); matDiffR=vm->read8(p+6);
    matSpecB=vm->read8(p+8);  matSpecG=vm->read8(p+9); matSpecR=vm->read8(p+10);
    matEmB=vm->read8(p+12);   matEmG=vm->read8(p+13);  matEmR=vm->read8(p+14);
    matShininess=f2f(vm->read32(p+16));
}
u32 Renderer3D::diffuseArgb(int p) const {
    /* DCOLOR = {b,g,r,a}; preserva o alpha difuso (usado pelo color-buffer blend) */
    int b=vm->read8(p), g=vm->read8(p+1), r=vm->read8(p+2), a=vm->read8(p+3);
    return ((u32)a<<24)|((u32)r<<16)|((u32)g<<8)|(u32)b;
}
/* Aprox. fixed-function: cor = emission + diff*ambienteCena + soma_luzes(diff*luz*max(0,N.L)*atten).
   ILUMINACAO EM ESPACO DE OBJETO: a doc (5.33.1.15) diz que a matriz de luz "should be the
   INVERSE of the world matrix" -- o proposito de uma inversa-do-mundo e trazer as luzes
   (definidas em mundo) para o espaco do OBJETO, onde as normais ja estao. Rotacionar a
   NORMAL p/ view e transformar a LUZ pela matriz de luz misturava dois espacos -> N.L
   incoerente e sombreado quase constante (o carro do Rally saia chapado). */
u32 Renderer3D::shadeVertex(int vertPtr,int normalPtr,u32 baseArgb){
    u32 alpha = baseArgb >> 24;
    float dr=(float)((baseArgb>>16)&0xff), dg=(float)((baseArgb>>8)&0xff), db=(float)(baseArgb&0xff);
    float wnx=vx(normalPtr), wny=vy(normalPtr), wnz=vz(normalPtr);
    float wx=f2f(vm->read32(vertPtr)), wy=f2f(vm->read32(vertPtr+4)), wz=f2f(vm->read32(vertPtr+8));
    float nl = wpSqrt(wnx*wnx+wny*wny+wnz*wnz);
    if (nl > 1e-6f){ wnx/=nl; wny/=nl; wnz/=nl; }
    float sar=((ambientScene>>16)&0xff)/255.0f;
    float sag=((ambientScene>>8)&0xff)/255.0f;
    float sab=(ambientScene&0xff)/255.0f;
    float rr=matEmR + dr*sar, gg=matEmG + dg*sag, bb=matEmB + db*sab;
    for (int li=0; li<MAXL; li++){
        if (!lightUsed[li]) continue;
        float* L = lights[li];
        int type=(int)L[0];
        float lx,ly,lz, atten=1.0f;
        if (type==2){        /* DIRECTIONAL: vetor p/ a luz = -dir. A DIRECAO passa pela
                                matriz de luz, so a parte 3x3 (vetor, sem translacao). */
            float dx=L[4], dy=L[5], dz=L[6];
            float tx=light[0]*dx+light[1]*dy+light[2]*dz;
            float ty=light[4]*dx+light[5]*dy+light[6]*dz;
            float tz=light[8]*dx+light[9]*dy+light[10]*dz;
            lx=-tx; ly=-ty; lz=-tz;
            float ll=wpSqrt(lx*lx+ly*ly+lz*lz);
            if (ll>1e-6f){ lx/=ll; ly/=ll; lz/=ll; }
        } else {             /* POINT/SPOT: vetor do vertice ate a luz + atenuacao.
                                A POSICAO passa pela matriz de luz completa (com translacao). */
            float px=L[1], py=L[2], pz=L[3];
            float tx=light[0]*px+light[1]*py+light[2]*pz+light[3];
            float ty=light[4]*px+light[5]*py+light[6]*pz+light[7];
            float tz=light[8]*px+light[9]*py+light[10]*pz+light[11];
            lx=tx-wx; ly=ty-wy; lz=tz-wz;
            float dist=wpSqrt(lx*lx+ly*ly+lz*lz);
            if (dist>1e-6f){ lx/=dist; ly/=dist; lz/=dist; }
            if (L[13] > 0.0f){ atten = 1.0f - dist*L[13]; if (atten<0.0f) atten=0.0f; }
        }
        float ndotl = wnx*lx + wny*ly + wnz*lz;
        if (ndotl < 0.0f) ndotl = 0.0f;
        float k = ndotl*atten;
        rr += dr*(L[7]/255.0f)*k;
        gg += dg*(L[8]/255.0f)*k;
        bb += db*(L[9]/255.0f)*k;
        /* ESPECULAR (Blinn-Phong) -- so com SPECULARENABLE (estado 16). O observador em
           espaco de OBJETO e a origem do view levada pela inversa-do-mundo, que e
           exatamente a coluna de translacao da matriz de luz. */
        if (specularEnable && ndotl > 0.0f){
            float eox=light[3]-wx, eoy=light[7]-wy, eoz=light[11]-wz;
            float vlen=wpSqrt(eox*eox+eoy*eoy+eoz*eoz);
            float ex = vlen>1e-6f ? eox/vlen : 0.0f;
            float ey = vlen>1e-6f ? eoy/vlen : 0.0f;
            float ez = vlen>1e-6f ? eoz/vlen : 1.0f;
            float hx=lx+ex, hy=ly+ey, hz=lz+ez;
            float hl=wpSqrt(hx*hx+hy*hy+hz*hz);
            if (hl>1e-6f){
                hx/=hl; hy/=hl; hz/=hl;
                float ndoth = wnx*hx + wny*hy + wnz*hz;
                if (ndoth > 0.0f){
                    float sh = matShininess>0.5f ? matShininess : 4.0f;
                    float sp = wpPow(ndoth, sh) * atten;
                    rr += matSpecR * (L[10]/255.0f) * sp;
                    gg += matSpecG * (L[11]/255.0f) * sp;
                    bb += matSpecB * (L[12]/255.0f) * sp;
                }
            }
        }
    }
    return (alpha<<24) | ((u32)clamp255((int)rr)<<16) | ((u32)clamp255((int)gg)<<8) | (u32)clamp255((int)bb);
}
/* vLightPoint(LIGHTARGS*): pre-ilumina vertices escrevendo dstdiff (DCOLOR).
   LIGHTARGS: v@0, n@4, color@8, dstdiff@12, dstspec@16, count@20 */
void Renderer3D::lightPoint(int argsPtr){
    int vP=vm->read32(argsPtr), nP=vm->read32(argsPtr+4), colP=vm->read32(argsPtr+8);
    int ddP=vm->read32(argsPtr+12), dsP=vm->read32(argsPtr+16);
    int count=vm->read16(argsPtr+20)&0xffff;
    if (vP==0||nP==0) return;
    for (int i=0;i<count;i++){
        u32 base = colP!=0 ? diffuseArgb(colP+i*4)
                           : (0xff000000u|((u32)matDiffR<<16)|((u32)matDiffG<<8)|(u32)matDiffB);
        u32 lit = shadeVertex(vP+i*12, nP+i*12, base);
        if (ddP!=0){
            vm->write8(ddP+i*4,   (int)(lit & 0xff));
            vm->write8(ddP+i*4+1, (int)((lit>>8) & 0xff));
            vm->write8(ddP+i*4+2, (int)((lit>>16) & 0xff));
            vm->write8(ddP+i*4+3, (int)(lit>>24));
        }
        if (dsP!=0) vm->write32(dsP+i*4, 0);   /* specular nao calculado (aprox.) */
    }
}
