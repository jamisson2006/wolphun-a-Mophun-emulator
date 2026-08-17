package wolphun;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Framebuffer ARGB + paleta + fonte + entrada — versão ANDROID.
 *
 * Mantém EXATAMENTE a API pública que o núcleo (Sdk, Renderer3D) usa
 * (fb, palette, rgb332, w, h, clip*, flips, present(), setClip(), inClip(),
 * getButtons(), selectFont(), drawText(), showMessageBox(), textWidth(),
 * fontHeight(), setPaletteRgb555()), mas o desenho de texto usa android.graphics
 * e o "present" empurra o frame para um FrameSink (a SurfaceView do emulador).
 * O input vem do teclado virtual (touch) via setButtonBit().
 */
public final class Screen {
    public final int w, h, scale;
    public final int[] fb;                       // framebuffer ARGB (0xFFrrggbb)
    public final int[] palette = new int[256];   // ARGB (paleta mutável: RGB332 default + overrides do jogo)
    public final int[] rgb332  = new int[256];   // ARGB RGB332 FIXO (cor direta de sprites/tiles format 7)

    // ---- bits de botão mophun (nofun KeyCode.cs) ----
    public static final int UP=0x01, DOWN=0x02, LEFT=0x04, RIGHT=0x08,
        FIRE=0x10, SELECT=0x20, FIRE2=0x100;
    private volatile int buttons = 0;

    // janela de recorte [clipX0,clipX1) x [clipY0,clipY1)
    public int clipX0=0, clipY0=0, clipX1, clipY1;

    // frame pacing: vFlipScreen "bloqueia" até 1/targetFps
    public int targetFps = 60;
    private long lastFrameNanos = 0;
    public double gamma = 1.0;
    public volatile int flips = 0;

    // ---- buffer de APRESENTAÇÃO (frame COMPLETO) ----
    // O jogo desenha em 'fb' (que passa por estados incompletos durante um frame).
    // Em present() (fim do frame, no vFlipScreen) tiramos um snapshot de fb -> 'presented'.
    // A SurfaceView lê 'presented' -> nunca mostra frame pela metade => sem tearing/piscada.
    private final Object frameLock = new Object();
    private int[] presented;
    /** copia o último frame COMPLETO para 'out'. false se ainda não houve nenhum frame. */
    public boolean copyPresented(int[] out){
        synchronized(frameLock){
            if(presented==null) return false;
            System.arraycopy(presented, 0, out, 0, Math.min(out.length, presented.length));
            return true;
        }
    }

    // ---- fonte (Paint) ----
    private final Paint textPaint = new Paint();
    private final Bitmap fontScratch;
    private final Canvas fontCanvas;
    private final int[] fontPixels;
    private final int fsW = 320, fsH = 64;
    private float baseline;
    private int lineHeight;

    // buffer de saída com gamma (só usado se gamma != 1.0)
    private int[] displayFb;

    public Screen(int w, int h, int scale){
        this.w=w; this.h=h; this.scale=scale;
        clipX1=w; clipY1=h;
        fb = new int[w*h];
        initRgb332Palette();
        textPaint.setAntiAlias(false);
        textPaint.setColor(0xffffffff);
        fontScratch = Bitmap.createBitmap(fsW, fsH, Bitmap.Config.ARGB_8888);
        fontCanvas  = new Canvas(fontScratch);
        fontPixels  = new int[fsW*fsH];
        selectFont(11);
    }

    // ---- input (chamado pelo teclado virtual e lido pelo Sdk via getButtons) ----
    public void setButtonBit(int bit, boolean down){ if(down) buttons|=bit; else buttons&=~bit; }
    public void clearButtons(){ buttons = 0; }
    public int getButtons(){ return buttons; }

    // ---- clip ----
    public void setClip(int x0,int y0,int x1,int y1){
        if(x0>x1){ int t=x0; x0=x1; x1=t; }
        if(y0>y1){ int t=y0; y0=y1; y1=t; }
        clipX0=Math.max(0,x0); clipY0=Math.max(0,y0);
        clipX1=Math.min(w,x1); clipY1=Math.min(h,y1);
    }
    public boolean inClip(int x,int y){ return x>=clipX0 && x<clipX1 && y>=clipY0 && y<clipY1; }

    // ---- paleta ----
    private void initRgb332Palette(){
        for(int i=0;i<256;i++){
            int r=(i>>5)&7, g=(i>>2)&7, b=i&3;
            int c=0xff000000|(Math.round(r/7f*255)<<16)|(Math.round(g/7f*255)<<8)|Math.round(b/3f*255);
            palette[i]=c; rgb332[i]=c;
        }
    }
    public static int rgb555ToArgb(int rgb555){
        int r=(rgb555>>10)&31, g=(rgb555>>5)&31, b=rgb555&31;
        return 0xff000000|(Math.round(r/31f*255)<<16)|(Math.round(g/31f*255)<<8)|Math.round(b/31f*255);
    }
    public void setPaletteRgb555(int index,int rgb555){ palette[index&0xff]=rgb555ToArgb(rgb555); }
    public static int argbToRgb555(int argb){
        int r=(argb>>16)&0xff, g=(argb>>8)&0xff, b=argb&0xff;
        return ((r>>3)<<10)|((g>>3)<<5)|(b>>3);
    }

    // ---- fonte ----
    public void selectFont(int px){
        if(px<6) px = 8 + px*2;
        if(px>24) px = 24;
        textPaint.setTextSize(px);
        Paint.FontMetrics m = textPaint.getFontMetrics();
        baseline = -m.ascent + 1;
        lineHeight = (int)Math.ceil(m.descent - m.ascent);
    }
    public int textWidth(String s){ return (s==null||s.isEmpty())?0:(int)Math.ceil(textPaint.measureText(s)); }
    public int fontHeight(){ return lineHeight; }

    // desenha texto no framebuffer em (x,y)=canto sup-esq, cor ARGB de frente.
    public void drawText(int x,int y,String s,int argbFg){
        if(s==null||s.isEmpty()) return;
        int tw = Math.min(textWidth(s)+2, fsW);
        int th = Math.min(fontHeight()+2, fsH);
        fontScratch.eraseColor(Color.TRANSPARENT);
        textPaint.setColor(argbFg | 0xff000000);
        fontCanvas.drawText(s, 1, baseline, textPaint);
        fontScratch.getPixels(fontPixels, 0, fsW, 0, 0, tw, th);
        int rgb = argbFg & 0xffffff;
        for(int j=0;j<th;j++){
            int dy=y+j; if(dy<clipY0||dy>=clipY1) continue;
            for(int i=0;i<tw;i++){
                int dx=x+i; if(dx<clipX0||dx>=clipX1) continue;
                int p = fontPixels[j*fsW+i];
                if((p>>>24) >= 0x80) fb[dy*w+dx] = 0xff000000 | rgb;
            }
        }
    }

    // ---- present: pacing + empurra o frame p/ a SurfaceView ----
    public void present(){
        flips++;
        if(targetFps>0){
            long interval = 1000000000L/targetFps;
            long now = System.nanoTime();
            if(lastFrameNanos>0){
                long wait = interval - (now-lastFrameNanos);
                if(wait>1000000L){
                    try { Thread.sleep(wait/1000000L, (int)(wait%1000000L)); }
                    catch(InterruptedException e){ Thread.currentThread().interrupt(); return; }
                }
            }
            lastFrameNanos = System.nanoTime();
        }
        // snapshot do frame COMPLETO (com gamma) p/ a SurfaceView desenhar sem tearing
        synchronized(frameLock){
            if(presented==null || presented.length!=fb.length) presented=new int[fb.length];
            if(gamma==1.0) System.arraycopy(fb, 0, presented, 0, fb.length);
            else { ensureGammaLut(); for(int i=0;i<fb.length;i++) presented[i]=applyGamma(fb[i]); }
        }
    }

    // ---- message box modal (vMsgBox/vMsgBoxU): desenha caixa + texto, espera FIRE, retorna 1 ----
    public int showMessageBox(String title, String msg){
        drawMsgBox(title, msg);
        present();
        try {
            long t0=System.currentTimeMillis();
            while(getButtons()!=0 && System.currentTimeMillis()-t0<3000){ present(); Thread.sleep(15); }
            while((getButtons()&(FIRE|SELECT))==0){ present(); Thread.sleep(15); }
            while(getButtons()!=0){ present(); Thread.sleep(15); }
        } catch(InterruptedException e){ Thread.currentThread().interrupt(); }
        return 1;
    }
    private void drawMsgBox(String title, String msg){
        int bg=0xff101840, border=0xffffffff;
        for(int y=3;y<h-3;y++) for(int x=3;x<w-3;x++)
            fb[y*w+x] = (x==3||x==w-4||y==3||y==h-4) ? border : bg;
        int yy=7;
        if(title!=null && !title.isEmpty()){ drawText(7, yy, title, 0xff00ffff); yy+=fontHeight()+3; }
        for(String line: wrapText(msg, w-14)){ if(yy>h-20) break; drawText(7, yy, line, 0xffffff00); yy+=fontHeight(); }
        drawText(7, h-16, "[ OK ]", 0xff00ff00);
    }
    private java.util.List<String> wrapText(String s, int maxW){
        java.util.List<String> out=new java.util.ArrayList<String>();
        if(s==null) return out;
        for(String para : s.split("\n")){
            StringBuilder line=new StringBuilder();
            for(String word : para.split(" ")){
                String test = line.length()==0 ? word : line+" "+word;
                if(textWidth(test)>maxW && line.length()>0){ out.add(line.toString()); line=new StringBuilder(word); }
                else line=new StringBuilder(test);
            }
            out.add(line.toString());
        }
        return out;
    }

    // ---- gamma (clareia preservando matiz) ----
    private int[] gammaLut = null; private double gammaBuilt = -1;
    private void ensureGammaLut(){
        if(gammaLut==null || gammaBuilt!=gamma){
            gammaLut = new int[256];
            for(int i=0;i<256;i++) gammaLut[i]=(int)Math.round(Math.pow(i/255.0, gamma)*255);
            gammaBuilt = gamma;
        }
    }
    private int applyGamma(int argb){
        int r=(argb>>16)&0xff, g=(argb>>8)&0xff, b=argb&0xff;
        int m = r>g ? (r>b?r:b) : (g>b?g:b);
        if(m==0) return 0xff000000;
        int m2 = gammaLut[m];
        int rr=(r*m2 + (m>>1))/m, gg=(g*m2 + (m>>1))/m, bb=(b*m2 + (m>>1))/m;
        return 0xff000000|(clamp8(rr)<<16)|(clamp8(gg)<<8)|clamp8(bb);
    }
    private static int clamp8(int v){ return v>255?255:(v<0?0:v); }
}
