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
    // botões de TECLA NUMÉRICA literal: bit = KEYPAD_FLAG | ascii (não colide com os bits mophun <=0x100).
    // Emitem scr.setKeypad(ascii) em vez de um bit de botão. Usados por jogos tipo Bomb Jack (andar = '2'/'3').
    public static final int KEYPAD_FLAG = 0x10000;

    public static final class Btn {
        final int bit; final String label; final boolean round;
        boolean visible = true;   // botões numéricos começam ocultos (ligados por jogo via setShowNumbers)
        final RectF rect = new RectF();
        Float cxFrac, cyFrac;    // centro customizado (fração 0..1 da tela); null = usa o default
        float scale = 1f;        // tamanho individual (só p/ botões não-dpad; o d-pad usa dpadScale de grupo)
        Btn(int bit, String label, boolean round){ this.bit=bit; this.label=label; this.round=round; }
    }
    static boolean isDpad(int bit){ return bit==Screen.UP||bit==Screen.DOWN||bit==Screen.LEFT||bit==Screen.RIGHT; }
    static float clampf(float v){ return v<0?0:v>1?1:v; }

    private final List<Btn> btns = new ArrayList<Btn>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillMenu = new Paint(Paint.ANTI_ALIAS_FLAG);   // botao de opcoes (≡): fundo OPACO p/ flutuar visivel sobre o jogo
    private final Paint edit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeMenu = new Paint(Paint.ANTI_ALIAS_FLAG); // borda de destaque do botao de opcoes
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int viewW, viewH;
    // grupo D-pad: move e escala JUNTO. dpadFrac* = centro do grupo (-1 = posição default); dpadScale = tamanho do grupo.
    private float dpadFracX = -1f, dpadFracY = -1f, dpadScale = 1f;

    public VirtualKeypad(){
        fill.setColor(0x55FFFFFF);
        fillDown.setColor(0xAAE8451F);
        fillMenu.setColor(0xCC4A4A52);   // cinza-medio semi-opaco: visivel sobre PRETO (letterbox) E sobre cenas claras
        edit.setColor(0x66E8451F);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(2f); stroke.setColor(0x88FFFFFF);
        strokeMenu.setStyle(Paint.Style.STROKE); strokeMenu.setStrokeWidth(3f); strokeMenu.setColor(0xFFE8451F);   // borda accent
        text.setColor(0xEEFFFFFF); text.setTextAlign(Paint.Align.CENTER);

        btns.add(new Btn(Screen.UP,    "▲", false));
        btns.add(new Btn(Screen.DOWN,  "▼", false));
        btns.add(new Btn(Screen.LEFT,  "◀", false));
        btns.add(new Btn(Screen.RIGHT, "▶", false));
        btns.add(new Btn(Screen.FIRE,  "OK", true));       // acao principal / confirmar
        btns.add(new Btn(Screen.FIRE2, "B", true));        // 2a acao (era "F2", confuso) -> "B" (padrao gamepad)
        btns.add(new Btn(Screen.SELECT,"BACK", true));     // softkey esq. -> a maioria dos jogos VOLTA/SAI com ela ("SEL"/"SELECT" confundia)
        btns.add(new Btn(MENU,         "≡", true));
        // teclas numéricas 2/3 (ocultas por padrão; ligadas por jogo). No Bomb Jack ANDAM esq/dir,
        // por isso os rótulos são setas clássicas ← → (mais claras que "2"/"3").
        Btn k2 = new Btn(KEYPAD_FLAG|'2', "←", true); k2.visible=false; btns.add(k2);
        Btn k3 = new Btn(KEYPAD_FLAG|'3', "→", true); k3.visible=false; btns.add(k3);
    }

    /** mostra/oculta o botão ≡ (MENU) desenhado no canvas. */
    public void setMenuVisible(boolean on){
        for(Btn b: btns) if(b.bit==MENU) b.visible = on;
    }

    /** liga/desliga os botões de tecla numérica (2/3) — por jogo (ex.: Bomb Jack). */
    public void setShowNumbers(boolean on){
        for(Btn b: btns) if((b.bit & KEYPAD_FLAG)!=0) b.visible = on;
        recompute();
    }

    public void layout(int w, int h){ viewW=w; viewH=h; recompute(); }

    // recalcula os retângulos. D-pad = GRUPO (posição+escala compartilhadas). Demais = individuais.
    private void recompute(){
        if(viewW==0 || viewH==0) return;
        float u = Math.min(viewW, viewH) * 0.11f;         // unidade base (sem escala global)
        // --- grupo D-pad: 4 setas ao redor de um centro; move e escala juntas ---
        float du = u * dpadScale;
        float dcx = (dpadFracX>=0) ? dpadFracX*viewW : du*2.2f;
        float dcy = (dpadFracY>=0) ? dpadFracY*viewH : viewH - du*2.2f;
        placeAbs(Screen.UP,    dcx,     dcy-du, du);
        placeAbs(Screen.DOWN,  dcx,     dcy+du, du);
        placeAbs(Screen.LEFT,  dcx-du,  dcy,    du);
        placeAbs(Screen.RIGHT, dcx+du,  dcy,    du);
        // --- botões individuais (posição própria + escala própria) ---
        placeInd(Screen.FIRE,   viewW-u*1.6f,          viewH-u*1.6f,       u*1.6f);
        placeInd(Screen.FIRE2,  viewW-u*1.6f-u*2.0f,   viewH-u*1.6f,       u*1.2f);
        placeInd(Screen.SELECT, viewW-u*1.6f,          viewH-u*1.6f-u*2.0f,u*1.2f);
        placeInd(MENU,          viewW-u*1.0f,          u*2.2f,             u*1.25f);
        // posição padrão do 2/3: parte de cima da zona de controles (como o usuário pediro/print)
        placeInd(KEYPAD_FLAG|'2', viewW*0.36f, viewH*0.68f, u*1.1f);
        placeInd(KEYPAD_FLAG|'3', viewW*0.54f, viewH*0.68f, u*1.1f);
    }
    // posiciona um botão do d-pad (centro absoluto derivado do grupo)
    private void placeAbs(int bit, float cx, float cy, float size){
        for(Btn b: btns) if(b.bit==bit){ b.rect.set(cx-size/2, cy-size/2, cx+size/2, cy+size/2); return; }
    }
    // posiciona um botão individual: centro custom (cxFrac) ou default; tamanho = base * escala do botão
    private void placeInd(int bit, float defCx, float defCy, float baseSize){
        for(Btn b: btns) if(b.bit==bit){
            float size = baseSize * b.scale;
            float cx = b.cxFrac!=null ? b.cxFrac*viewW : defCx;
            float cy = b.cyFrac!=null ? b.cyFrac*viewH : defCy;
            b.rect.set(cx-size/2, cy-size/2, cx+size/2, cy+size/2);
            return;
        }
    }

    public void draw(Canvas c, Screen s, boolean menuDown, boolean editMode){
        for(Btn x: btns){
            if(!x.visible) continue;
            boolean down;
            if(x.bit==MENU) down = menuDown;
            else if((x.bit & KEYPAD_FLAG)!=0) down = s.keypadPressed(x.bit & 0xFF);
            else down = (s.getButtons() & x.bit)!=0;
            boolean isMenu = (x.bit==MENU);
            // botao de opcoes ocioso: fundo OPACO + borda accent -> sempre flutua visivel sobre o jogo
            Paint p = editMode ? edit : (down ? fillDown : (isMenu ? fillMenu : fill));
            Paint st = (isMenu && !editMode && !down) ? strokeMenu : stroke;
            if(x.round){
                float rad = Math.min(x.rect.width(), x.rect.height())/2f, mx=x.rect.centerX(), my=x.rect.centerY();
                c.drawCircle(mx,my,rad,p); c.drawCircle(mx,my,rad,st);
            } else {
                float r = x.rect.height()*0.18f;
                c.drawRoundRect(x.rect, r, r, p); c.drawRoundRect(x.rect, r, r, st);
            }
            // tamanho do texto AUTO-AJUSTADO ao rotulo: rotulos longos ("SELECT") encolhem p/ caber no botao
            float diam = Math.min(x.rect.width(), x.rect.height());
            float base = Math.min(viewW, viewH) * 0.055f;                 // ~ u*0.5
            float fit  = 1.5f * diam / Math.max(1, x.label.length());     // largura ~ len*0.6*ts <= diam
            text.setTextSize(Math.max(diam*0.22f, Math.min(base, fit)));
            c.drawText(x.label, x.rect.centerX(), x.rect.centerY() + text.getTextSize()*0.35f, text);
        }
    }

    /** bit do botão sob (x,y), ou 0 se nenhum. (bit codificado KEYPAD_FLAG|ascii p/ teclas numéricas) */
    public int hitTest(float x, float y){
        for(Btn b: btns) if(b.visible && b.rect.contains(x,y)) return b.bit;
        return 0;
    }
    /** o Btn sob (x,y) (p/ arrastar no modo edição), ou null. */
    public Btn hitButton(float x, float y){
        for(Btn b: btns) if(b.visible && b.rect.contains(x,y)) return b;
        return null;
    }
    /** move um botão (ou o GRUPO d-pad inteiro) para que ele fique sob (cx,cy). */
    public void moveButton(Btn b, float cx, float cy){
        if(isDpad(b.bit)){
            // move o grupo mantendo a seta arrastada SOB o dedo (offset dela em relação ao centro)
            float du = Math.min(viewW,viewH)*0.11f*dpadScale;
            float ox = b.bit==Screen.LEFT ? -du : b.bit==Screen.RIGHT ? du : 0f;
            float oy = b.bit==Screen.UP   ? -du : b.bit==Screen.DOWN  ? du : 0f;
            dpadFracX = clampf((cx-ox)/viewW); dpadFracY = clampf((cy-oy)/viewH);
        } else {
            b.cxFrac = clampf(cx/viewW); b.cyFrac = clampf(cy/viewH);
        }
        recompute();
    }

    /** escala atual do alvo (grupo d-pad ou botão individual) — base p/ a pinça. */
    public float startScaleOf(Btn b){ return isDpad(b.bit) ? dpadScale : b.scale; }
    /** define a escala do alvo (d-pad = grupo todo; senão = só aquele botão). */
    public void setScaleOf(Btn b, float s){
        s = Math.max(0.5f, Math.min(2.5f, s));
        if(isDpad(b.bit)) dpadScale = s; else b.scale = s;
        recompute();
    }

    public void resetLayout(){
        for(Btn b: btns){ b.cxFrac=null; b.cyFrac=null; b.scale=1f; }
        dpadFracX=-1f; dpadFracY=-1f; dpadScale=1f;
        recompute();
    }

    // serializa: "D:dx,dy,dscale|bit:cx,cy,scale|..." (grupo d-pad + botões individuais alterados)
    public String exportLayout(){
        StringBuilder sb = new StringBuilder("D:").append(dpadFracX).append(',').append(dpadFracY).append(',').append(dpadScale);
        for(Btn b: btns){
            if(isDpad(b.bit)) continue;
            boolean moved = b.cxFrac!=null && b.cyFrac!=null, scaled = b.scale!=1f;
            if(moved || scaled){
                float cx = b.cxFrac!=null?b.cxFrac:-1f, cy = b.cyFrac!=null?b.cyFrac:-1f;
                sb.append('|').append(b.bit).append(':').append(cx).append(',').append(cy).append(',').append(b.scale);
            }
        }
        return sb.toString();
    }
    public void importLayout(String s){
        resetLayout();
        if(s==null || s.isEmpty()) return;
        try {
            for(String part : s.split("\\|")){
                if(part.startsWith("D:")){
                    String[] v = part.substring(2).split(",");
                    dpadFracX=Float.parseFloat(v[0]); dpadFracY=Float.parseFloat(v[1]); dpadScale=Float.parseFloat(v[2]);
                } else {
                    String[] kv = part.split(":");
                    int bit = Integer.parseInt(kv[0]);
                    String[] v = kv[1].split(",");
                    float cx=Float.parseFloat(v[0]), cy=Float.parseFloat(v[1]);
                    float sc = v.length>2 ? Float.parseFloat(v[2]) : 1f;
                    for(Btn b: btns) if(b.bit==bit){ if(cx>=0)b.cxFrac=cx; if(cy>=0)b.cyFrac=cy; b.scale=sc; }
                }
            }
        } catch(Exception e){ resetLayout(); }
        recompute();
    }
}
