/* Screen.h - framebuffer ARGB + paleta + clip + entrada + fonte do sistema.
 * Port de src/wolphun/Screen.java, SEM AWT/Swing.
 *
 * O modelo grafico do mophun (8bpp): sprites/tiles guardam INDICES; fills/textos
 * usam cores diretas (RGB555 com bit31) ou indices de paleta. Convertemos tudo p/
 * ARGB no framebuffer (igual ao nofun) -> transparencia e cores corretas.
 *
 * Diferencas em relacao ao Java:
 *  - Removido o supersampling interno (rs): num celular nao ha ganho, so custo.
 *    O framebuffer e logico = fisico (w x h).
 *  - A fonte AWT virou uma fonte BITMAP embutida (inc/sysfont.h), gerada a partir
 *    da MESMA fonte do emulador de PC -> texto equivalente, sem dependencia de SO.
 *  - present() e showMessageBox() sao delegados ao HOST (a casca Symbian) via a
 *    interface ScreenHost; o nucleo continua portavel.
 */
#ifndef WOLPHUN_SCREEN_H
#define WOLPHUN_SCREEN_H

#include "vmcommon.h"

/* Implementada pela casca (app Symbian) ou pelo harness de teste. */
class ScreenHost {
public:
    virtual ~ScreenHost(){}
    /* chamado por vFlipScreen: o framebuffer esta pronto para ir a tela. */
    virtual void Present() = 0;
    /* milissegundos desde o inicio (base de tempo para vGetTickCount / pacing). */
    virtual u32 TickMs() = 0;
    /* caixa de mensagem modal (vMsgBox). Devolve o botao (1 = OK). */
    virtual int MessageBox(const char* title, const char* msg){ (void)title; (void)msg; return 1; }
    /* segundos desde 1970 UTC (vGetTime). */
    virtual u32 EpochSeconds(){ return 0; }
    /* data/hora local (vGetTimeDate): mes 1-12, dia do mes 1-31. */
    virtual void DateTime(int* year,int* month,int* day,int* hour,int* minute,int* second){
        if(year)*year=2008; if(month)*month=1; if(day)*day=1;
        if(hour)*hour=0; if(minute)*minute=0; if(second)*second=0;
    }
};

class Screen {
public:
    /* mascara de botoes - BITS REAIS do mophun (nofun KeyCode.cs). */
    enum {
        UP=0x01, DOWN=0x02, LEFT=0x04, RIGHT=0x08,
        FIRE=0x10, SELECT=0x20, FIRE2=0x100
    };

    int w, h;
    u32* fb;                 /* framebuffer ARGB (w*h) */
    u32 palette[256];        /* paleta mutavel (RGB332 default + overrides do jogo) */
    u32 rgb332[256];         /* RGB332 FIXO (cor direta de sprites/tiles formato 7) */

    /* janela de recorte: [clipX0,clipX1) x [clipY0,clipY1) */
    int clipX0, clipY0, clipX1, clipY1;

    int  targetFps;          /* vFlipScreen "bloqueia" ao refresh do aparelho */
    u32  flips;              /* contador de frames (vFrameTickCount) */
    i64  pixels2d;           /* diagnostico: pixels escritos pelo caminho 2D */

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

    /* ---- apresentacao ---- */
    void present();

    /* ---- entrada ---- */
    int  getButtons() const { return buttons; }
    void setButtonBit(int bit, bool down){ if(down) buttons|=bit; else buttons&=~bit; }
    void clearButtons(){ buttons=0; }
    void setKeypad(int ascii, bool down){ if(ascii>=0 && ascii<256) keypadDown[ascii]=(u8)(down?1:0); }
    bool keypadPressed(int ascii) const { return ascii>=0 && ascii<256 && keypadDown[ascii]!=0; }

    /* ---- fonte do sistema (substituta p/ vTextOut / vTextOutU / vPrint) ---- */
    /* vSelectFont(size, flags) do SDK; com fonte bitmap so a ESCALA inteira varia. */
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
    u8  keypadDown[256];     /* 0..127 ASCII + 200..217 teclas do aparelho */
    int fontScale;           /* escala inteira da fonte bitmap (1..4) */
    int lastFontSize, lastFontFlags;
    u32 lastFlipMs;
};

#endif /* WOLPHUN_SCREEN_H */
