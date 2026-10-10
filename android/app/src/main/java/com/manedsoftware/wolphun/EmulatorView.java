package com.manedsoftware.wolphun;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import wolphun.Screen;

/**
 * Superfície do emulador. Tem um RENDER LOOP próprio (thread) que lê o framebuffer
 * do Screen ~60fps e faz o blit escalonado (letterbox, nearest) + desenha o teclado
 * virtual por cima. Isso desacopla o desenho do vFlipScreen do jogo — resolve jogos
 * (como o HC2) cujo loop de menu para de flipar. O toque alimenta os bits de botão.
 */
public final class EmulatorView extends SurfaceView implements SurfaceHolder.Callback {

    public interface MenuListener { void onMenu(); }
    /** recebe o bitmap da captura (ou null se falhou). Chamado na THREAD DE RENDER. */
    public interface CaptureCallback { void onCaptured(Bitmap bmp); }

    private Screen scr;
    private Bitmap frame;
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final Paint blit = new Paint();
    private final VirtualKeypad keypad = new VirtualKeypad();
    private final SparseIntArray pointerBit = new SparseIntArray();
    private volatile boolean surfaceReady = false;
    private volatile boolean menuDown = false;
    private MenuListener menuListener;

    private Thread renderThread;
    private volatile boolean rendering = false;
    private int[] pixelBuf;
    private int diag = 0;

    // ---- captura de tela (screenshot) ----
    private volatile CaptureCallback capCb;        // != null = ha uma captura pedida
    private volatile boolean capControls;          // incluir o teclado virtual na imagem
    private volatile boolean everPresented;        // ja veio pelo menos um frame do jogo

    // ---- modo de edição do teclado (mover/redimensionar) ----
    private volatile boolean editMode = false;
    private VirtualKeypad.Btn dragBtn;
    private float pinchStartDist, pinchStartScale;
    public void setEditMode(boolean on){ editMode = on; }
    public boolean isEditMode(){ return editMode; }
    public void importKeypad(String s){ keypad.importLayout(s); }
    public String exportKeypad(){ return keypad.exportLayout(); }
    public void resetKeypad(){ keypad.resetLayout(); }
    public void setPhoneKeys(java.util.Set<Integer> codes){ keypad.setPhoneKeys(codes); postInvalidate(); }
    public void hidePhoneKeys(){ keypad.hidePhoneKeys(); postInvalidate(); }
    public void setWalkKeys(boolean on){ keypad.setWalkKeys(on); postInvalidate(); }
    public void setLayoutPreset(int p){ keypad.setLayoutPreset(p); postInvalidate(); }
    /** mostra/oculta os botões de tecla numérica 2/3 (por jogo — ex.: Bomb Jack). */
    public void setShowNumbers(boolean on){ keypad.setShowNumbers(on); }
    /** mostra/oculta o botão ≡ desenhado no canvas (usamos um Button real na Activity). */
    public void setCanvasMenuVisible(boolean on){ keypad.setMenuVisible(on); }

    /** copia reduzida do ultimo frame renderizado (p/ miniatura/icone). null se ainda nao renderizou. */
    public Bitmap snapshot(int maxSide){
        Bitmap f = frame;
        if(f==null || f.isRecycled()) return null;
        try {
            int w=f.getWidth(), h=f.getHeight();
            float s = Math.min(1f, (float)maxSide/Math.max(w,h));
            int nw=Math.max(1,Math.round(w*s)), nh=Math.max(1,Math.round(h*s));
            return Bitmap.createScaledBitmap(f, nw, nh, true);
        } catch(Throwable t){ return null; }
    }

    /**
     * Pede uma captura de tela NA RESOLUCAO DO APARELHO (nao na do jogo).
     * O frame e composto do mesmo jeito que vai para a tela: o framebuffer do jogo
     * escalonado para os pixels da superficie, com a mesma proporcao e o mesmo filtro.
     *
     * @param withControls true = tela cheia como o usuario ve (letterbox + teclado virtual);
     *                     false = so a area do jogo, recortada (sem barras e sem botoes).
     * O callback vem na thread de render -> NAO comprimir/gravar dentro dele.
     */
    public void requestCapture(boolean withControls, CaptureCallback cb){
        if(cb == null) return;
        capControls = withControls;
        capCb = cb;
    }

    public EmulatorView(Context ctx){
        super(ctx);
        blit.setFilterBitmap(false);
        blit.setDither(false);
        getHolder().addCallback(this);
        setFocusable(true);
    }

    public void attach(Screen s){ this.scr = s; }
    public void setMenuListener(MenuListener l){ this.menuListener = l; }

    // ---- SurfaceHolder ----
    @Override public void surfaceCreated(SurfaceHolder h){ surfaceReady = true; startRender(); }
    @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hh){ keypad.layout(w, hh); }
    @Override public void surfaceDestroyed(SurfaceHolder h){ surfaceReady = false; stopRender(); }

    // ---- render loop (thread própria) ----
    private void startRender(){
        if(rendering) return;
        rendering = true;
        renderThread = new Thread(new Runnable(){ public void run(){ renderLoop(); } }, "wolphun-render");
        renderThread.start();
    }
    private void stopRender(){
        rendering = false;
        Thread t = renderThread;
        if(t!=null) t.interrupt();
        renderThread = null;
    }

    private void renderLoop(){
        while(rendering){
            long t0 = System.nanoTime();
            try {
                Screen s = scr;
                if(surfaceReady && s != null) drawFrame(s);
            } catch(Throwable ignore){ /* surface transitória */ }
            long sleep = 16_000_000L - (System.nanoTime()-t0);
            if(sleep > 1_000_000L){
                try { Thread.sleep(sleep/1_000_000L); }
                catch(InterruptedException e){ break; }
            }
        }
    }

    private void drawFrame(Screen s){
        int w = s.pw, h = s.ph;              // dimensoes FISICAS (supersampling HD); a razao de aspecto e a mesma da logica
        if(frame==null || frame.getWidth()!=w || frame.getHeight()!=h){
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            everPresented = false;
            src.set(0,0,w,h);
            pixelBuf = new int[w*h];
        }
        blit.setFilterBitmap(s.rs>1);                     // downscale do buffer HD -> bilinear (anti-aliasing)
        boolean has = s.copyPresented(pixelBuf);          // frame COMPLETO (sem tearing)
        if(has){ frame.setPixels(pixelBuf, 0, w, 0, 0, w, h); everPresented = true; }

        SurfaceHolder holder = getHolder();
        Canvas c = holder.lockCanvas();
        if(c==null) return;
        final int vw = c.getWidth(), vh = c.getHeight();   // pixels REAIS do aparelho
        try {
            c.drawColor(Color.BLACK);
            if(has){
                computeDst(vw, vh, w, h);
                c.drawBitmap(frame, src, dst, blit);
            }
            keypad.draw(c, s, menuDown, editMode);
        } finally {
            holder.unlockCanvasAndPost(c);
        }

        // Captura pedida pelo menu: feita AQUI, nesta thread, com o frame que acabou de ir
        // para a tela -- e por isso que a imagem nunca sai meio escrita nem com tearing.
        CaptureCallback cb = capCb;
        if(cb != null){ capCb = null; doCapture(s, cb, vw, vh); }

        if(diag < 4){ diag++; Log.d("wolphun", "render flips="+s.flips+" has="+has+" w="+w+" h="+h); }
    }

    /** Compoe a imagem da captura. Mesma matematica do blit da tela: o tamanho sai dos
        pixels da superficie (vw x vh), nunca de s.pw/s.ph (que e a resolucao do JOGO). */
    private void doCapture(Screen s, CaptureCallback cb, int vw, int vh){
        Bitmap out = null;
        try {
            Bitmap f = frame;
            if(everPresented && f != null && !f.isRecycled() && vw > 0 && vh > 0){
                computeDst(vw, vh, f.getWidth(), f.getHeight());
                final Rect area = new Rect(dst);        // onde o jogo esta na tela
                if(capControls){
                    out = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
                    Canvas cc = new Canvas(out);
                    cc.drawColor(Color.BLACK);
                    cc.drawBitmap(f, src, area, blit);
                    keypad.draw(cc, s, false, false);   // sem realce de tecla pressionada
                } else {
                    final int w = Math.max(1, area.width()), h = Math.max(1, area.height());
                    out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas cc = new Canvas(out);
                    cc.drawBitmap(f, src, new Rect(0, 0, w, h), blit);
                }
            }
        } catch(Throwable t){ out = null; }             // sem memoria p/ o bitmap, p.ex.
        try { cb.onCaptured(out); } catch(Throwable ignore){}
    }

    // letterbox preservando proporção. Em RETRATO joga a tela do jogo p/ CIMA e reserva
    // ~34% embaixo p/ os controles (assim o jogo não fica atrás dos botões).
    private void computeDst(int vw, int vh, int w, int h){
        boolean portrait = vh > vw;
        int gameH = portrait ? Math.round(vh * screenTopFraction) : vh;
        float s = Math.min((float)vw/w, (float)gameH/h);
        int dw = Math.round(w*s), dh = Math.round(h*s);
        int ox = (vw-dw)/2;
        int oy = portrait ? 0 : (vh-dh)/2;   // retrato: alinhado ao topo; paisagem: centralizado
        dst.set(ox, oy, ox+dw, oy+dh);
    }
    // fração da altura (em retrato) reservada para a tela do jogo; o resto é a zona de controles.
    private volatile float screenTopFraction = 0.66f;
    public void setScreenTopFraction(float f){ screenTopFraction = Math.max(0.4f, Math.min(1.0f, f)); }

    // ---- input (multitoque) ----
    @Override public boolean onTouchEvent(MotionEvent e){
        if(scr==null) return true;
        if(editMode) return onEditTouch(e);
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                apply(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for(int i=0;i<e.getPointerCount();i++)
                    apply(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = e.getActionIndex();
                release(e.getPointerId(idx));
                break;
            }
        }
        return true;
    }

    // --- toque no MODO EDIÇÃO: 1 dedo ARRASTA (d-pad move como GRUPO; demais individual);
    //     2 dedos (pinça) REDIMENSIONAM só o botão/grupo tocado ---
    private VirtualKeypad.Btn scaleTarget;
    private boolean onEditTouch(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                dragBtn = keypad.hitButton(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if(e.getPointerCount()==2){
                    float mx=(e.getX(0)+e.getX(1))/2f, my=(e.getY(0)+e.getY(1))/2f;
                    VirtualKeypad.Btn tgt = keypad.hitButton(mx,my);   // alvo = botão/grupo sob a pinça
                    if(tgt==null) tgt = dragBtn;
                    scaleTarget = tgt;
                    if(scaleTarget!=null){ pinchStartDist = dist(e); pinchStartScale = keypad.startScaleOf(scaleTarget); }
                    dragBtn = null;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()>=2 && scaleTarget!=null && pinchStartDist>0){
                    float d = dist(e); if(d>0) keypad.setScaleOf(scaleTarget, pinchStartScale * d/pinchStartDist);
                } else if(dragBtn!=null){
                    keypad.moveButton(dragBtn, e.getX(), e.getY());
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                pinchStartDist = 0; scaleTarget = null;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragBtn = null; pinchStartDist = 0; scaleTarget = null;
                break;
        }
        return true;
    }
    private static float dist(MotionEvent e){
        if(e.getPointerCount()<2) return 0;
        float dx=e.getX(0)-e.getX(1), dy=e.getY(0)-e.getY(1);
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    private void apply(int pid, float x, float y){
        int hit = keypad.hitTest(x, y);
        int prev = pointerBit.get(pid, 0);
        if(hit == prev) return;
        releaseCode(prev);                                   // solta o botão/tecla anterior
        if(hit==VirtualKeypad.MENU){
            menuDown=true; pointerBit.put(pid, hit);
            if(menuListener!=null) menuListener.onMenu();
        } else if((hit & VirtualKeypad.KEYPAD_FLAG)!=0){
            scr.setKeypad(hit & 0xFF, true); pointerBit.put(pid, hit);
        } else if(hit!=0){
            scr.setButtonBit(hit, true); pointerBit.put(pid, hit);
        } else {
            pointerBit.delete(pid);
        }
    }

    private void release(int pid){
        releaseCode(pointerBit.get(pid, 0));
        pointerBit.delete(pid);
    }

    // solta um "código" de botão: MENU, tecla numérica (KEYPAD_FLAG|ascii) ou bit mophun.
    // MENU=-1 é checado ANTES do KEYPAD_FLAG (pois -1 & KEYPAD_FLAG != 0).
    private void releaseCode(int code){
        if(code==VirtualKeypad.MENU) menuDown=false;
        else if((code & VirtualKeypad.KEYPAD_FLAG)!=0) scr.setKeypad(code & 0xFF, false);
        else if(code!=0) scr.setButtonBit(code, false);
    }
}
