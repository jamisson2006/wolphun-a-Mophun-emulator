/* Screen.h - ARGB framebuffer + palette + clipping + input + the system font.
 * Ported from src/wolphun/Screen.java, WITHOUT AWT/Swing.
 *
 * Mophun's graphics model is 8bpp: sprites and tiles store INDICES, while fills and text
 * use either direct colours (RGB555 with bit 31 set) or palette indices. Everything is
 * converted to ARGB in the framebuffer, as nofun does, which gets both transparency and
 * colours right.
 *
 * Differences from the Java version:
 *  - The internal supersampling (rs) is gone: on a phone it buys nothing and costs a
 *    lot. The framebuffer is logical = physical (w x h).
 *  - The AWT font became an embedded BITMAP font (inc/sysfont.h), generated from the
 *    SAME font the PC emulator uses, so the text matches with no OS dependency.
 *  - present() and showMessageBox() are delegated to the HOST (the Symbian shell)
 *    through the ScreenHost interface, so the core stays portable.
 */
#ifndef WOLPHUN_SCREEN_H
#define WOLPHUN_SCREEN_H

#include "vmcommon.h"

/* Implemented by the shell (the Symbian app) or by the test harness. */
class ScreenHost {
public:
    virtual ~ScreenHost(){}
    /* called by vFlipScreen: the framebuffer is ready to go to the display. */
    virtual void Present() = 0;
    /* milliseconds since start, the time base for vGetTickCount and frame pacing. */
    virtual u32 TickMs() = 0;
    /* modal message box (vMsgBox). Returns the button pressed (1 = OK). */
    virtual int MessageBox(const char* title, const char* msg){ (void)title; (void)msg; return 1; }
    /* seconds since 1970 UTC (vGetTime). */
    virtual u32 EpochSeconds(){ return 0; }
    /* local date and time (vGetTimeDate): month 1-12, day of month 1-31. */
    virtual void DateTime(int* year,int* month,int* day,int* hour,int* minute,int* second){
        if(year)*year=2008; if(month)*month=1; if(day)*day=1;
        if(hour)*hour=0; if(minute)*minute=0; if(second)*second=0;
    }
};

class Screen {
public:
    /* button mask - Mophun's REAL BITS (from nofun's KeyCode.cs). */
    enum {
        UP=0x01, DOWN=0x02, LEFT=0x04, RIGHT=0x08,
        FIRE=0x10, SELECT=0x20, FIRE2=0x100
    };

    int w, h;
    u32* fb;                 /* ARGB framebuffer (w*h) */
    u32 palette[256];        /* mutable palette: RGB332 by default, overridden by the game */
    u32 rgb332[256];         /* FIXED RGB332: the direct colour of format-7 sprites and tiles */

    /* clipping window: [clipX0,clipX1) x [clipY0,clipY1) */
    int clipX0, clipY0, clipX1, clipY1;

    int  targetFps;          /* vFlipScreen "blocks" to the device's refresh */
    u32  flips;              /* frame counter (vFrameTickCount) */
    i64  pixels2d;           /* diagnostic: pixels written by the 2D path */

    Screen(int w, int h, ScreenHost* host);
    ~Screen();

    /* ---- paleta ---- */
    static u32 rgb555ToArgb(int rgb555);
    static int argbToRgb555(u32 argb);
    void setPaletteRgb555(int index, int rgb555){ palette[index & 0xff] = rgb555ToArgb(rgb555); }

    /* ---- clip ---- */
    void setClip(int x0,int y0,int x1,int y1);
    bool inClip(int x,int y) const { return x>=clipX0 && x<clipX1 && y>=clipY0 && y<clipY1; }

    /* ---- pixels ---- */
    void put(int x,int y,u32 argb){
        if(x<clipX0||x>=clipX1||y<clipY0||y>=clipY1) return;
        pixels2d++;
        fb[y*w+x] = argb;
    }
    void putNoClip(int x,int y,u32 argb){
        if(x<0||x>=w||y<0||y>=h) return;
        fb[y*w+x] = argb;
    }
    u32 get(int x,int y) const {
        if(x<0||x>=w||y<0||y>=h) return 0;
        return fb[y*w+x];
    }
    void fill(u32 argb){ for(int i=0,n=w*h;i<n;i++) fb[i]=argb; }

    /* ---- presentation ---- */
    void present();

    /* ---- input ---- */
    int  getButtons() const { return buttons; }
    void setButtonBit(int bit, bool down){ if(down) buttons|=bit; else buttons&=~bit; }
    void clearButtons(){ buttons=0; }
    void setKeypad(int ascii, bool down){ if(ascii>=0 && ascii<256) keypadDown[ascii]=(u8)(down?1:0); }
    bool keypadPressed(int ascii) const { return ascii>=0 && ascii<256 && keypadDown[ascii]!=0; }

    /* ---- the system font, standing in for vTextOut / vTextOutU / vPrint ---- */
    /* The SDK's vSelectFont(size, flags); with a bitmap font only the integer SCALE
       can vary. */
    void selectFont(int size, int flags);
    int  textWidth(const char* s) const;
    int  fontHeight() const;
    void drawText(int x,int y,const char* s,u32 argbFg);

    int  messageBox(const char* title, const char* msg);

    ScreenHost* host;

private:
    void initRgb332Palette();
    int  charWidth(unsigned char c) const;

    volatile int buttons;
    u8  keypadDown[256];     /* 0..127 ASCII plus 200..217, the handset keys */
    int fontScale;           /* integer scale of the bitmap font (1..4) */
    int lastFontSize, lastFontFlags;
    u32 lastFlipMs;
};

#endif /* WOLPHUN_SCREEN_H */
