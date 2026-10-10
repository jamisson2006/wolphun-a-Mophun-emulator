/* Screen.cpp - ported from src/wolphun/Screen.java (see Screen.h). */
#include "Screen.h"
#include "sysfont.h"

/* integer rounding of (v * num / den), equivalent to Java's Math.round(float) */
static inline int rnd(int v, int num, int den){ return (v*num*2 + den) / (den*2); }

Screen::Screen(int w_, int h_, ScreenHost* host_)
: w(w_), h(h_), fb(0),
  clipX0(0), clipY0(0), clipX1(w_), clipY1(h_),
  targetFps(60), flips(0), pixels2d(0),
  host(host_), buttons(0), fontScale(1),
  lastFontSize(11), lastFontFlags(0), lastFlipMs(0)
{
    fb = new u32[w*h];
    for (int i=0;i<w*h;i++) fb[i]=0xff000000u;
    for (int i=0;i<256;i++) keypadDown[i]=0;
    initRgb332Palette();
}

Screen::~Screen(){ delete[] fb; }

void Screen::initRgb332Palette(){
    for (int i=0;i<256;i++){
        int r=(i>>5)&7, g=(i>>2)&7, b=i&3;
        u32 c = 0xff000000u
              | ((u32)rnd(r,255,7) << 16)
              | ((u32)rnd(g,255,7) << 8)
              |  (u32)rnd(b,255,3);
        palette[i]=c; rgb332[i]=c;   /* rgb332 stays FIXED; palette can be overwritten by the game */
    }
}

u32 Screen::rgb555ToArgb(int rgb555){
    int r=(rgb555>>10)&31, g=(rgb555>>5)&31, b=rgb555&31;
    return 0xff000000u | ((u32)rnd(r,255,31) << 16) | ((u32)rnd(g,255,31) << 8) | (u32)rnd(b,255,31);
}

int Screen::argbToRgb555(u32 argb){
    int r=(int)((argb>>16)&0xff), g=(int)((argb>>8)&0xff), b=(int)(argb&0xff);
    return ((r>>3)<<10) | ((g>>3)<<5) | (b>>3);
}

/* vSetClipWindow(x0,y0,x1,y1): the SDK is INCLUSIVE (x1/y1 are the last column and
   row, like vFillRect -- vmgp.h calls it "Right position"). We store it exclusive,
   [x0,x1), by adding 1, so consumers can keep using `>=clipX1`. Without that, a
   one-row clip -- a logo reveal, for instance -- became an empty range and nothing
   appeared. */
void Screen::setClip(int x0,int y0,int x1,int y1){
    if (x0>x1){ int t=x0; x0=x1; x1=t; }
    if (y0>y1){ int t=y0; y0=y1; y1=t; }
    clipX0 = x0<0?0:x0;
    clipY0 = y0<0?0:y0;
    clipX1 = (x1+1 > w) ? w : (x1+1);
    clipY1 = (y1+1 > h) ? h : (y1+1);
    if (clipX1 < clipX0) clipX1 = clipX0;
    if (clipY1 < clipY0) clipY1 = clipY0;
}

void Screen::present(){
    flips++;
    if (host){
        host->Present();
        /* Frame pacing: vFlipScreen is meant to follow the device's refresh. Here we
           only record the moment; the actual wait, if any, is the shell's job, since it
           is the part that knows the system's event loop. */
        lastFlipMs = host->TickMs();
    }
}

int Screen::messageBox(const char* title, const char* msg){
    return host ? host->MessageBox(title, msg) : 1;
}

/* ---------------- the system font (embedded bitmap) ---------------- */

/* vSelectFont(size, flags), from vmgp.h:
     bit31 (FONT_SIZE_PIXEL_FLAG)  -> the low bits are a height in PIXELS
     bit30 (FONT_SIZE_POINTS_FLAG) -> the low bits are a height in POINTS (~4/3 px)
     otherwise -> a relative preset (0 = Normal, 1 = Small, 2 = Large)
   With a fixed-height bitmap font (SYSFONT_HEIGHT), the request is honoured by picking
   the largest INTEGER SCALE that still fits inside the height asked for. */
void Screen::selectFont(int size, int flags){
    lastFontSize = size; lastFontFlags = flags;
    int px;
    if ((size & 0x80000000) != 0)      px = size & 0x00FFFFFF;
    else if ((size & 0x40000000) != 0) px = ((size & 0x00FFFFFF) * 4 + 1) / 3;
    else { px = size; if (px < 6) px = 8 + px*2; if (px > 24) px = 24; }
    int s = px / SYSFONT_HEIGHT;
    if (s < 1) s = 1;
    if (s > 4) s = 4;
    fontScale = s;
}

int Screen::charWidth(unsigned char c) const {
    if (c < SYSFONT_FIRST || c > SYSFONT_LAST) return 0;
    return kSysFontW[c - SYSFONT_FIRST] * fontScale;
}

int Screen::textWidth(const char* s) const {
    if (!s) return 0;
    int tw=0;
    for (const unsigned char* p=(const unsigned char*)s; *p; ++p) tw += charWidth(*p);
    return tw;
}

int Screen::fontHeight() const { return SYSFONT_HEIGHT * fontScale; }

void Screen::drawText(int x,int y,const char* s,u32 argbFg){
    if (!s || !*s) return;
    int penX = x;
    for (const unsigned char* p=(const unsigned char*)s; *p; ++p){
        unsigned char c = *p;
        if (c < SYSFONT_FIRST || c > SYSFONT_LAST){ continue; }
        int gi = c - SYSFONT_FIRST;
        int cw = kSysFontW[gi];
        const unsigned short* rows = &kSysFontBits[gi * SYSFONT_HEIGHT];
        for (int row=0; row<SYSFONT_HEIGHT; row++){
            unsigned short bits = rows[row];
            if (!bits){ continue; }
            for (int col=0; col<cw; col++){
                if ((bits & (1u<<col)) == 0) continue;
                /* expand by the integer scale */
                int bx = penX + col*fontScale, by = y + row*fontScale;
                for (int oy=0; oy<fontScale; oy++)
                    for (int ox=0; ox<fontScale; ox++)
                        put(bx+ox, by+oy, argbFg);
            }
        }
        penX += cw * fontScale;
    }
}
