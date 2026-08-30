package wolphun.vm;

import wolphun.Screen;

/**
 * Rasterizador de software do subsistema 3D do mophun (modulo VMGP3D), portado do
 * nofun (que usa a GPU do Unity). Implementa a pilha de matrizes (Fixed 14-bit,
 * column-major default), transformacao de vertices e preenchimento de triangulos
 * com z-buffer + gouraud (cor difusa interpolada). clip = projection * current * v.
 *
 * Formatos: posicao/ matriz = Fixed/16384; UV = /1024; angulo = (d&0xFFF)/4095 volta.
 * NativeMesh(22B): vertices@0, uvs@4, diffuses@8, speculars@12, normal@16, count(u16)@20.
 * NativeVector3D(12B): x@0,y@4,z@8. NativeDiffuseColor(4B): b,g,r,a.
 */
public final class Renderer3D {
    final Pip2 vm; final Screen scr;

    // matrizes (row-major float[16], m[r*4+c]); transform v' = M*v, mult A*B
    float[] current = ident();
    float[] projection = ident();
    float[] light = ident();
    int matrixOrientation = 0x2000;      // ColumnMajor (default)

    // viewport + z-buffer
    int vpL=0, vpT=0, vpW, vpH;
    public long drawCalls=0, trisDrawn=0, pixelsDrawn=0, billboards=0;   // instrumentacao
    float[] zbuf; int zW, zH;
    int cullMode = 2;                     // DEFAULT = CounterClockwise (nofun ClientState.cs) -> backface culling LIGADO
    boolean textureMode = false;
    boolean transp3d = false;            // TransparentEnable (state 15): indice 0 da textura = transparente
    // --- render states VMGP3D honrados (defaults escolhidos p/ = comportamento anterior; sem regressao) ---
    int shadeMode = 2;                   // state 7:  1=FLAT, 2=GOURAUD (default)
    int texBlendMode = 1;                // state 11: 1=DECAL/REPLACE (default), 2=MODULATE, 4=ADD
    int colorBlend = 0;                  // state 20: 0=REPLACE (opaco, default),1=ALPHAADD,2=ALPHA,3=MODULATE
    boolean alphaEnable = false;         // state 12: habilita blending pelo alpha difuso do vertice
    int wrapMode = 1;                    // state 14: 1=WRAP (default), 2=CLAMP, 4=MIRROR
    boolean zEnable = true;              // state 4:  z-buffer on (default)
    int zFunc = 2;                       // state 5:  2=LESS (default)
    boolean fogEnable = false;           // state 8
    int fogMode = 1;                     // state 22: 0=EXP, 1=LINEAR
    int fogColor = 0x000000;             // state 21 / vSetFogColor (RGB888)
    float fogStart = 0f, fogEnd = 1f, fogDensity = 0f;  // states 23,24,25 (fixed->float)
    boolean lightingEnable = false;      // state 17
    boolean specularEnable = false;      // state 16

    // --- iluminacao T&L (VMGP3D). So aplicada quando LIGHTINGENABLE (state 17); default off -> sem efeito. ---
    static final int MAXL = 8;
    int ambientScene = 0;                // vSetAmbientLight (RGB888)
    // cada luz: [type, px,py,pz, dx,dy,dz, r,g,b, sr,sg,sb, range(1/range), exponent, cutoff]
    final float[][] lights = new float[MAXL][];
    // material (componentes 0..255; shininess em float)
    int matDiffR=255,matDiffG=255,matDiffB=255;
    int matSpecR=0,matSpecG=0,matSpecB=0;
    int matEmR=0,matEmG=0,matEmB=0;
    int matAmbR=255,matAmbG=255,matAmbB=255;
    float matShininess=4f;
    float camX=0,camY=0,camZ=0;          // vSetCameraPos (para specular)
    static final boolean NOCULL = System.getenv("NOCULL")!=null;      // TESTE A/B: desliga backface culling
    static final boolean NOTRANSP = System.getenv("NOTRANSP")!=null;  // TESTE A/B: desliga transparencia 3D
    int texturePtr = 0, textureFormat = 0, textureW = 0, textureH = 0;
    // paleta EMBUTIDA da textura ativa (formatos self-palette 16-19 / vCreateTexture).
    // null = textura indexada resolve pela paleta de TELA (scr.palette), igual ao vSetTexture do nofun.
    int[] activePalette = null;
    // texturas gerenciadas (vCreateTexture -> handle; vSetActiveTexture(handle) as reativa).
    final java.util.HashMap<Integer,Tex> managed = new java.util.HashMap<Integer,Tex>();
    int nextTexHandle = 1;
    static final class Tex { int format, w, h, dataPtr; int[] palette; }

    public Renderer3D(Pip2 vm, Screen scr){ this.vm=vm; this.scr=scr; this.vpW=scr.w; this.vpH=scr.h; }

    // ---------------- fixed-point ----------------
    static float f2f(int fx){ return fx/16384.0f; }              // 14-bit
    static int   ff(float v){ return (int)(v*16384f); }
    static float uv2f(short u){ return u/1024.0f; }              // 9-point
    static float ang2rad(int d){ return (d & 0xFFF)/4095.0f * (float)(Math.PI*2); }
    static float ang2deg(int d){ return (d & 0xFFF)/4095.0f * 360f; }

    public int vMul(int a,int b){ return (int)(((long)a*b) >> 14); }
    public int vDiv(int a,int b){ return b==0?0:(int)(((long)a<<14)/b); }
    public int vPow(int val,int exp){ return ff((float)Math.pow(f2f(val), exp&0xff)); }
    public int vSqrt(int v){ return ff((float)Math.sqrt(f2f(v))); }
    public int vSin(int v){ return ff((float)Math.sin(ang2rad(v))); }
    public int vCos(int v){ return ff((float)Math.cos(ang2rad(v))); }
    public int vTan(int v){ return ff((float)Math.tan(ang2rad(v))); }

    // ---------------- vetores 3D (NativeVector3D 12B: x@0,y@4,z@8 Fixed) ----------------
    float vx(int p){ return f2f(vm.read32(p)); } float vy(int p){ return f2f(vm.read32(p+4)); } float vz(int p){ return f2f(vm.read32(p+8)); }
    void putV(int p,float x,float y,float z){ vm.write32(p,ff(x)); vm.write32(p+4,ff(y)); vm.write32(p+8,ff(z)); }
    // add/sub sao soma/subtracao direta dos raw fixed (mesma escala)
    public void vectorAdd(int d,int a,int b){ for(int i=0;i<12;i+=4) vm.write32(d+i, vm.read32(a+i)+vm.read32(b+i)); }
    public void vectorSub(int d,int a,int b){ for(int i=0;i<12;i+=4) vm.write32(d+i, vm.read32(a+i)-vm.read32(b+i)); }
    public void vectorMul(int d,int a,int b){ putV(d, vx(a)*vx(b), vy(a)*vy(b), vz(a)*vz(b)); }        // componente a componente
    public void vectorNormalize(int d){ float x=vx(d),y=vy(d),z=vz(d); double l=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
        if(l>1e-9) putV(d,(float)(x/l),(float)(y/l),(float)(z/l)); }
    public void crossProduct(int d,int a,int b){ float ax=vx(a),ay=vy(a),az=vz(a),bx=vx(b),by=vy(b),bz=vz(b);
        putV(d, ay*bz-az*by, az*bx-ax*bz, ax*by-ay*bx); }
    public int dotProduct(int a,int b){ return ff(vx(a)*vx(b)+vy(a)*vy(b)+vz(a)*vz(b)); }
    // transforma pontos pela matriz ATUAL (MultiplyPoint3x4: afim, w=1)
    public void vectorTransformV3(int dest,int src,int count){ float[] o=new float[4];
        for(int i=0;i<count;i++){ int s=src+i*12,d=dest+i*12; xform(current, vx(s),vy(s),vz(s),1f, o); putV(d,o[0],o[1],o[2]); } }

    // ---------------- frustum / plano / colisao ----------------
    // vBoxInViewFrustum(NativeBBox min@0,max@12): 1 se a caixa PODE estar visivel; 0 se totalmente fora
    // (senao o jogo faz culling e nada 3D aparece). Portado do nofun Helper.cs.
    public long frustumVis=0, frustumCull=0;   // instrumentacao
    public int boxInViewFrustum(int b){
        float mnx=vx(b),mny=vy(b),mnz=vz(b), mxx=vx(b+12),mxy=vy(b+12),mxz=vz(b+12);
        float[] pv=mul(projection,current);
        float[][] pts={{mnx,mny,mnz},{mnx,mny,mxz},{mxx,mny,mxz},{mxx,mny,mnz},{mxx,mxy,mxz},{mxx,mxy,mnz},{mnx,mxy,mnz},{mnx,mxy,mxz}};
        float[][] cl=new float[8][4];
        for(int i=0;i<8;i++) xform(pv, pts[i][0],pts[i][1],pts[i][2],1f, cl[i]);
        // caixa que ATRAVESSA a camera (algum canto em/atras do near, w<=0): o teste -w<=coord<=w e
        // instavel com w negativo e pode CULAR por engano uma chunk em que estamos entrando -> geometria
        // do mapa "some ao mover". Nesse caso nao culamos (o rasterizador ja recorta no near). So reduz culling.
        for(int i=0;i<8;i++) if(cl[i][3] <= 1e-4f){ frustumVis++; return 1; }
        for(int c=0;c<3;c++){
            boolean in=false; for(int i=0;i<8;i++) if(cl[i][c] > -cl[i][3]){ in=true; break; } if(!in){ frustumCull++; return 0; }
            in=false;        for(int i=0;i<8;i++) if(cl[i][c] <  cl[i][3]){ in=true; break; } if(!in){ frustumCull++; return 0; }
        }
        frustumVis++; return 1;
    }
    // vCreatePlaneFromPoly(NativePlane dest{normal@0,dist@12}, 3x NativeVector3D): normal=norm((p1-p3)x(p2-p3)), dist=dot(n,p1)
    public void createPlaneFromPoly(int dest,int poly){
        float p1x=vx(poly),p1y=vy(poly),p1z=vz(poly);
        float p2x=vx(poly+12),p2y=vy(poly+12),p2z=vz(poly+12);
        float p3x=vx(poly+24),p3y=vy(poly+24),p3z=vz(poly+24);
        float e1x=p1x-p3x,e1y=p1y-p3y,e1z=p1z-p3z, e2x=p2x-p3x,e2y=p2y-p3y,e2z=p2z-p3z;
        float nx=e1y*e2z-e1z*e2y, ny=e1z*e2x-e1x*e2z, nz=e1x*e2y-e1y*e2x;
        double l=Math.sqrt((double)nx*nx+ny*ny+nz*nz); if(l<1e-9) l=1;
        nx/=l; ny/=l; nz/=l;
        putV(dest,nx,ny,nz); vm.write32(dest+12, ff(nx*p1x+ny*p1y+nz*p1z));
    }
    // vCollisionPointBox(point, NativeBBox): 1 se min<=point<=max (compara raw fixed direto)
    public int collisionPointBox(int point,int box){
        for(int i=0;i<12;i+=4){ int p=vm.read32(point+i); if(p<vm.read32(box+i) || p>vm.read32(box+12+i)) return 0; } return 1;
    }
    public int collisionBoxBox(int b1,int b2){
        for(int i=0;i<12;i+=4){ if(vm.read32(b1+i)>vm.read32(b2+12+i) || vm.read32(b1+12+i)<vm.read32(b2+i)) return 0; } return 1;
    }
    // vVectorTransformV4(dstV4, srcV3, count): dst[i] = current * (src,1), guarda x,y,z,w (NativeVector4D=16B)
    public void vectorTransformV4(int dst,int src,int count){ float[] o=new float[4];
        for(int i=0;i<count;i++){ int s=src+i*12,d=dst+i*16; xform(current, vx(s),vy(s),vz(s),1f, o);
            vm.write32(d,ff(o[0])); vm.write32(d+4,ff(o[1])); vm.write32(d+8,ff(o[2])); vm.write32(d+12,ff(o[3])); } }
    // vVectorProjectV3/V4: projeta ponto(view space) -> tela. nofun VectorProjectImpl: X/Y em 1/16 px, Z/W fixed.
    public void vectorProjectV3(int dst,int src,int count){ projectImpl(dst,src,count,false); }
    public void vectorProjectV4(int dst,int src,int count){ projectImpl(dst,src,count,true); }
    void projectImpl(int dst,int src,int count,boolean v4){ int ss=v4?16:12; float[] o=new float[4];
        for(int i=0;i<count;i++){ int s=src+i*ss,d=dst+i*16;
            float w=v4? f2f(vm.read32(s+12)) : 1f;
            xform(projection, vx(s),vy(s),vz(s), w, o);
            if(o[3]==0f){ vm.write32(d,ff(o[0])); vm.write32(d+4,ff(o[1])); vm.write32(d+8,ff(4)); vm.write32(d+12,0); }
            else { float px=o[0]/o[3]*(vpW*0.5f)+(vpL+vpW*0.5f), py=o[1]/o[3]*(-vpH*0.5f)+(vpT+vpH*0.5f), pz=o[2]/o[3]*2f+2f;
                vm.write32(d,(int)(px*16)); vm.write32(d+4,(int)(py*16)); vm.write32(d+8,ff(pz)); vm.write32(d+12,ff(o[3])); } } }
    // vMatrixInvert: current = inversa(current)
    public void matrixInvert(){ float[] inv=invert4x4(current); if(inv!=null) current=inv; }
    public void matrixTranspose(){ float[] t=new float[16];
        for(int r=0;r<4;r++) for(int c=0;c<4;c++) t[c*4+r]=current[r*4+c]; current=t; }
    // multiplica so os componentes de rotacao (3x3) da matriz atual pela matriz especificada
    public void matrixMultiply3x3(int ptr){ float[] m=readMatrix(ptr); float[] r3=ident();
        for(int r=0;r<3;r++) for(int c=0;c<3;c++) r3[r*4+c]=m[r*4+c]; current=mul(current,r3); }
    // vGetInteger: consulta um estado interno estatico (capabilities do renderer de software)
    public int getInteger(int param){
        switch(param){
            case 1: return 8;        // MAX_LIGHTS
            case 2: return 4096;     // MAX_VERTICES (recomendado)
            case 3: return 256;      // MAX_TEXTURE_SIZE
            case 4: return 4096;     // MAX_TEXTURE_COUNT
            case 5: return 1<<20;    // TEXTURE_CACHE_SIZE (bytes)
            case 6: return 16;       // DEPTH_BITS (temos z-buffer)
            default: return 0;
        }
    }
    // le o z-buffer (0 se fora do clip-window); depth [0,1] -> uint16
    public int getZBufferValue(int x,int y){
        if(zbuf==null||x<scr.clipX0||x>=scr.clipX1||y<scr.clipY0||y>=scr.clipY1) return 0;
        float d=zbuf[(y*scr.rs)*zW+(x*scr.rs)]; if(d==Float.MAX_VALUE) return 0;
        int v=(int)(d*65535f); return v<0?0:(v>65535?65535:v);
    }
    public void vectorArrayAdd(int d,int s1,int s2,int count){ for(int i=0;i<count;i++) vectorAdd(d+i*12,s1+i*12,s2+i*12); }
    // vVectorArrayDelta: d = (s2 - s1) / steps ; low16=nr de vetores, high16=steps
    public void vectorArrayDelta(int d,int s1,int s2,int param){
        int count=param&0xffff, steps=(param>>>16); if(steps==0) steps=1;
        for(int i=0;i<count;i++){ int a=s1+i*12,b=s2+i*12,o=d+i*12;
            putV(o, (vx(b)-vx(a))/steps, (vy(b)-vy(a))/steps, (vz(b)-vz(a))/steps); } }
    static float[] invert4x4(float[] m){
        float[] inv=new float[16];
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
        float det=m[0]*inv[0]+m[1]*inv[4]+m[2]*inv[8]+m[3]*inv[12];
        if(Math.abs(det)<1e-12f) return null;
        det=1f/det; for(int i=0;i<16;i++) inv[i]*=det; return inv;
    }
    // vCollisionVectorPoly/Plane: intersecao de segmento com plano (opcional dentro do triangulo). nofun Helper.cs
    public int collisionVectorPoly(int collPos,int lineV,int polyV,int plane){
        float nx,ny,nz,pw;
        if(plane!=0){ nx=vx(plane); ny=vy(plane); nz=vz(plane); pw=f2f(vm.read32(plane+12)); }
        else if(polyV!=0){ float p1x=vx(polyV),p1y=vy(polyV),p1z=vz(polyV),p2x=vx(polyV+12),p2y=vy(polyV+12),p2z=vz(polyV+12),p3x=vx(polyV+24),p3y=vy(polyV+24),p3z=vz(polyV+24);
            float e1x=p1x-p3x,e1y=p1y-p3y,e1z=p1z-p3z,e2x=p2x-p3x,e2y=p2y-p3y,e2z=p2z-p3z;
            nx=e1y*e2z-e1z*e2y; ny=e1z*e2x-e1x*e2z; nz=e1x*e2y-e1y*e2x;
            double l=Math.sqrt((double)nx*nx+ny*ny+nz*nz); if(l<1e-9)l=1; nx/=l;ny/=l;nz/=l; pw=nx*p1x+ny*p1y+nz*p1z; }
        else return 0;
        float l0x=vx(lineV),l0y=vy(lineV),l0z=vz(lineV), dx=vx(lineV+12)-l0x,dy=vy(lineV+12)-l0y,dz=vz(lineV+12)-l0z;
        float mult=dx*nx+dy*ny+dz*nz; if(Math.abs(mult)<=1e-6f) return 0;
        float t=(pw-(nx*l0x+ny*l0y+nz*l0z))/mult;
        float cx=l0x+dx*t, cy=l0y+dy*t, cz=l0z+dz*t;
        if(polyV!=0){
            float a0=vx(polyV)-cx,a1=vy(polyV)-cy,a2=vz(polyV)-cz, b0=vx(polyV+12)-cx,b1=vy(polyV+12)-cy,b2=vz(polyV+12)-cz, c0=vx(polyV+24)-cx,c1=vy(polyV+24)-cy,c2=vz(polyV+24)-cz;
            float ux=b1*c2-b2*c1,uy=b2*c0-b0*c2,uz=b0*c1-b1*c0;   // cross(b,c)
            float vX=c1*a2-c2*a1,vY=c2*a0-c0*a2,vZ=c0*a1-c1*a0;    // cross(c,a)
            float wX=a1*b2-a2*b1,wY=a2*b0-a0*b2,wZ=a0*b1-a1*b0;    // cross(a,b)
            if(ux*vX+uy*vY+uz*vZ < 0) return 0;
            if(vX*wX+vY*wY+vZ*wZ < 0) return 0;
        }
        putV(collPos,cx,cy,cz); return 1;
    }
    public int collisionVectorPlane(int collPos,int lineV,int plane){ return collisionVectorPoly(collPos,lineV,0,plane); }

    // vDrawBillboard(NativeBillboard): quad TEXTURIZADO que sempre encara a camera, na posicao 3D.
    // NativeBillboard(56B): pos@0(12B), w@12, h@16, uv0@20, uv1@24, uv2@28, uv3@32, color0-3@36-48, rot@52, pivot@54.
    // Transforma o centro pela matriz ATUAL (view) -> monta 4 cantos alinhados a tela (camera-facing) ->
    // projeta e rasteriza 2 triangulos com a textura ativa. (pivot/rotacao: centro por ora)
    public void drawBillboard(int bb){
        if(bb==0) return; ensureZ();
        float px=vx(bb), py=vy(bb), pz=vz(bb);
        float w=f2f(vm.read32(bb+12)), h=f2f(vm.read32(bb+16));
        float[] c=new float[4]; xform(current, px,py,pz,1f, c);          // centro em view space
        float hw=w*0.5f, hh=h*0.5f;
        // cantos alinhados a tela (offset nos eixos X/Y da view): TL, TR, BR, BL
        float[][] vs={{c[0]-hw,c[1]+hh,c[2]},{c[0]+hw,c[1]+hh,c[2]},{c[0]+hw,c[1]-hh,c[2]},{c[0]-hw,c[1]-hh,c[2]}};
        float[][] cl=new float[4][4];
        for(int i=0;i<4;i++) xform(projection, vs[i][0],vs[i][1],vs[i][2],1f, cl[i]);
        float[][] uv=new float[4][2]; int[] uo={20,24,28,32};
        for(int i=0;i<4;i++){ uv[i][0]=uv2f((short)vm.read16(bb+uo[i])); uv[i][1]=uv2f((short)vm.read16(bb+uo[i]+2)); }
        boolean tex = texturePtr!=0 && textureW>0;
        int W=0xffffffff;
        int savedCull=cullMode; boolean savedT=transp3d;
        cullMode=0; transp3d=!NOTRANSP;                  // billboard: nunca cull + indice 0 transparente (recorte da arvore)
        clipRaster(cl[0],cl[1],cl[2], W,W,W, uv[0],uv[1],uv[2], tex);
        clipRaster(cl[0],cl[2],cl[3], W,W,W, uv[0],uv[2],uv[3], tex);
        cullMode=savedCull; transp3d=savedT;
        drawCalls++; billboards++;
    }

    // ---------------- matriz: helpers ----------------
    static float[] ident(){ float[] m=new float[16]; m[0]=m[5]=m[10]=m[15]=1; return m; }
    static float[] mul(float[] a, float[] b){    // a*b
        float[] r=new float[16];
        for(int i=0;i<4;i++) for(int j=0;j<4;j++){ float s=0;
            for(int k=0;k<4;k++) s+=a[i*4+k]*b[k*4+j]; r[i*4+j]=s; }
        return r;
    }
    // transforma (x,y,z,w) por m -> out[4]
    static void xform(float[] m, float x,float y,float z,float w, float[] out){
        out[0]=m[0]*x+m[1]*y+m[2]*z+m[3]*w;
        out[1]=m[4]*x+m[5]*y+m[6]*z+m[7]*w;
        out[2]=m[8]*x+m[9]*y+m[10]*z+m[11]*w;
        out[3]=m[12]*x+m[13]*y+m[14]*z+m[15]*w;
    }

    float[] readMatrix(int ptr){
        if(ptr==0) return current.clone();
        float[] m=new float[16];
        for(int r=0;r<4;r++) for(int c=0;c<4;c++){
            int fx=vm.read32(ptr + (r*4+c)*4);
            if(matrixOrientation==0x2000) m[r*4+c]=f2f(fx);   // ColumnMajor: campo mRC = elemento [r][c]
            else                          m[c*4+r]=f2f(fx);   // RowMajor: transpoe
        }
        return m;
    }
    public void matrixGetCurrent(int ptr){
        if(ptr==0) return;
        for(int r=0;r<4;r++) for(int c=0;c<4;c++){
            float v = (matrixOrientation==0x2000)? current[r*4+c] : current[c*4+r];
            vm.write32(ptr + (r*4+c)*4, ff(v));
        }
    }

    // ---------------- API de matriz ----------------
    public void setMatrixMode(int mode){ if(mode==0||mode==0x2000) matrixOrientation=mode; }
    public void matrixIdentity(){ current=ident(); }
    public void matrixSetCurrent(int ptr){ current=readMatrix(ptr); }
    public void matrixMultiply(int ptr){ current=mul(current, readMatrix(ptr)); }
    public void matrixSetProjection(int ptr){ projection=readMatrix(ptr); }
    public void matrixSetLight(int ptr){ light=readMatrix(ptr); }
    public void matrixTranslate(int x,int y,int z){
        float[] t=ident(); t[3]=f2f(x); t[7]=f2f(y); t[11]=f2f(z); current=mul(current,t);
    }
    public void matrixScale(int x,int y,int z){
        float[] s=ident(); s[0]=f2f(x); s[5]=f2f(y); s[10]=f2f(z); current=mul(current,s);
    }
    public void matrixRotateX(int d){ current=mul(current, rot(ang2deg(d),1,0,0)); }
    public void matrixRotateY(int d){ current=mul(current, rot(ang2deg(d),0,1,0)); }
    public void matrixRotateZ(int d){ current=mul(current, rot(ang2deg(d),0,0,1)); }
    public void matrixRotateVector(int axisPtr,int d){
        float ax=f2f(vm.read32(axisPtr)), ay=f2f(vm.read32(axisPtr+4)), az=f2f(vm.read32(axisPtr+8));
        current=mul(current, rot(ang2deg(d),-ax,-ay,-az));
    }
    static float[] rot(float deg, float ax,float ay,float az){
        float n=(float)Math.sqrt(ax*ax+ay*ay+az*az); if(n<1e-9f) return ident();
        ax/=n; ay/=n; az/=n;
        float a=deg*(float)Math.PI/180f, c=(float)Math.cos(a), s=(float)Math.sin(a), t=1-c;
        float[] m=ident();
        m[0]=t*ax*ax+c;    m[1]=t*ax*ay-s*az; m[2]=t*ax*az+s*ay;
        m[4]=t*ax*ay+s*az; m[5]=t*ay*ay+c;    m[6]=t*ay*az-s*ax;
        m[8]=t*ax*az-s*ay; m[9]=t*ay*az+s*ax; m[10]=t*az*az+c;
        return m;
    }
    // perspectiva: width/height = dimensoes do near-plane (fixed). LH, depth [0,1], w=z_view.
    public void matrixPerspective(int wFx,int hFx,int nearFx,int farFx){
        float w=f2f(wFx), h=f2f(hFx), near=f2f(nearFx), far=f2f(farFx);
        if(w==0||h==0||near==0||far==near){ current=ident(); return; }
        float[] m=new float[16];
        m[0]=2*near/w; m[5]=2*near/h;
        m[10]=far/(far-near); m[11]=-near*far/(far-near);
        m[14]=1; m[15]=0;
        current=m;
    }
    public void matrixOrtho(int wFx,int hFx,int nearFx,int farFx){
        float w=f2f(wFx), h=f2f(hFx), near=f2f(nearFx), far=f2f(farFx);
        float[] m=ident();
        if(w!=0) m[0]=2f/w; if(h!=0) m[5]=2f/h;
        if(far!=near){ m[10]=-2f/(far-near); m[11]=(near+far)/(far-near); }
        current=m;
    }
    public void matrixLookAt(int eyeP,int atP,int upP){
        float ex=f2f(vm.read32(eyeP)),ey=f2f(vm.read32(eyeP+4)),ez=f2f(vm.read32(eyeP+8));
        float ax=f2f(vm.read32(atP)), ay=f2f(vm.read32(atP+4)), az=f2f(vm.read32(atP+8));
        float ux=f2f(vm.read32(upP)), uy=f2f(vm.read32(upP+4)), uz=f2f(vm.read32(upP+8));
        float zx=ax-ex, zy=ay-ey, zz=az-ez; float zn=(float)Math.sqrt(zx*zx+zy*zy+zz*zz);
        if(zn<1e-9f) return; zx/=zn; zy/=zn; zz/=zn;
        // xaxis = up x zaxis
        float xx=uy*zz-uz*zy, xy=uz*zx-ux*zz, xz=ux*zy-uy*zx; float xn=(float)Math.sqrt(xx*xx+xy*xy+xz*xz);
        if(xn<1e-9f) return; xx/=xn; xy/=xn; xz/=xn;
        // yaxis = zaxis x xaxis
        float yx=zy*xz-zz*xy, yy=zz*xx-zx*xz, yz=zx*xy-zy*xx;
        float[] la=ident();
        la[0]=xx; la[1]=xy; la[2]=xz;
        la[4]=yx; la[5]=yy; la[6]=yz;
        la[8]=zx; la[9]=zy; la[10]=zz;
        float[] tr=ident(); tr[3]=-ex; tr[7]=-ey; tr[11]=-ez;
        current=mul(current, mul(la, tr));
    }

    // ---------------- estado ----------------
    public void setViewport(int l,int t,int w,int h){ vpL=l; vpT=t; vpW=w; vpH=h; }
    public void setRenderState(int state,int value){
        switch(state){
            case 4:  zEnable=(value!=0); break;        // ZENABLE
            case 5:  zFunc=value; break;               // ZFUNCTION
            case 6:  cullMode=value; break;            // CULLMODE
            case 7:  shadeMode=value; break;           // SHADEMODE (flat/gouraud)
            case 8:  fogEnable=(value!=0); break;      // FOGENABLE
            case 9:  textureMode=(value!=0); break;    // TEXTUREENABLE
            case 11: texBlendMode=value; break;        // TEXTURE_BLENDMODE (decal/modulate/add)
            case 12: alphaEnable=(value!=0); break;    // ALPHAENABLE
            case 14: wrapMode=value; break;            // WRAPMODE (wrap/clamp/mirror)
            case 15: transp3d=(value!=0); break;       // TRANSPARENTENABLE (indice 0 transparente)
            case 16: specularEnable=(value!=0); break; // SPECULARENABLE
            case 17: lightingEnable=(value!=0); break; // LIGHTINGENABLE
            case 20: colorBlend=value; break;          // COLORBUFFER_BLENDMODE
            case 21: fogColor=value&0xffffff; break;   // FOGCOLOR (RGB888)
            case 22: fogMode=value; break;             // FOGMODE
            case 23: fogStart=f2f(value); break;       // FOGSTART (fixed)
            case 24: fogEnd=f2f(value); break;         // FOGEND (fixed)
            case 25: fogDensity=f2f(value); break;     // FOGDENSITY (fixed)
            default: break;
        }
    }
    public void setFogColor(int rgb){ fogColor=rgb&0xffffff; }
    // teste de profundidade honrando ZFUNCTION (buf preenchido com MAX_VALUE = "vazio")
    boolean zTest(float d,float buf){
        switch(zFunc){
            case 1:   return false;      // NEVER
            case 4:   return d==buf;     // EQUAL
            case 8:   return d<=buf;     // LEQUAL
            case 16:  return d>buf;      // GREATER
            case 32:  return d!=buf;     // NOTEQUAL
            case 64:  return d>=buf;     // GEQUAL
            case 128: return true;       // ALWAYS
            default:  return d<buf;      // LESS (2) + default
        }
    }
    // fator de fog: 1=sem fog, 0=fog total. z = profundidade view-space.
    float fogFactor(float z){
        float f = (fogMode==0) ? (float)Math.exp(-fogDensity*z)     // EXP
                               : (fogEnd-z)/(fogEnd-fogStart);      // LINEAR
        return f<0?0:(f>1?1:f);
    }
    // resolve coordenada de texel honrando WRAPMODE
    int wrapCoord(int c,int size){
        switch(wrapMode){
            case 2:  return c<0?0:(c>=size?size-1:c);                              // CLAMP
            case 4:  { int p=((c%(2*size))+2*size)%(2*size); return p<size?p:(2*size-1-p); } // MIRROR
            default: return ((c%size)+size)%size;                                  // WRAP
        }
    }
    public void clearDepth(){ ensureZ(); java.util.Arrays.fill(zbuf, Float.MAX_VALUE); }
    // limpa o z-buffer SO se ja foi alocado (3D ativo). Chamado a cada vFlipScreen: o z-buffer do mophun
    // e scratch por-frame, mas jogos como Rally Pro Contest NUNCA chamam vSetZBuffer nem vClearScreen
    // (redesenham a cena inteira todo frame). Sem limpar, o z acumulava a menor profundidade ja vista por
    // pixel -> geometria nova falhava o z-test e SUMIA ao mover a camera. So-2D nunca aloca zbuf -> no-op.
    public void clearDepthPerFrame(){ if(zbuf!=null) java.util.Arrays.fill(zbuf, Float.MAX_VALUE); }
    // vSetTexture(data,format,lods,mip): textura "efemera" -> indexado usa a paleta de TELA (activePalette=null).
    public int setTexture(int dataPtr,int format,int lods,int mipCount){
        texturePtr=dataPtr; textureFormat=format; activePalette=null;
        textureW=1<<(lods&0xff); textureH=1<<((lods>>8)&0xff);
        if(textureW<1||textureW>2048||textureH<1||textureH>2048){ textureW=textureH=0; }
        return 1;
    }

    // vCreateTexture(NativeTexture*): cria textura gerenciada com paleta PROPRIA (quando o formato a embute).
    // NativeTexture(32B): format@0, lodX@4, lodY@8, mipCount@12, paletteCount@16, reserved@20, palette@24, data@28.
    public int createTexture(int texPtr){
        if(texPtr==0) return 0;
        Tex t = new Tex();
        t.format = vm.read32(texPtr);
        int lodX = vm.read32(texPtr+4), lodY = vm.read32(texPtr+8);
        t.w = 1<<(lodX&0x1f); t.h = 1<<(lodY&0x1f);
        int palCount = vm.read32(texPtr+16);
        int palPtr   = vm.read32(texPtr+24);
        t.dataPtr    = vm.read32(texPtr+28);
        if(t.w<1||t.w>2048||t.h<1||t.h>2048) return 0;
        t.palette = decodeTexPalette(t.format, palPtr, palCount);   // null se o formato nao embute paleta
        int h = nextTexHandle++;
        managed.put(h, t);
        return h;
    }
    public void setActiveTexture(int handle){
        Tex t = managed.get(handle);
        if(t==null) return;                                        // handle invalido: mantem a textura atual
        texturePtr=t.dataPtr; textureFormat=t.format; textureW=t.w; textureH=t.h; activePalette=t.palette;
    }
    public void deleteTexture(int handle){ managed.remove(handle); }
    public void deleteAllTextures(){ managed.clear(); }

    // decodifica a paleta EMBUTIDA (formatos 16-19). null p/ formatos sem paleta propria (usam paleta de tela).
    // paleta e um array de u32: RGB888 (0x00RRGGBB) p/ 16/17; ARGB8888 p/ 18/19 (bit31 do alpha = transparente no HW,
    // mas o rasterizador 3D e opaco -> guardamos so o RGB, opaco).
    int[] decodeTexPalette(int format, int palPtr, int count){
        boolean self = (format==16||format==17||format==18||format==19);
        if(!self || palPtr==0 || count<=0) return null;
        if(count>256) count=256;
        int[] pal = new int[count];
        for(int i=0;i<count;i++){ int c=vm.read32(palPtr+i*4); pal[i]=0xff000000|(c&0xffffff); }
        return pal;
    }
    // resolve um indice pela paleta ativa da textura (embutida) ou, se nao houver, pela paleta de TELA.
    int palLookup(int i){ int[] p=(activePalette!=null)?activePalette:scr.palette; return (i>=0&&i<p.length)?p[i]:0xff000000; }
    // le um texel de 4bpp (nibble) no indice linear; LSB-first (convencao de textura 3D do mophun/nofun).
    int read4(int idx){ int b=vm.read8(texturePtr+(idx>>1))&0xff; return ((idx&1)==0)?(b&0x0f):(b>>4); }

    // amostra a textura ativa em (u,v) [0..1] com wrap; decodifica pelo formato
    int texSample(float u,float v){
        int tx=wrapCoord((int)Math.floor(u*textureW), textureW);
        int ty=wrapCoord((int)Math.floor(v*textureH), textureH);
        int idx=ty*textureW+tx;                                                    // indice linear do texel
        // formatos indexados: se transp3d (TransparentEnable) e o indice e 0 -> transparente (retorna alpha 0)
        switch(textureFormat){
            case 7:  { int i=vm.read8(texturePtr+idx)&0xff; return (transp3d&&i==0)?0:scr.rgb332[i]; }         // RGB332
            case 6: case 17: case 19: { int i=vm.read8(texturePtr+idx)&0xff; return (transp3d&&i==0)?0:palLookup(i); } // Palette256*
            case 5: case 16: case 18: { int i=read4(idx); return (transp3d&&i==0)?0:palLookup(i); }           // Palette16*
            case 8:  return rgb565(vm.read16(texturePtr+idx*2)&0xffff);             // RGB565
            case 9:  return Screen.rgb555ToArgb(vm.read16(texturePtr+idx*2)&0xffff);// RGB555
            case 10: { int b=texturePtr+idx*3;                                       // RGB888 (24bpp direto)
                       return 0xff000000|((vm.read8(b)&0xff)<<16)|((vm.read8(b+1)&0xff)<<8)|(vm.read8(b+2)&0xff); }
            case 11: return 0xff000000|(vm.read32(texturePtr+idx*4)&0xffffff);      // ARGB8888 (RGB direto, opaco)
            case 12: { int w=vm.read16(texturePtr+idx*2)&0xffff;                    // RGB444 (4/4/4)
                       int r=(w>>8)&0xf,g=(w>>4)&0xf,bl=w&0xf; return 0xff000000|((r*17)<<16)|((g*17)<<8)|(bl*17); }
            case 13: { int w=vm.read16(texturePtr+idx*2)&0xffff;                    // ARGB4444 (bit alto do alpha = transparente)
                       if((w&0x8000)==0) return 0; int r=(w>>8)&0xf,g=(w>>4)&0xf,bl=w&0xf; return 0xff000000|((r*17)<<16)|((g*17)<<8)|(bl*17); }
            case 14: { int w=vm.read16(texturePtr+idx*2)&0xffff;                    // ARGB1555 (bit alto = transparente)
                       if((w&0x8000)==0) return 0; int r=(w>>10)&0x1f,g=(w>>5)&0x1f,bl=w&0x1f;
                       return 0xff000000|(Math.round(r*255f/31)<<16)|(Math.round(g*255f/31)<<8)|Math.round(bl*255f/31); }
            default: { int i=vm.read8(texturePtr+idx)&0xff; return (transp3d&&i==0)?0:palLookup(i); }
        }
    }
    static int rgb565(int v){ int r=(v>>11)&31,g=(v>>5)&63,b=v&31;
        return 0xff000000|(Math.round(r*255f/31)<<16)|(Math.round(g*255f/63)<<8)|Math.round(b*255f/31); }
    void ensureZ(){
        if(zbuf==null || zW!=scr.pw || zH!=scr.ph){ zW=scr.pw; zH=scr.ph; zbuf=new float[zW*zH]; java.util.Arrays.fill(zbuf,Float.MAX_VALUE); }
    }

    // ---------------- iluminacao T&L (VMGP3D) ----------------
    public void setAmbientLight(int rgb){ ambientScene=rgb&0xffffff; }
    public void setCameraPos(int p){ camX=vx(p); camY=vy(p); camZ=vz(p); }
    public void resetLights(){ for(int i=0;i<MAXL;i++) lights[i]=null; }
    // LIGHT(40B): pos@0, dir@12, type@24(u16), diff r,g,b@26, spec sr,sg,sb@29, range@32(fixed=1/range), exponent@36(8:8), cutoff@38
    public void setLight(int index,int p){
        if(index<0||index>=MAXL) return;
        float[] L=new float[16];
        L[0]=vm.read16(p+24)&0xffff;                                     // type
        L[1]=vx(p);        L[2]=vy(p);        L[3]=vz(p);                 // pos
        L[4]=vx(p+12);     L[5]=vy(p+12);     L[6]=vz(p+12);             // dir
        L[7]=vm.read8(p+26); L[8]=vm.read8(p+27); L[9]=vm.read8(p+28);    // diffuse r,g,b
        L[10]=vm.read8(p+29);L[11]=vm.read8(p+30);L[12]=vm.read8(p+31);   // specular r,g,b
        L[13]=f2f(vm.read32(p+32));                                       // range (guardado como 1/range)
        L[14]=(vm.read16(p+36)&0xffff)/256f;                             // exponent (8:8)
        L[15]=vm.read16(p+38)&0xffff;                                    // cutoff
        lights[index]=L;
    }
    // MATERIAL = DSCOLOR: diff{b,g,r,a}@0, spec{b,g,r,f}@4
    public void setMaterial(int p){
        matDiffB=vm.read8(p);   matDiffG=vm.read8(p+1); matDiffR=vm.read8(p+2);
        matSpecB=vm.read8(p+4); matSpecG=vm.read8(p+5); matSpecR=vm.read8(p+6);
        matEmR=matEmG=matEmB=0; matAmbR=matAmbG=matAmbB=255; matShininess=4f;
    }
    // MATERIAL2: ambient,diff,spec,emission (DCOLOR@0,4,8,12), shininess@16(fixed)
    public void setMaterial2(int p){
        matAmbB=vm.read8(p);    matAmbG=vm.read8(p+1);  matAmbR=vm.read8(p+2);
        matDiffB=vm.read8(p+4); matDiffG=vm.read8(p+5); matDiffR=vm.read8(p+6);
        matSpecB=vm.read8(p+8); matSpecG=vm.read8(p+9); matSpecR=vm.read8(p+10);
        matEmB=vm.read8(p+12);  matEmG=vm.read8(p+13);  matEmR=vm.read8(p+14);
        matShininess=f2f(vm.read32(p+16));
    }
    // Aprox. fixed-function: cor = emission + diff*ambientCena + sum_luzes(diff*lightDiff*max(0,N.L)*atten).
    // baseArgb = cor difusa por-vertice (substitui a reflectancia difusa do material, conforme VERTEXLIST).
    int shadeVertex(int vertPtr,int normalPtr,int baseArgb){
        int alpha=baseArgb>>>24;
        float dr=(baseArgb>>16)&0xff, dg=(baseArgb>>8)&0xff, db=baseArgb&0xff;   // reflectancia difusa 0..255
        // normal p/ view: rotaciona pela 3x3 da matriz atual (modelview) e normaliza
        float nx=vx(normalPtr), ny=vy(normalPtr), nz=vz(normalPtr);
        float wnx=current[0]*nx+current[1]*ny+current[2]*nz;
        float wny=current[4]*nx+current[5]*ny+current[6]*nz;
        float wnz=current[8]*nx+current[9]*ny+current[10]*nz;
        float nl=(float)Math.sqrt(wnx*wnx+wny*wny+wnz*wnz); if(nl>1e-6f){wnx/=nl;wny/=nl;wnz/=nl;}
        // posicao view do vertice (p/ luzes point/spot)
        float ox=f2f(vm.read32(vertPtr)),oy=f2f(vm.read32(vertPtr+4)),oz=f2f(vm.read32(vertPtr+8));
        float wx=current[0]*ox+current[1]*oy+current[2]*oz+current[3];
        float wy=current[4]*ox+current[5]*oy+current[6]*oz+current[7];
        float wz=current[8]*ox+current[9]*oy+current[10]*oz+current[11];
        float sar=((ambientScene>>16)&0xff)/255f, sag=((ambientScene>>8)&0xff)/255f, sab=(ambientScene&0xff)/255f;
        float rr=matEmR + dr*sar, gg=matEmG + dg*sag, bb=matEmB + db*sab;
        for(int li=0; li<MAXL; li++){ float[] L=lights[li]; if(L==null) continue;
            int type=(int)L[0]; float lx,ly,lz, atten=1f;
            if(type==2){                 // DIRECTIONAL: vetor p/ a luz = -dir (dir = para onde a luz viaja)
                lx=-L[4]; ly=-L[5]; lz=-L[6];
                float ll=(float)Math.sqrt(lx*lx+ly*ly+lz*lz); if(ll>1e-6f){lx/=ll;ly/=ll;lz/=ll;}
            } else {                     // POINT/SPOT: vetor do vertice ate a luz + atenuacao por distancia
                lx=L[1]-wx; ly=L[2]-wy; lz=L[3]-wz;
                float dist=(float)Math.sqrt(lx*lx+ly*ly+lz*lz); if(dist>1e-6f){lx/=dist;ly/=dist;lz/=dist;}
                if(L[13]>0f){ atten=1f-dist*L[13]; if(atten<0f)atten=0f; }
            }
            float ndotl=wnx*lx+wny*ly+wnz*lz; if(ndotl<0f) ndotl=0f;
            float k=ndotl*atten;
            rr += dr*(L[7]/255f)*k; gg += dg*(L[8]/255f)*k; bb += db*(L[9]/255f)*k;
        }
        return (alpha<<24)|(clamp((int)rr)<<16)|(clamp((int)gg)<<8)|clamp((int)bb);
    }
    // vLightPoint(LIGHTARGS*): pre-ilumina uma lista de vertices, escrevendo dstdiff (DCOLOR). LIGHTARGS: v@0,n@4,color@8,dstdiff@12,dstspec@16,count@20
    public void lightPoint(int argsPtr){
        int vP=vm.read32(argsPtr), nP=vm.read32(argsPtr+4), colP=vm.read32(argsPtr+8);
        int ddP=vm.read32(argsPtr+12), dsP=vm.read32(argsPtr+16);
        int count=vm.read16(argsPtr+20)&0xffff;
        if(vP==0||nP==0) return;
        for(int i=0;i<count;i++){
            int base = colP!=0 ? diffuseArgb(colP+i*4) : ((255<<24)|(matDiffR<<16)|(matDiffG<<8)|matDiffB);
            int lit = shadeVertex(vP+i*12, nP+i*12, base);
            if(ddP!=0){ vm.write8(ddP+i*4, lit&0xff); vm.write8(ddP+i*4+1,(lit>>8)&0xff);
                        vm.write8(ddP+i*4+2,(lit>>16)&0xff); vm.write8(ddP+i*4+3,(lit>>>24)); }
            if(dsP!=0) vm.write32(dsP+i*4, 0);   // specular nao calculado (aprox.)
        }
    }

    // ---------------- render ----------------
    // VERTEXLIST: v@0, uv@4, diff@8, spec@12, normal@16, count@20
    public void renderPrimitive(int meshPtr,int topology){
        int vertsP=vm.read32(meshPtr), uvsP=vm.read32(meshPtr+4), diffP=vm.read32(meshPtr+8), normP=vm.read32(meshPtr+16);
        int count=vm.read16(meshPtr+20)&0xffff;
        if(vertsP==0||count<3) return; drawCalls++;
        renderIndexed(vertsP, uvsP, diffP, normP, count, 0, 0, topology);
    }
    public void renderPrimitiveIndexed(int idxPtr,int idxCount,int meshPtr,int topology){
        int vertsP=vm.read32(meshPtr), uvsP=vm.read32(meshPtr+4), diffP=vm.read32(meshPtr+8), normP=vm.read32(meshPtr+16);
        int count=vm.read16(meshPtr+20)&0xffff;
        if(vertsP==0||idxPtr==0||idxCount<3) return; drawCalls++;
        renderIndexed(vertsP, uvsP, diffP, normP, count, idxPtr, idxCount, topology);
    }
    // vDrawPolygon(v1,v2,v3): NativeVertexGST (position Vector4@0, diffuse@16, spec@20, uv@24)
    public void drawPolygon(int p1,int p2,int p3){
        drawCalls++; ensureZ();          // garante z-buffer (jogos que usam vDrawPolygon sem vRenderPrimitive antes)
        float[] mvp=mul(projection,current);
        float[][] cl=new float[3][4]; int[] col=new int[3]; float[][] uv=new float[3][2];
        int[] ps={p1,p2,p3};
        boolean tex = texturePtr!=0 && textureW>0;
        for(int i=0;i<3;i++){ int p=ps[i];
            xform(mvp, f2f(vm.read32(p)), f2f(vm.read32(p+4)), f2f(vm.read32(p+8)), 1f, cl[i]);
            col[i]=diffuseArgb(p+16);
            if(tex){ uv[i][0]=uv2f((short)vm.read16(p+24)); uv[i][1]=uv2f((short)vm.read16(p+26)); }
        }
        clipRaster(cl[0],cl[1],cl[2],col[0],col[1],col[2],uv[0],uv[1],uv[2],tex);
    }

    void renderIndexed(int vertsP,int uvsP,int diffP,int normP,int count,int idxPtr,int idxCount,int topology){
        ensureZ();
        float[] mvp=mul(projection,current);
        boolean tex = uvsP!=0 && texturePtr!=0 && textureW>0;
        boolean lit = lightingEnable && normP!=0;   // T&L so quando o jogo habilita LIGHTINGENABLE e fornece normais
        float[][] clip=new float[count][4]; int[] col=new int[count]; float[][] uv=new float[count][2];
        for(int i=0;i<count;i++){
            int vp=vertsP+i*12;
            xform(mvp, f2f(vm.read32(vp)), f2f(vm.read32(vp+4)), f2f(vm.read32(vp+8)), 1f, clip[i]);
            int base = diffP!=0 ? diffuseArgb(diffP+i*4) : 0xffffffff;
            col[i]= lit ? shadeVertex(vp, normP+i*12, base) : base;
            if(tex){ uv[i][0]=uv2f((short)vm.read16(uvsP+i*4)); uv[i][1]=uv2f((short)vm.read16(uvsP+i*4+2)); }
        }
        // vRenderPrimitiveIndexed: o index list pode conter VARIAS strips/fans separadas por -1 (0xFFFF),
        // conforme o SDK ("Multiple strips and fans are supported using indexnr -1 as delimiter"). Cada
        // sub-run REINICIA a paridade da strip e o pivot do fan; sem isso os triangulos apos o -1 saiam
        // com winding invertido e o backface culling os apagava -> faces do carro/mapa "sumindo" ao mover.
        int n = idxPtr!=0 ? idxCount : count;
        int runStart=0;
        while(runStart<n){
            int runEnd=runStart;
            while(runEnd<n && idxAt(idxPtr,runEnd)>=0) runEnd++;   // -1 (ou fim da lista) termina o run
            int len=runEnd-runStart;
            if(topology==0x10){          // TriangleStrip: paridade LOCAL ao run
                for(int k=0;k+2<len;k++){
                    int a=idxAt(idxPtr,runStart+k), b=idxAt(idxPtr,runStart+k+1), c=idxAt(idxPtr,runStart+k+2);
                    if((k&1)!=0){ int tmp=b; b=c; c=tmp; }
                    emitTri(a,b,c,count,clip,col,uv,tex);
                }
            } else if(topology==0x20){   // TriangleFan: pivot = 1o vertice DO run
                int a=idxAt(idxPtr,runStart);
                for(int k=1;k+1<len;k++) emitTri(a, idxAt(idxPtr,runStart+k), idxAt(idxPtr,runStart+k+1), count,clip,col,uv,tex);
            } else {                     // TriangleList
                for(int k=0;k+2<len;k+=3) emitTri(idxAt(idxPtr,runStart+k), idxAt(idxPtr,runStart+k+1), idxAt(idxPtr,runStart+k+2), count,clip,col,uv,tex);
            }
            runStart = runEnd+1;         // pula o delimitador -1
        }
    }
    // emite 1 triangulo (com bounds-check dos indices contra a contagem de vertices)
    void emitTri(int a,int b,int c,int count,float[][] clip,int[] col,float[][] uv,boolean tex){
        if(a<0||b<0||c<0||a>=count||b>=count||c>=count) return;
        clipRaster(clip[a],clip[b],clip[c],col[a],col[b],col[c],uv[a],uv[b],uv[c],tex);
    }
    int idxAt(int idxPtr,int i){ return idxPtr==0 ? i : (short)vm.read16(idxPtr+i*2); }
    // DCOLOR = {b,g,r,a}; preserva o alpha difuso (usado pelo color-buffer blend quando ALPHAENABLE)
    int diffuseArgb(int p){ int b=vm.read8(p), g=vm.read8(p+1), r=vm.read8(p+2), a=vm.read8(p+3); return (a<<24)|(r<<16)|(g<<8)|b; }

    // Recorta o triangulo contra o NEAR-plane (z_clip>=0) e rasteriza os triangulos resultantes.
    // Sem isto, triangulos que cruzam o near eram DESCARTADOS (geometria "some" ao chegar perto) e
    // vertices com w minusculo projetavam p/ coords extremas (o "triangulo preto"). Sutherland-Hodgman:
    // interpola posicao(clip4)+cor+uv no cruzamento d=z_clip=0; saida 0/3/4 vertices -> fan de triangulos.
    static float[] lerp4(float[] a,float[] b,float t){ return new float[]{a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t,a[3]+(b[3]-a[3])*t}; }
    static int lerpCol(int a,int b,float t){ int ar=(a>>16)&0xff,ag=(a>>8)&0xff,ab=a&0xff;
        int r=(int)(ar+(((b>>16)&0xff)-ar)*t), g=(int)(ag+(((b>>8)&0xff)-ag)*t), bl=(int)(ab+((b&0xff)-ab)*t);
        return 0xff000000|(clamp(r)<<16)|(clamp(g)<<8)|clamp(bl); }
    void clipRaster(float[] c0,float[] c1,float[] c2,int col0,int col1,int col2,float[] uv0,float[] uv1,float[] uv2,boolean tex){
        float[][] cs={c0,c1,c2}; int[] cols={col0,col1,col2}; float[][] uvs={uv0,uv1,uv2};
        // se todos ja estao em frente do near, rasteriza direto (caso comum, sem alocar)
        if(c0[2]>=0 && c1[2]>=0 && c2[2]>=0){ rasterTri(c0,c1,c2,col0,col1,col2,uv0,uv1,uv2,tex); return; }
        if(c0[2]<0 && c1[2]<0 && c2[2]<0) return;                 // totalmente atras do near -> nada
        float[][] oc=new float[4][]; int[] ocol=new int[4]; float[][] ouv=new float[4][]; int n=0;
        for(int i=0;i<3;i++){ int j=(i+1)%3; float di=cs[i][2], dj=cs[j][2]; boolean ini=di>=0, inj=dj>=0;
            if(ini){ oc[n]=cs[i]; ocol[n]=cols[i]; ouv[n]=uvs[i]; n++; }
            if(ini!=inj){ float t=di/(di-dj);
                oc[n]=lerp4(cs[i],cs[j],t); ocol[n]=lerpCol(cols[i],cols[j],t);
                ouv[n]=new float[]{uvs[i][0]+(uvs[j][0]-uvs[i][0])*t, uvs[i][1]+(uvs[j][1]-uvs[i][1])*t}; n++; }
        }
        for(int i=1;i+1<n;i++) rasterTri(oc[0],oc[i],oc[i+1], ocol[0],ocol[i],ocol[i+1], ouv[0],ouv[i],ouv[i+1], tex);
    }

    // rasteriza triangulo (clip-space) com z-buffer + textura (perspective-correct) ou gouraud
    void rasterTri(float[] c0,float[] c1,float[] c2,int col0,int col1,int col2,float[] uv0,float[] uv1,float[] uv2,boolean tex){
        trisDrawn++;
        if(c0[3]<=1e-4f||c1[3]<=1e-4f||c2[3]<=1e-4f) return;   // near-clip simples
        float iw0=1f/c0[3], iw1=1f/c1[3], iw2=1f/c2[3];
        float x0=vpL+(c0[0]*iw0*0.5f+0.5f)*vpW, y0=vpT+(0.5f-c0[1]*iw0*0.5f)*vpH, z0=c0[2]*iw0;
        float x1=vpL+(c1[0]*iw1*0.5f+0.5f)*vpW, y1=vpT+(0.5f-c1[1]*iw1*0.5f)*vpH, z1=c1[2]*iw1;
        float x2=vpL+(c2[0]*iw2*0.5f+0.5f)*vpW, y2=vpT+(0.5f-c2[1]*iw2*0.5f)*vpH, z2=c2[2]*iw2;
        final int rs=scr.rs;                         // supersampling: rasteriza em coords FISICAS (rs x)
        if(rs>1){ x0*=rs; y0*=rs; x1*=rs; y1*=rs; x2*=rs; y2*=rs; }
        float area=(x1-x0)*(y2-y0)-(x2-x0)*(y1-y0);
        if(area==0) return;
        // backface culling (honra cullMode do jogo: 1=CW, 2=CCW). Descarta faces de tras -> tira
        // "triangulos pretos" (backfaces do terreno/objetos que o jogo queria esconder). None(0)=sem cull.
        if(!NOCULL){ if(cullMode==1 && area>0) return; if(cullMode==2 && area<0) return; }
        int minX=(int)Math.floor(Math.min(x0,Math.min(x1,x2))), maxX=(int)Math.ceil(Math.max(x0,Math.max(x1,x2)));
        int minY=(int)Math.floor(Math.min(y0,Math.min(y1,y2))), maxY=(int)Math.ceil(Math.max(y0,Math.max(y1,y2)));
        minX=Math.max(minX,Math.max(vpL,scr.clipX0)*rs); maxX=Math.min(maxX,Math.min(vpL+vpW,scr.clipX1)*rs);
        minY=Math.max(minY,Math.max(vpT,scr.clipY0)*rs); maxY=Math.min(maxY,Math.min(vpT+vpH,scr.clipY1)*rs);
        // SHADEMODE FLAT: usa o vertice provocante (v0) p/ todo o triangulo (spec: "first vertex is used")
        if(shadeMode==1){ col1=col0; col2=col0; }
        int r0=(col0>>16)&0xff,g0=(col0>>8)&0xff,b0=col0&0xff,a0=(col0>>>24);
        int r1=(col1>>16)&0xff,g1=(col1>>8)&0xff,b1=col1&0xff,a1=(col1>>>24);
        int r2=(col2>>16)&0xff,g2=(col2>>8)&0xff,b2=col2&0xff,a2=(col2>>>24);
        boolean blend = alphaEnable;   // ALPHAENABLE(12) liga o blend; state 20 (COLORBUFFER_BLENDMODE) so escolhe o modo
        int fr=(fogColor>>16)&0xff, fg=(fogColor>>8)&0xff, fb=fogColor&0xff;
        float inv=1f/area;
        for(int py=minY;py<maxY;py++){ float fy=py+0.5f;
            for(int px=minX;px<maxX;px++){ float fx=px+0.5f;
                float w0=((x1-fx)*(y2-fy)-(x2-fx)*(y1-fy))*inv;
                float w1=((x2-fx)*(y0-fy)-(x0-fx)*(y2-fy))*inv;
                float w2=1-w0-w1;
                if(w0<0||w1<0||w2<0) continue;
                float depth=w0*z0+w1*z1+w2*z2;
                int zi=py*zW+px;
                // primitivas transparentes (blend, ex.: a SOMBRA do carro) sao decals COPLANARES com o chao:
                // com o teste estrito LESS elas z-fightam e somem. Usa LEQUAL (<=) p/ passar sobre o coplanar.
                if(zEnable){ boolean pass = blend ? (depth <= zbuf[zi]+1e-5f) : zTest(depth,zbuf[zi]); if(!pass) continue; }
                float iws = (tex||fogEnable) ? (w0*iw0+w1*iw1+w2*iw2) : 0f;
                int rr,gg,bb;
                if(tex){   // interpolacao perspective-correct de UV
                    float u=(w0*uv0[0]*iw0+w1*uv1[0]*iw1+w2*uv2[0]*iw2)/iws;
                    float v=(w0*uv0[1]*iw0+w1*uv1[1]*iw1+w2*uv2[1]*iw2)/iws;
                    int t=texSample(u,v);
                    if((t>>>24)==0) continue;      // texel transparente -> nao pinta NEM escreve z-buffer
                    int tr=(t>>16)&0xff, tg=(t>>8)&0xff, tb=t&0xff;
                    switch(texBlendMode){          // combina texel x cor difusa do fragmento (state 11)
                        case 2: { int vr=(int)(w0*r0+w1*r1+w2*r2),vg=(int)(w0*g0+w1*g1+w2*g2),vb=(int)(w0*b0+w1*b1+w2*b2);
                                  rr=tr*vr/255; gg=tg*vg/255; bb=tb*vb/255; break; }       // MODULATE
                        case 4: { int vr=(int)(w0*r0+w1*r1+w2*r2),vg=(int)(w0*g0+w1*g1+w2*g2),vb=(int)(w0*b0+w1*b1+w2*b2);
                                  rr=tr+vr; gg=tg+vg; bb=tb+vb; break; }                    // ADD
                        default: rr=tr; gg=tg; bb=tb; break;                                // DECAL/REPLACE
                    }
                } else {
                    rr=(int)(w0*r0+w1*r1+w2*r2); gg=(int)(w0*g0+w1*g1+w2*g2); bb=(int)(w0*b0+w1*b1+w2*b2);
                }
                if(fogEnable){                     // mistura com a cor do fog pela profundidade (state 8)
                    float f=fogFactor(iws>1e-9f?1f/iws:fogEnd);
                    rr=(int)(rr*f+fr*(1-f)); gg=(int)(gg*f+fg*(1-f)); bb=(int)(bb*f+fb*(1-f));
                }
                rr=clamp(rr); gg=clamp(gg); bb=clamp(bb);
                if(blend){                         // color-buffer blend com o pixel ja no framebuffer (state 20)
                    int sa=(int)(w0*a0+w1*a1+w2*a2); if(sa<0)sa=0; else if(sa>255)sa=255;
                    int dst=scr.fb[py*scr.pw+px], dr=(dst>>16)&0xff, dg=(dst>>8)&0xff, db=dst&0xff;
                    // sem state 20 explicito, o default e ALPHA (SrcAlpha/InvSrcAlpha) = alpha do vertice/textura.
                    // Rally desenha a SOMBRA do carro como poligono preto com alpha=0x80 (50%) e SO liga ALPHAENABLE;
                    // antes (exigindo colorBlend!=0) saia preto opaco -> agora mistura 50% = sombra suave.
                    switch(colorBlend!=0?colorBlend:2){
                        case 1: rr=clamp(dr+rr*sa/255); gg=clamp(dg+gg*sa/255); bb=clamp(db+bb*sa/255); break; // ALPHAADD
                        case 2: rr=(rr*sa+dr*(255-sa))/255; gg=(gg*sa+dg*(255-sa))/255; bb=(bb*sa+db*(255-sa))/255; break; // ALPHA
                        case 3: rr=rr*dr/255; gg=gg*dg/255; bb=bb*db/255; break;                              // MODULATE
                        default: break;                                                                       // REPLACE
                    }
                }
                if(zEnable && !blend) zbuf[zi]=depth;   // transparente (sombra) nao escreve z -> nao oclui o carro
                pixelsDrawn++;
                scr.fb[py*scr.pw+px]=0xff000000|(rr<<16)|(gg<<8)|bb;
            }
        }
    }
    static int clamp(int v){ return v<0?0:v>255?255:v; }
}
