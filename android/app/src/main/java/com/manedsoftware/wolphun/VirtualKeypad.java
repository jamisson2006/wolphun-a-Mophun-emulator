package com.manedsoftware.wolphun;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import wolphun.Screen;

/**
 * Teclado virtual translúcido, CUSTOMIZÁVEL por jogo (estilo J2ME-Loader/PPSSPP):
 * cada botão pode ser MOVIDO (centro salvo como fração da tela) e todos podem ser
 * REDIMENSIONADOS por um fator global. layout() recalcula pelo tamanho da tela e
 * aplica as customizações; exportLayout()/importLayout() persistem por jogo.
 */
public final class VirtualKeypad {
    public static final int MENU = -1;

    public static final class Btn {
        final int bit; final String label; final boolean round;
        final RectF rect = new RectF();
        Float cxFrac, cyFrac;    // centro customizado (fração 0..1 da tela); null = usa o default
        Btn(int bit, String label, boolean round){ this.bit=bit; this.label=label; this.round=round; }
    }

    private final List<Btn> btns = new ArrayList<Btn>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int viewW, viewH;
    private float globalScale = 1f;

    public VirtualKeypad(){
        fill.setColor(0x55FFFFFF);
        fillDown.setColor(0xAAE8451F);
        edit.setColor(0x66E8451F);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(2f); stroke.setColor(0x88FFFFFF);
        text.setColor(0xEEFFFFFF); text.setTextAlign(Paint.Align.CENTER);

        btns.add(new Btn(Screen.UP,    "▲", false));
        btns.add(new Btn(Screen.DOWN,  "▼", false));
        btns.add(new Btn(Screen.LEFT,  "◀", false));
        btns.add(new Btn(Screen.RIGHT, "▶", false));
        btns.add(new Btn(Screen.FIRE,  "OK", true));
        btns.add(new Btn(Screen.FIRE2, "2", true));
        btns.add(new Btn(Screen.SELECT,"SEL", true));
        btns.add(new Btn(MENU,         "≡", true));
    }

    public void layout(int w, int h){ viewW=w; viewH=h; recompute(); }
    public float getGlobalScale(){ return globalScale; }
    public void setGlobalScale(float s){ globalScale = Math.max(0.5f, Math.min(2.2f, s)); recompute(); }

    // recalcula os retângulos: centro = custom (fração) ou default; tamanho = base * globalScale
    private void recompute(){
        if(viewW==0 || viewH==0) return;
        float u = Math.min(viewW, viewH) * 0.11f * globalScale;
        text.setTextSize(u*0.5f);
        float dpadCx = u*2.2f, dpadCy = viewH - u*2.2f;   // D-pad no canto inf-esq
        setBtn(Screen.UP,    dpadCx, dpadCy-u, u);
        setBtn(Screen.DOWN,  dpadCx, dpadCy+u, u);
        setBtn(Screen.LEFT,  dpadCx-u, dpadCy, u);
        setBtn(Screen.RIGHT, dpadCx+u, dpadCy, u);
        float ax = viewW - u*1.6f, ay = viewH - u*1.6f;   // ações no canto inf-dir
        setBtn(Screen.FIRE,  ax,        ay,        u*1.6f);
        setBtn(Screen.FIRE2, ax-u*2.0f, ay,        u*1.2f);
        setBtn(Screen.SELECT,ax,        ay-u*2.0f, u*1.2f);
        setBtn(MENU,         viewW-u*0.9f, u*0.9f, u*0.9f);
    }
    private void setBtn(int bit, float defCx, float defCy, float size){
        for(Btn b: btns) if(b.bit==bit){
            float cx = b.cxFrac!=null ? b.cxFrac*viewW : defCx;
            float cy = b.cyFrac!=null ? b.cyFrac*viewH : defCy;
            b.rect.set(cx-size/2, cy-size/2, cx+size/2, cy+size/2);
            return;
        }
    }

    public void draw(Canvas c, int pressedMask, boolean menuDown, boolean editMode){
        for(Btn x: btns){
            boolean down = (x.bit==MENU) ? menuDown : (pressedMask & x.bit)!=0;
            Paint p = editMode ? edit : (down ? fillDown : fill);
            if(x.round){
                float rad = Math.min(x.rect.width(), x.rect.height())/2f, mx=x.rect.centerX(), my=x.rect.centerY();
                c.drawCircle(mx,my,rad,p); c.drawCircle(mx,my,rad,stroke);
            } else {
                float r = x.rect.height()*0.18f;
                c.drawRoundRect(x.rect, r, r, p); c.drawRoundRect(x.rect, r, r, stroke);
            }
            c.drawText(x.label, x.rect.centerX(), x.rect.centerY() + text.getTextSize()*0.35f, text);
        }
    }

    /** bit do botão sob (x,y), ou 0 se nenhum. */
    public int hitTest(float x, float y){
        for(Btn b: btns) if(b.rect.contains(x,y)) return b.bit;
        return 0;
    }
    /** o Btn sob (x,y) (p/ arrastar no modo edição), ou null. */
    public Btn hitButton(float x, float y){
        for(Btn b: btns) if(b.rect.contains(x,y)) return b;
        return null;
    }
    /** move um botão para o centro (x,y) — salvo como fração da tela. */
    public void moveButton(Btn b, float cx, float cy){
        b.cxFrac = Math.max(0f, Math.min(1f, cx/viewW));
        b.cyFrac = Math.max(0f, Math.min(1f, cy/viewH));
        recompute();
    }

    public void resetLayout(){ for(Btn b: btns){ b.cxFrac=null; b.cyFrac=null; } globalScale=1f; recompute(); }

    /** serializa o layout: "scale|bit:cx,cy|bit:cx,cy|..." (só botões movidos). */
    public String exportLayout(){
        StringBuilder sb = new StringBuilder().append(globalScale);
        for(Btn b: btns) if(b.cxFrac!=null && b.cyFrac!=null)
            sb.append('|').append(b.bit).append(':').append(b.cxFrac).append(',').append(b.cyFrac);
        return sb.toString();
    }
    public void importLayout(String s){
        resetLayout();
        if(s==null || s.isEmpty()) return;
        try {
            String[] parts = s.split("\\|");
            globalScale = Float.parseFloat(parts[0]);
            for(int i=1;i<parts.length;i++){
                String[] kv = parts[i].split(":");
                int bit = Integer.parseInt(kv[0]);
                String[] xy = kv[1].split(",");
                for(Btn b: btns) if(b.bit==bit){ b.cxFrac=Float.parseFloat(xy[0]); b.cyFrac=Float.parseFloat(xy[1]); }
            }
        } catch(Exception e){ resetLayout(); }
        recompute();
    }
}
