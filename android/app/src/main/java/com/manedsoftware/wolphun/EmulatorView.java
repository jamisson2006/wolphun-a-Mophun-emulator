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

    // ---- modo de edição do teclado (mover/redimensionar) ----
    private volatile boolean editMode = false;
    private VirtualKeypad.Btn dragBtn;
    private float pinchStartDist, pinchStartScale;
    public void setEditMode(boolean on){ editMode = on; }
    public boolean isEditMode(){ return editMode; }
    public void importKeypad(String s){ keypad.importLayout(s); }
    public String exportKeypad(){ return keypad.exportLayout(); }
    public void resetKeypad(){ keypad.resetLayout(); }

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
        int w = s.w, h = s.h;
        if(frame==null || frame.getWidth()!=w || frame.getHeight()!=h){
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            src.set(0,0,w,h);
            pixelBuf = new int[w*h];
        }
        boolean has = s.copyPresented(pixelBuf);          // frame COMPLETO (sem tearing)
        if(has) frame.setPixels(pixelBuf, 0, w, 0, 0, w, h);

        SurfaceHolder holder = getHolder();
        Canvas c = holder.lockCanvas();
        if(c==null) return;
        try {
            c.drawColor(Color.BLACK);
            if(has){
                computeDst(c.getWidth(), c.getHeight(), w, h);
                c.drawBitmap(frame, src, dst, blit);
            }
            keypad.draw(c, s.getButtons(), menuDown, editMode);
        } finally {
            holder.unlockCanvasAndPost(c);
        }

        if(diag < 4){ diag++; Log.d("wolphun", "render flips="+s.flips+" has="+has+" w="+w+" h="+h); }
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

    // --- toque no MODO EDIÇÃO: 1 dedo ARRASTA o botão; 2 dedos (pinça) REDIMENSIONAM todos ---
    private boolean onEditTouch(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                dragBtn = keypad.hitButton(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if(e.getPointerCount()==2){ pinchStartDist = dist(e); pinchStartScale = keypad.getGlobalScale(); dragBtn = null; }
                break;
            case MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()>=2 && pinchStartDist>0){
                    float d = dist(e); if(d>0) keypad.setGlobalScale(pinchStartScale * d/pinchStartDist);
                } else if(dragBtn!=null){
                    keypad.moveButton(dragBtn, e.getX(), e.getY());
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                pinchStartDist = 0;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragBtn = null; pinchStartDist = 0;
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
        if(prev==VirtualKeypad.MENU) menuDown=false;
        else if(prev!=0) scr.setButtonBit(prev, false);
        if(hit==VirtualKeypad.MENU){
            menuDown=true; pointerBit.put(pid, hit);
            if(menuListener!=null) menuListener.onMenu();
        } else if(hit!=0){
            scr.setButtonBit(hit, true); pointerBit.put(pid, hit);
        } else {
            pointerBit.delete(pid);
        }
    }

    private void release(int pid){
        int prev = pointerBit.get(pid, 0);
        if(prev==VirtualKeypad.MENU) menuDown=false;
        else if(prev!=0) scr.setButtonBit(prev, false);
        pointerBit.delete(pid);
    }
}
