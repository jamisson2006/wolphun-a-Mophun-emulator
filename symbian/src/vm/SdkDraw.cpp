/* SdkDraw.cpp - parte do Sdk: desenho 2D, sprites e tilemap.
 * Port de src/wolphun/vm/Sdk.java (secoes graficas). Ver Sdk.h / Sdk.cpp.
 * Os comentarios do original (o PORQUE de cada decisao, achado testando jogos
 * reais) foram preservados: sao o valor acumulado do emulador.
 */
#include "Sdk.h"
#include "Pip2.h"
#include "Screen.h"

static inline int iround2(int v,int num,int den){ return (v*num*2 + den) / (den*2); }

static u32 rgb565ToArgb2(int v){
    int r=(v>>11)&31, g=(v>>5)&63, b=v&31;
    return 0xff000000u | ((u32)iround2(r,255,31)<<16) | ((u32)iround2(g,255,63)<<8) | (u32)iround2(b,255,31);
}

/* bits por pixel de cada TextureFormat (nofun TextureUtil.GetPixelSizeInBits) */
static int bppOf(int fmt){
    switch (fmt){
        case 0: case 3: return 1;                 /* Monochrome, Palette2 */
        case 1: case 4: return 2;                 /* Gray4, Palette4 */
        case 2: case 5: return 4;                 /* Gray16, Palette16 */
        case 6: case 7: return 8;                 /* Palette256, RGB332 */
        case 8: case 13: case 14: return 16;      /* RGB565, ARGB4444, ARGB1555 */
        case 9: return 16;                        /* RGB555 */
        default: return 8;
    }
}

/* ================= primitivas ================= */

/* vFillRect(x0,y0,x1,y1) - cantos; cor = foreColor.
   NORMALIZA cantos invertidos: jogos pseudo-3D (XxX/V-Rally) desenham as BANDAS do
   terreno com cantos em ordem DESCENDENTE. O nofun rejeita e as faixas somem; o
   emulador OFICIAL normaliza e desenha.
   E INCLUSIVO: x1/y1 sao a borda DIREITA/INFERIOR (SDK: "x1=Right position"). Uma
   coluna de 1 pixel (x0==x1) desenha 1 pixel - ex.: a barra de vida do Honey Cave 1
   (vFillRect(x,74,x,77) por coluna), que com range exclusivo ficava VAZIA.
   RESPEITA o clip window em qualquer versao de API: o vmgp.h lista vFillRect entre as
   "functions affected by the clip window" e contrasta explicitamente com vClearScreen,
   que nao e afetado. (Abrir excecao p/ API <1.50 apagava o HUD do Graveyard Adventure.) */
void Sdk::fillRect(int x0,int y0,int x1,int y1){
    if (x0>x1){ int t=x0; x0=x1; x1=t; }
    if (y0>y1){ int t=y0; y0=y1; y1=t; }
    u32 c = getColor(foreColor);
    for (int yy=y0; yy<=y1; yy++)
        for (int xx=x0; xx<=x1; xx++)
            px(xx,yy,c);
}

/* vDrawLine(x0,y0,x1,y1) - Bresenham com foreColor */
void Sdk::drawLine(int x0,int y0,int x1,int y1){
    u32 c = getColor(foreColor);
    int dx = (x1>x0) ? (x1-x0) : (x0-x1);
    int dy = (y1>y0) ? (y0-y1) : (y1-y0);      /* = -abs(y1-y0) */
    int sx = (x0<x1) ? 1 : -1;
    int sy = (y0<y1) ? 1 : -1;
    int err = dx+dy;
    for (int guard=0; guard<4096; guard++){
        px(x0,y0,c);
        if (x0==x1 && y0==y1) break;
        int e2 = 2*err;
        if (e2>=dy){ err+=dy; x0+=sx; }
        if (e2<=dx){ err+=dx; y0+=sy; }
    }
}

/* vDrawFlatPolygon(triPtr): Triangle{x0,y0,x1,y1,x2,y2}=s16 -> triangulo 2D preenchido */
void Sdk::fillFlatPoly(int p){
    int x0=(i16)vm->read16(p),    y0=(i16)vm->read16(p+2);
    int x1=(i16)vm->read16(p+4),  y1=(i16)vm->read16(p+6);
    int x2=(i16)vm->read16(p+8),  y2=(i16)vm->read16(p+10);
    u32 c = getColor(foreColor);
    int mnX = x0<x1?(x0<x2?x0:x2):(x1<x2?x1:x2);
    int mxX = x0>x1?(x0>x2?x0:x2):(x1>x2?x1:x2);
    int mnY = y0<y1?(y0<y2?y0:y2):(y1<y2?y1:y2);
    int mxY = y0>y1?(y0>y2?y0:y2):(y1>y2?y1:y2);
    int minX = mnX > scr->clipX0 ? mnX : scr->clipX0;
    int maxX = (mxX+1) < scr->clipX1 ? (mxX+1) : scr->clipX1;
    int minY = mnY > scr->clipY0 ? mnY : scr->clipY0;
    int maxY = (mxY+1) < scr->clipY1 ? (mxY+1) : scr->clipY1;
    int area = (x1-x0)*(y2-y0) - (x2-x0)*(y1-y0);
    if (area==0) return;
    for (int py=minY; py<maxY; py++){
        for (int pxx=minX; pxx<maxX; pxx++){
            int w0=(x1-pxx)*(y2-py)-(x2-pxx)*(y1-py);
            int w1=(x2-pxx)*(y0-py)-(x0-pxx)*(y2-py);
            int w2=area-w0-w1;
            bool in = (area>0) ? (w0>=0&&w1>=0&&w2>=0) : (w0<=0&&w1<=0&&w2<=0);
            if (in) scr->put(pxx,py,c);
        }
    }
}

/* vSetPalette(colorsPtr, startIndex, count) - array de u16 RGB555 */
void Sdk::setPalette(int ptr,int start,int count){
    if (count>256) count=256;
    for (int i=0;i<count;i++)
        scr->setPaletteRgb555((start+i)&0xff, vm->read16(ptr+i*2));
}

/* vFindRGBIndex(rgb555): indice da paleta de tela mais proximo (nofun Graphics.cs) */
int Sdk::findRGBIndex(int rgb555){
    int tr=iround2((rgb555>>10)&31,255,31), tg=iround2((rgb555>>5)&31,255,31), tb=iround2(rgb555&31,255,31);
    int best=0; i64 bestD=((i64)1)<<62;
    for (int i=0;i<256;i++){
        u32 c=scr->palette[i];
        int r=(int)((c>>16)&0xff), g=(int)((c>>8)&0xff), b=(int)(c&0xff);
        i64 d=(i64)(r-tr)*(r-tr)+(i64)(g-tg)*(g-tg)+(i64)(b-tb)*(b-tb);
        if (d<bestD){ bestD=d; best=i; }
    }
    return best;
}

/* ================= sprites ================= */

/* NativeSprite (10B): palOff,format,centerX(s16),centerY(s16),w(u16),h(u16); pixels @+10. */
void Sdk::drawObject(int x,int y,int sp){
    if (sp==0) return;
    int palOff = vm->read8(sp), format = vm->read8(sp+1);
    int cx=(i16)vm->read16(sp+2), cy=(i16)vm->read16(sp+4);
    /* SPRITE.centerx/centery sao o ponto de ancoragem - sempre da ordem do tamanho do
       sprite. Valores absurdos (|c| > 1024) nao existem em sprite legitimo e jogam o
       desenho todo p/ fora da tela: o ceu da fase 2 do Joe's Treasure Quest vinha com
       centery=-12416 e as 22 faixas desenhavam 0 pixels. PROTECAO, nao causa raiz. */
    if (cx>1024||cx<-1024) cx=0;
    if (cy>1024||cy<-1024) cy=0;
    int w = vm->read16(sp+6), h = vm->read16(sp+8);
    if (w==0||h==0||w>2048||h>2048) return;
    int pix = sp+10, bpp = bppOf(format), mask = (1<<bpp)-1;
    int ox = x-cx, oy = y-cy;
    bool flipX = (transferMode & 2)!=0;   /* TransferMode.FlipX=2 */
    bool flipY = (transferMode & 4)!=0;   /* TransferMode.FlipY=4 */
    /* transparencia (indice 0 = chave) no modo Transparent (bit0); Block(0)=opaco, p/ os
       fundos aparecerem. O motor de SLOTS sempre trata 0 como chave: o vmgp.h so
       documenta o transfer mode no vDrawObject, e os slots desenham objetos de jogo
       sobre um fundo (o HoneyCave 2 deixa o modo em 0 e ainda assim sai recortado). */
    bool transp = ((transferMode & 1)!=0) || spriteSlotDraw;
    for (int j=0;j<h;j++){
        int dy = oy+j; if (dy<scr->clipY0 || dy>=scr->clipY1) continue;
        int sj = flipY ? (h-1-j) : j;
        for (int i=0;i<w;i++){
            int dx = ox+i; if (dx<scr->clipX0 || dx>=scr->clipX1) continue;
            int si = flipX ? (w-1-i) : i;
            int pi = sj*w+si; u32 argb;
            if (bpp<=8){
                int bitPos = pi*bpp, byteIdx = pix + (bitPos>>3);
                /* sub-byte (Palette2/4/16 = 1/2/4bpp) e LSB-first no mophun: o 1o pixel
                   fica no nibble/bit BAIXO (nofun DataConvertor.PaletteToARGB8888).
                   MSB-first embaralhava texto/HUD de jogos 4bpp. */
                int idx = (vm->read8(byteIdx) >> (bitPos&7)) & mask;
                if (transp && idx==0) continue;
                if (format==1||format==2||format==0){            /* grayscale/mono */
                    int g = iround2(idx,255,mask);
                    argb = 0xff000000u | ((u32)g<<16) | ((u32)g<<8) | (u32)g;
                } else if (format==7){
                    argb = scr->rgb332[idx & 0xff];               /* RGB332 = cor DIRETA */
                } else {
                    /* Palette256 (fmt6): o indice JA e 0-255 (paleta completa) -> palOff
                       NAO se aplica (senao desloca +1: V-Rally 2 seta palette[197] como
                       cor do carro e lia-se palette[198] -> carros psicodelicos).
                       Palette16 (fmt5): palOff = banco de 16. */
                    int po = (format==6) ? 0 : palOff;
                    argb = scr->palette[(idx+po) & 0xff];
                }
            } else {                                             /* 16bpp direto */
                int v = vm->read16(pix + pi*2) & 0xffff;
                if (transp && v==0) continue;
                argb = (format==8) ? rgb565ToArgb2(v) : Screen::rgb555ToArgb(v);
            }
            scr->put(dx,dy,argb);
        }
    }
}

void Sdk::drawSpriteSlots(){
    if (!slotSprite) return;
    bool prev = spriteSlotDraw; spriteSlotDraw = true;
    for (int i=0;i<spriteCount;i++)
        if (slotSprite[i]!=0) drawObject(slotX[i], slotY[i], slotSprite[i]);
    spriteSlotDraw = prev;
}

/* vDrawTile(data, format, x, y): UM tile 8x8. format: bits0-2=formato de pixel,
   bit3=transparente, bits8-15=offset de paleta. Usado como MOTOR DE CHAO/PISTA
   (Rally Pro Contest desenha o terreno 3D como grade de tiles: 1144 tiles/frame). */
void Sdk::drawTile(int dataPtr,int format,int x,int y){
    int pixFmt = format & 0x7;
    bool transp = (format & 0x8)!=0;
    int palOff = (format>>8) & 0xff;
    int bpp = bppOf(pixFmt), mask=(1<<bpp)-1;
    for (int j=0;j<8;j++){
        int dy=y+j; if (dy<scr->clipY0||dy>=scr->clipY1) continue;
        for (int i=0;i<8;i++){
            int dx=x+i; if (dx<scr->clipX0||dx>=scr->clipX1) continue;
            u32 argb;
            if (bpp<=8){
                int bitPos=(j*8+i)*bpp;
                int idx=(vm->read8(dataPtr+(bitPos>>3)) >> (bitPos&7)) & mask;  /* LSB-first */
                if ((pixFmt==7 || transp) && idx==0) continue;
                argb = (pixFmt==7) ? scr->rgb332[idx&0xff] : scr->palette[(idx+palOff)&0xff];
            } else {
                int v=vm->read16(dataPtr+(j*8+i)*2)&0xffff;
                if (transp && v==0) continue;
                argb = (pixFmt==8) ? rgb565ToArgb2(v) : Screen::rgb555ToArgb(v);
            }
            scr->put(dx,dy,argb);
        }
    }
}

/* colisao AABB entre dois retangulos (top-left + w/h) */
static bool aabb(int ax,int ay,int aw,int ah,int bx,int by,int bw,int bh){
    return ax < bx+bw && ax+aw > bx && ay < by+bh && ay+ah > by;
}

int Sdk::spriteBoxCollision(int boxPtr,int from,int to){
    if (!slotSprite) return -1;
    int bx=(i16)vm->read16(boxPtr),   by=(i16)vm->read16(boxPtr+2);
    int bw=vm->read16(boxPtr+4),      bh=vm->read16(boxPtr+6);
    int lo = (from>0)?from:0, hi = (to < spriteCount-1)?to:(spriteCount-1);
    for (int i=lo;i<=hi;i++){
        int sp=slotSprite[i]; if (!sp) continue;
        int sw=vm->read16(sp+6), sh=vm->read16(sp+8);
        if (aabb(bx,by,bw,bh, slotX[i],slotY[i],sw,sh)) return i;
    }
    return -1;
}

int Sdk::spriteCollision(int check,int from,int to){
    if (!slotSprite || check<0 || check>=spriteCount || slotSprite[check]==0) return -1;
    int cp=slotSprite[check];
    int cw=vm->read16(cp+6), ch=vm->read16(cp+8);
    int cx=slotX[check], cy=slotY[check];
    int lo = (from>0)?from:0, hi = (to < spriteCount-1)?to:(spriteCount-1);
    for (int i=lo;i<=hi;i++){
        int sp=slotSprite[i]; if (!sp) continue;
        int sw=vm->read16(sp+6), sh=vm->read16(sp+8);
        if (aabb(cx,cy,cw,ch, slotX[i],slotY[i],sw,sh)) return i;
    }
    return -1;
}

/* vCopyRect(VMGPSTRIDEPTR* dest, *source, u16 width, u16 height): copia um retangulo de
   graficos 8bpp entre dois locais. STRIDEPTR{ptr@0, xpan@4, ypan@6, stride@8};
   ptr==0 = a TELA em (0,0). Golf Pro Contest 1/2 desenham o campo com isto (antes
   no-op -> chao preto/magenta). */
void Sdk::copyRect(int dS,int sS,int w,int h){
    w&=0xffff; h&=0xffff;
    if (w<=0||h<=0||w>4096||h>4096) return;
    int dP=vm->read32(dS), dX=(i16)vm->read16(dS+4), dY=(i16)vm->read16(dS+6), dStr=(i16)vm->read16(dS+8);
    int sP=vm->read32(sS), sX=(i16)vm->read16(sS+4), sY=(i16)vm->read16(sS+6), sStr=(i16)vm->read16(sS+8);
    bool dScr=(dP==0), sScr=(sP==0);
    if (dScr && dStr==0) dStr=scr->w;
    if (sScr && sStr==0) sStr=scr->w;
    for (int y=0;y<h;y++){
        for (int x=0;x<w;x++){
            int b;
            if (sScr){
                int sx=sX+x, sy=sY+y;
                if (sx<0||sx>=scr->w||sy<0||sy>=scr->h) continue;
                b = argbToRgb332(scr->get(sx,sy));
            } else {
                b = vm->read8(sP + sX + (sY+y)*sStr + x) & 0xff;
            }
            if (dScr){
                int dx=dX+x, dy=dY+y;
                if (dx<0||dx>=scr->w||dy<0||dy>=scr->h) continue;
                scr->put(dx,dy, scr->rgb332[b]);
            } else {
                vm->write8(dP + dX + (dY+y)*dStr + x, b);
            }
        }
    }
}

/* ================= tilemap ================= */

/* Le o MAP_HEADER do jogo para a copia interna. So faz isso quando o jogo MANDA
   (vMapInit / vMapHeaderUpdate, que zeram mhValid) -- nunca a cada desenho.
   Motivo (Crazy Bombs): a funcao que monta a fase poe o MAP_HEADER na PILHA e depois
   RETORNA; dali em diante aquela memoria e reaproveitada, entao reler o header vivo a
   cada drawMap trazia lixo (largura/altura/ponteiros aleatorios) -> o cenario virava
   faixas de fragmentos. O runtime real obviamente tira um INSTANTANEO no vMapInit, e a
   doc confirma: vMapHeaderUpdate existe justamente p/ o jogo avisar que mexeu. */
void Sdk::mapSync(){
    if (mhValid) return;                       /* ja temos o instantaneo */
    int hp = mapHeaderPtr; if (hp==0) return;
    int w = vm->read8(hp+2)&0xff, h = vm->read8(hp+3)&0xff;
    int md = vm->read32(hp+16), td = vm->read32(hp+20);
    if (w==0||h==0||md==0||td==0) return;      /* header morto: mantem a copia */
    /* Ponteiro PLAUSIVEL = area estatica (code/data/bss/res) ou heap ABAIXO do topo
       alocado. "Diferente de zero" nao basta: o MAP_HEADER do Crazy Bombs vive na pilha
       e passava a valer tileData=0x25000c, que nunca foi alocado. */
    if (!plausiblePtr(md) || !plausiblePtr(td)) return;
    mhFlag = vm->read8(hp)&0xff;
    mhFmt  = vm->read8(hp+1)&0xff;
    mhW = w; mhH = h;
    mhAnim = vm->read8(hp+4)&0xff;
    mhXPan = (i16)vm->read16(hp+8);
    mhYPan = (i16)vm->read16(hp+10);
    mhXPos = vm->read16(hp+12)&0xffff;         /* MAP_HEADER.x/y sao uint16 */
    mhYPos = vm->read16(hp+14)&0xffff;
    mhMapData = md; mhTileData = td;
    mhValid = true;
}

void Sdk::drawMap(){
    mapSync();
    if (!mhValid) return;
    int flag = mhFlag;
    int mapW = mhW, mapH = mhH;
    int animSpeed = mhAnim;
    int xPan = mhXPan, yPan = mhYPan;
    /* xPos/yPos: ler com SINAL. O vmgp.h declara MAP_HEADER.x/y como uint16, e normalizar
       por (valor & 0xffff) % tamanho quebra o City Auto: ele faz vMapSetXY(-20, ...) num
       mapa 48x48 e usa o -20 p/ alinhar a coluna 0 com a borda da area de jogo. Com wrap
       (65516 % 384 = 236) o DESENHO ia parar noutro pedaco enquanto a COLISAO (o jogo le
       por vMapGetTile) continuava certa -> parede invisivel. */
    int xPos = (i16)mhXPos, yPos = (i16)mhYPos;
    int mapData = mhMapData, tileData = mhTileData;
    if (mapData < vm->base || mapData >= vm->base + vm->memLen) return;
    if (tileData < vm->base || tileData >= vm->base + vm->memLen) return;

    /* Plano de atributos: QUALQUER flag != 0 implica plano intercalado (passo 2).
       MAP_USERATTRIBUTE(0x2) e MAP_AUTOANIM(0x8) precisam de um byte por tile, e a camada
       de HUD do Honey Cave 2 tem flag=0x1 e TAMBEM e intercalada (com passo 1 o painel do
       placar virava farrapos, lendo byte de atributo como tile). Medido no Chang's Orbhunt
       (flag=0x8): com passo 1, 59 de 130 celulas caiam em "tile 0"; com passo 2, zero. */
    bool hasAttr = (flag != 0);
    int cell = hasAttr ? 2 : 1;
    const int TW=8, TH=8;
    /* O campo `format` vale o mesmo que o de um sprite: o tile NAO e sempre 8bpp.
       Final Hit (T300) declara format=5 (Palette16, 4bpp): lendo 1 byte por pixel o ceu
       e a cidade saiam como RUIDO RGB. */
    int fmt = mhFmt;
    int bpp = bppOf(fmt), mask=(1<<bpp)-1;
    int tileBytes = (TW*TH*bpp+7)/8;
    const u32* pal = (fmt==7) ? scr->rgb332 : scr->palette;   /* fmt 7 = RGB332 direto */

    /* Transparencia de tile e propriedade POR TILE (bit no byte de atributo). Tratar todo
       texel indice 0 como transparente so porque a camada tem a flag furava fundos
       solidos: no Shooting Star o tile da parede tem 14 dos 64 texels no indice 0 -> esses
       pixels nunca eram repintados e os alvos deixavam rastro. */
    bool layerTransp = (flag & 0x1)!=0;        /* MAP_TRANSPARENT */

    /* rolagem: xPos/yPos = scroll do mapa; xPan/yPan = posicao na tela */
    int startDrawX = xPan - (xPos % TW);
    int startDrawY = yPan - (yPos % TH);
    int tileStartX = xPos / TW, tileStartY = yPos / TH;
    int tilesAcross = (scr->w - startDrawX + TW-1)/TW + 1;
    int tilesDown   = (scr->h - startDrawY + TH-1)/TH + 1;

    for (int ry=0; ry<tilesDown; ry++){
        int my = tileStartY+ry; if (my<0||my>=mapH) continue;
        for (int rx=0; rx<tilesAcross; rx++){
            int mx = tileStartX+rx; if (mx<0||mx>=mapW) continue;
            int idx = (my*mapW+mx)*cell;
            int t = vm->read8(mapData+idx)&0xff;
            if (t==0) continue; t -= 1;              /* base-1; 0 = vazio */
            int attr = hasAttr ? (vm->read8(mapData+idx+1)&0xff) : 0;
            /* O nibble ALTO do atributo significa coisas diferentes conforme o FORMATO:
                 - Palette2/4/16 (fmt 3/4/5): e o BANCO DE PALETA (igual ao palOff do
                   sprite). O indice do pixel so vai de 0..15, entao sem o banco tudo cai
                   nas 16 primeiras cores (Hugo Evil Mirror: plataformas viravam riscos
                   branco/azul em vez de madeira marrom).
                 - RGB332 (fmt 7) e cor DIRETA, nao ha paleta -> ali os bits 6/7 sao os
                   flags de ANIMACAO.
               Medido no acervo: todo mapa indexado que usa o nibble alto o usa em
               multiplos de 16 e NUNCA liga o bit 7; todo mapa fmt=7 que usa liga o bit 7. */
            bool bankFmt = (fmt==3||fmt==4||fmt==5);
            int palBank = bankFmt ? (attr & 0xF0) : 0;
            if (!bankFmt){
                if ((attr&0x40)!=0) t += (frameAnimate % 2);        /* anima 2 frames */
                else if ((attr&0x80)!=0) t += (frameAnimate % 4);   /* anima 4 frames */
            }
            int tp = tileData + t*tileBytes;
            /* O bit 0 do atributo do TILE vale sozinho, mesmo sem MAP_TRANSPARENT na
               camada. vmgp.h ("Differences since API version 1.30") diz que a flag da
               camada passa a vetar o bit do tile -- mas logo abaixo: "The above changes do
               not take effect unless the API version is explicitly set in the game"
               (opt-in). Ou seja, o PADRAO e o comportamento antigo. */
            bool tileTransp = (layerTransp && (!hasAttr || (attr & 0x1)!=0))
                           || (hasAttr && (attr & 0x1)!=0);
            int sx = startDrawX + rx*TW, sy = startDrawY + ry*TH;
            for (int j=0;j<TH;j++){
                int dy=sy+j; if (dy<scr->clipY0||dy>=scr->clipY1) continue;
                for (int i=0;i<TW;i++){
                    int dx=sx+i; if (dx<scr->clipX0||dx>=scr->clipX1) continue;
                    int v; u32 argb;
                    if (bpp<=8){                       /* sub-byte LSB-first, igual sprite */
                        int bitPos=(j*TW+i)*bpp;
                        v = (vm->read8(tp+(bitPos>>3)) >> (bitPos&7)) & mask;
                        if (fmt==0||fmt==1||fmt==2){
                            int g=iround2(v,255,mask);
                            argb = 0xff000000u|((u32)g<<16)|((u32)g<<8)|(u32)g;
                        } else {
                            argb = pal[(v + palBank) & 255];
                        }
                    } else {                           /* 16bpp direto */
                        v = vm->read16(tp+(j*TW+i)*2)&0xffff;
                        argb = (fmt==8) ? rgb565ToArgb2(v) : Screen::rgb555ToArgb(v);
                    }
                    /* cor-chave = indice 0 */
                    if (tileTransp && ((bpp<=8) ? (v==0) : ((argb&0xffffff)==0))) continue;
                    scr->put(dx,dy,argb);
                }
            }
        }
    }
    if (animSpeed!=0 && (++framePassed > animSpeed)){
        frameAnimate = (frameAnimate+1) & 3;
        framePassed = 0;
    }
}

int Sdk::mapAttr(int x,int y){
    mapSync(); if (!mhValid) return 0;
    if (mhFlag==0) return 0;                   /* sem plano de atributos */
    if (x<0||y<0||x>=mhW||y>=mhH) return 0;
    return vm->read8(mhMapData+(y*mhW+x)*2+1)&0xff;
}
int Sdk::mapTile(int x,int y){
    mapSync(); if (!mhValid) return 0;
    if (x<0||y<0||x>=mhW||y>=mhH) return 0;
    int cell = (mhFlag!=0)?2:1;                /* mesmo passo do drawMap */
    return vm->read8(mhMapData+(y*mhW+x)*cell)&0xff;
}
/* setters simetricos aos getters: escrevem no mapData da VM (o drawMap le de volta).
   Sem isto eram no-op -> tilemaps VAZIOS -> jogos SEM CHAO e com "smearing" (o mapa nao
   cobria a tela e o frame anterior persistia). */
void Sdk::mapSetTile(int x,int y,int tile){
    mapSync(); if (!mhValid) return;
    if (x<0||y<0||x>=mhW||y>=mhH) return;
    int cell = (mhFlag!=0)?2:1;
    vm->write8(mhMapData+(y*mhW+x)*cell, tile&0xff);
}
void Sdk::mapSetAttr(int x,int y,int attr){
    mapSync(); if (!mhValid) return;
    if (mhFlag==0) return;
    if (x<0||y<0||x>=mhW||y>=mhH) return;
    vm->write8(mhMapData+(y*mhW+x)*2+1, attr&0xff);
}
