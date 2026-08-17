package wolphun;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;

/**
 * Janela + framebuffer RGB (ARGB por pixel) + paleta de 256 cores (p/ pixels
 * indexados de sprites/tiles RGB332) + entrada de teclado + fonte do sistema.
 *
 * O modelo do mophun 8bpp: sprites/tiles guardam INDICES; fills/textos usam
 * cores diretas (RGB555, bit31) ou indices de paleta. Convertemos tudo p/ ARGB
 * no framebuffer (igual ao driver do nofun) -> transparencia e cores corretas.
 */
public final class Screen {
    public final int w, h, scale;
    public final int[] fb;               // framebuffer ARGB (0xFFrrggbb)
    public final int[] palette = new int[256];  // ARGB (paleta mutavel: RGB332 default + overrides do jogo p/ UI/indexado)
    public final int[] rgb332  = new int[256];  // ARGB RGB332 FIXO (cor direta de sprites/tiles format 7; nunca muda)
    private final BufferedImage img;
    private final int[] imgPixels;
    private JFrame frame;
    private Canvas canvas;
    public boolean headless;

    // mapeamento tecla-do-PC (KeyEvent.VK_*) -> bit de botao mophun. Configuravel pelo emulador.
    public java.util.Map<Integer,Integer> keyMap;

    // ---- fonte do sistema (substituta Java p/ vTextOut*/vSelectFont) ----
    private final BufferedImage fontScratch;
    private final Graphics2D fontG;
    private Font sysFont;
    private FontMetrics fm;

    // mascara de botoes (bits estilo mophun)
    // BITS REAIS do mophun (nofun KeyCode.cs). Softkeys = Select(0x20)/Fire2(0x100), NAO bits fantasma.
    public static final int UP=0x01, DOWN=0x02, LEFT=0x04, RIGHT=0x08,
        FIRE=0x10, SELECT=0x20, FIRE2=0x100;
    // aliases de compat: as softkeys agora sao Select/Fire2 (menu/OK e 2a acao)
    public static final int SOFT_L=SELECT, SOFT_R=FIRE2, KEY1=SELECT, KEY5=FIRE2;
    private volatile int buttons = 0;

    // janela de recorte (vSetClipWindow): [clipX0,clipX1) x [clipY0,clipY1). Default = tela toda.
    public int clipX0=0, clipY0=0, clipX1, clipY1;
    // frame pacing: vFlipScreen deve "bloquear" ao refresh do aparelho (igual ao nofun).
    public int targetFps = 60;
    private long lastFrameNanos = 0;
    // brilho/gamma de DISPLAY (a paleta em si e exata=SDK; isto so clareia a saida, imitando o
    // LCD dos aparelhos, que exibe as cores RGB332 mais vivas/claras que o valor cru).
    // 1.0 = linear (cores EXATAS do SDK). <1.0 = clareia. Este modelo PRESERVA O MATIZ:
    // aplica o gamma so ao canal MAXIMO e escala os outros na mesma proporcao -> a cor fica
    // mais clara mas com a MESMA tonalidade (terreno oliva->oliva-amarelo, e o ceu azul do HC2
    // continua AZUL, nao vira roxo como no gamma por-canal antigo).
    public double gamma = 1.0;
    private int[] gammaLut = null; private double gammaBuilt = -1;
    private void ensureGammaLut(){
        if(gammaLut==null || gammaBuilt!=gamma){
            gammaLut = new int[256];
            for(int i=0;i<256;i++) gammaLut[i] = (int)Math.round(Math.pow(i/255.0, gamma)*255);
            gammaBuilt = gamma;
        }
    }
    // clareia preservando matiz+saturacao: canal max m -> m2 (via gamma); demais canais escalam m2/m.
    private int applyGamma(int argb){
        int r=(argb>>16)&0xff, g=(argb>>8)&0xff, b=argb&0xff;
        int m = r>g ? (r>b?r:b) : (g>b?g:b);
        if(m==0) return 0xff000000;
        int m2 = gammaLut[m];
        int rr=(r*m2 + (m>>1))/m, gg=(g*m2 + (m>>1))/m, bb=(b*m2 + (m>>1))/m;
        return 0xff000000|(clamp8(rr)<<16)|(clamp8(gg)<<8)|clamp8(bb);
    }
    static int clamp8(int v){ return v>255?255:(v<0?0:v); }

    public Screen(int w,int h,int scale){ this(w,h,scale,false,false); }
    public Screen(int w,int h,int scale,boolean headless){ this(w,h,scale,headless,false); }

    // embedded=true: cria o Canvas (com input) mas NAO abre janela propria; o host (emulador)
    // insere getCanvas() na janela dele. headless=true: sem UI (modo de teste/snapshot).
    public Screen(int w,int h,int scale,boolean headless,boolean embedded){
        this.w=w; this.h=h; this.scale=scale; this.headless = headless && !embedded;
        clipX1=w; clipY1=h;
        fb = new int[w*h];
        img = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        imgPixels = ((DataBufferInt)img.getRaster().getDataBuffer()).getData();
        initRgb332Palette();

        fontScratch = new BufferedImage(256,64,BufferedImage.TYPE_INT_ARGB);
        fontG = fontScratch.createGraphics();
        fontG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        selectFont(11);
        keyMap = defaultKeyMap();

        if(this.headless){ frame=null; canvas=null; return; }

        canvas = new Canvas(){
            @Override public void paint(Graphics g){ render(g); }
            @Override public void update(Graphics g){ render(g); }
        };
        canvas.setPreferredSize(new Dimension(w*scale,h*scale));
        canvas.setFocusable(true);
        KeyAdapter k = new KeyAdapter(){
            @Override public void keyPressed(KeyEvent e){ setKey(e.getKeyCode(),true); }
            @Override public void keyReleased(KeyEvent e){ setKey(e.getKeyCode(),false); }
        };
        canvas.addKeyListener(k);

        if(embedded){ frame=null; return; }   // host cuida da janela

        frame = new JFrame("Mophun - JavaSE (PIP2)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(canvas);
        frame.setResizable(false);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.addKeyListener(k);
        canvas.requestFocus();
    }

    // mapeamento padrao: setas/WASD + numpad posicional (celular); Espaco/Enter/Num5=fogo;
    // Q/Shift/Num7=Select(softkey/menu); E/X/Num9=Fire2 (2a softkey). Bits REAIS do mophun.
    public static java.util.Map<Integer,Integer> defaultKeyMap(){
        java.util.Map<Integer,Integer> m = new java.util.HashMap<Integer,Integer>();
        m.put(KeyEvent.VK_UP, UP);     m.put(KeyEvent.VK_W, UP);    m.put(KeyEvent.VK_NUMPAD8, UP);
        m.put(KeyEvent.VK_DOWN, DOWN); m.put(KeyEvent.VK_S, DOWN);  m.put(KeyEvent.VK_NUMPAD2, DOWN);
        m.put(KeyEvent.VK_LEFT, LEFT); m.put(KeyEvent.VK_A, LEFT);  m.put(KeyEvent.VK_NUMPAD4, LEFT);
        m.put(KeyEvent.VK_RIGHT, RIGHT); m.put(KeyEvent.VK_D, RIGHT); m.put(KeyEvent.VK_NUMPAD6, RIGHT);
        m.put(KeyEvent.VK_SPACE, FIRE); m.put(KeyEvent.VK_ENTER, FIRE); m.put(KeyEvent.VK_CONTROL, FIRE); m.put(KeyEvent.VK_NUMPAD5, FIRE);
        m.put(KeyEvent.VK_Q, SELECT); m.put(KeyEvent.VK_NUMPAD0, SELECT);
        m.put(KeyEvent.VK_E, FIRE2);  m.put(KeyEvent.VK_X, FIRE2);     m.put(KeyEvent.VK_BACK_SPACE, FIRE2);
        return m;
    }

    private void setKey(int code, boolean down){
        java.util.Map<Integer,Integer> m = keyMap;
        if(m==null) return;
        Integer bit = m.get(code);
        if(bit==null) return;
        if(down) buttons |= bit;
        else     buttons &= ~bit;
    }

    // vSetClipWindow(x0,y0,x1,y1): regiao [x0,x1) x [y0,y1) (nofun: NRectangle(x0,y0,x1-x0,y1-y0))
    public void setClip(int x0,int y0,int x1,int y1){
        if(x0>x1){ int t=x0; x0=x1; x1=t; }
        if(y0>y1){ int t=y0; y0=y1; y1=t; }
        clipX0=Math.max(0,x0); clipY0=Math.max(0,y0);
        clipX1=Math.min(w,x1); clipY1=Math.min(h,y1);
    }
    public boolean inClip(int x,int y){ return x>=clipX0 && x<clipX1 && y>=clipY0 && y<clipY1; }

    // acesso p/ o host embutido (emulador): canvas p/ inserir na janela + input direto (on-screen)
    public Canvas getCanvas(){ return canvas; }
    public void setButtonBit(int bit, boolean down){ if(down) buttons|=bit; else buttons&=~bit; }
    public void clearButtons(){ buttons=0; }
    public boolean autoAdvance=false; public int flips=0; private long gbTick=0;
    public int getButtons(){
        int b = buttons;
        // headless: 1 pulso de FIRE (~flip 15) p/ selecionar "New game" e entrar no jogo,
        // depois DIREITA p/ andar/rolar o nivel e mostrar gameplay.
        if(autoAdvance){
            gbTick++;
            // entra no jogo (FIRE), depois JOGA: anda p/ direita, pula e atira periodicamente
            // (p/ progredir/morrer e disparar saves de highscore/resume durante testes)
            boolean die = System.getenv("AUTODIE")!=null;
            if(flips>=8 && flips<11) b|=FIRE;             // entra no jogo
            else if(die){
                // modo "morrer": anda p/ direita sem pular -> cai em buraco -> game over
                // (apos game over, pulsa FIRE p/ passar telas de score/entrada de iniciais)
                if(flips>=15 && flips<120) b|=RIGHT;
                else if(flips>=120 && (flips%6)<3) b|=FIRE;
            } else if(flips>=15){
                b|=RIGHT;                         // avanca
                if((flips%20)<3)  b|=UP;          // pula a cada ~20 frames
                if((flips%13)==0) b|=FIRE;        // atira
                if((flips%160)<10) b|=LEFT;       // ocasionalmente volta (variacao)
            }
        }
        return b;
    }

    private void initRgb332Palette(){
        for(int i=0;i<256;i++){
            int r=(i>>5)&7, g=(i>>2)&7, b=i&3;
            int c=0xff000000|(Math.round(r/7f*255)<<16)|(Math.round(g/7f*255)<<8)|Math.round(b/3f*255);
            palette[i]=c; rgb332[i]=c;   // rgb332 fica FIXO; palette pode ser sobrescrita pelo jogo
        }
    }
    public static int rgb555ToArgb(int rgb555){
        int r=(rgb555>>10)&31, g=(rgb555>>5)&31, b=rgb555&31;
        return 0xff000000|(Math.round(r/31f*255)<<16)|(Math.round(g/31f*255)<<8)|Math.round(b/31f*255);
    }
    public void setPaletteRgb555(int index,int rgb555){ palette[index&0xff]=rgb555ToArgb(rgb555); }
    // ARGB -> RGB555 (p/ vGetPaletteEntry)
    public static int argbToRgb555(int argb){
        int r=(argb>>16)&0xff, g=(argb>>8)&0xff, b=argb&0xff;
        return ((r>>3)<<10)|((g>>3)<<5)|(b>>3);
    }

    // ---- fonte ----
    public void selectFont(int px){
        if(px<6) px = 8 + px*2;         // preset pequeno -> px
        if(px>24) px = 24;
        sysFont = new Font(Font.SANS_SERIF, Font.PLAIN, px);
        fontG.setFont(sysFont);
        fm = fontG.getFontMetrics();
    }
    public int textWidth(String s){ return (s==null||s.isEmpty())?0:fm.stringWidth(s); }
    public int fontHeight(){ return fm.getHeight(); }

    // desenha texto no framebuffer em (x,y)=canto sup-esq, cor ARGB de frente.
    public void drawText(int x,int y,String s,int argbFg){
        if(s==null||s.isEmpty()) return;
        int tw = Math.min(fm.stringWidth(s)+2, fontScratch.getWidth());
        int th = Math.min(fm.getHeight()+2, fontScratch.getHeight());
        // limpa area do scratch
        java.awt.Composite oc = fontG.getComposite();
        fontG.setComposite(AlphaComposite.Clear); fontG.fillRect(0,0,tw,th);
        fontG.setComposite(AlphaComposite.SrcOver);
        fontG.setColor(new Color(argbFg, true));
        fontG.drawString(s, 1, 1+fm.getAscent());
        fontG.setComposite(oc);
        int[] sp = ((DataBufferInt)fontScratch.getRaster().getDataBuffer()).getData();
        for(int j=0;j<th;j++){
            int dy=y+j; if(dy<clipY0||dy>=clipY1) continue;
            for(int i=0;i<tw;i++){
                int dx=x+i; if(dx<clipX0||dx>=clipX1) continue;
                int p = sp[j*fontScratch.getWidth()+i];
                if((p>>>24) >= 0x80) fb[dy*w+dx] = 0xff000000 | (p & 0xffffff);
            }
        }
    }

    // ---- message box modal (vMsgBox/vMsgBoxU): desenha caixa + texto, espera OK, retorna 1 ----
    public int showMessageBox(String title, String msg){
        if(headless) return 1;                       // sem UI: OK imediato
        drawMsgBox(title, msg);
        present();
        try {
            long t0=System.currentTimeMillis();
            while(getButtons()!=0 && System.currentTimeMillis()-t0<3000){ present(); Thread.sleep(15); } // solta tecla anterior
            while((getButtons()&(FIRE|KEY1))==0){ present(); Thread.sleep(15); }                          // espera OK
            while(getButtons()!=0){ present(); Thread.sleep(15); }                                         // solta
        } catch(InterruptedException e){}
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

    // salva o framebuffer atual como PNG (util no modo headless).
    public void savePng(String path){
        try {
            BufferedImage out = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
            if(gamma!=1.0) ensureGammaLut();
            for(int i=0;i<fb.length;i++) out.setRGB(i%w, i/w, (gamma==1.0?fb[i]:applyGamma(fb[i])) & 0xffffff);
            javax.imageio.ImageIO.write(out, "png", new java.io.File(path));
            System.out.println("snapshot salvo: "+path);
        } catch(Exception e){ System.err.println("savePng falhou: "+e); }
    }

    public String flipSnapPrefix=null; private int flipNum=0;
    public void present(){
        flips++;
        if(flipSnapPrefix!=null && ++flipNum<=200) savePng(flipSnapPrefix+String.format("%02d",flipNum)+".png");
        if(headless) return;
        // frame pacing: vFlipScreen "bloqueia" ate completar 1/targetFps (evita FPS exagerado/crash)
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
        if(gamma==1.0){ System.arraycopy(fb, 0, imgPixels, 0, fb.length); }
        else { ensureGammaLut(); for(int i=0;i<fb.length;i++) imgPixels[i]=applyGamma(fb[i]); }
        Graphics g=canvas.getGraphics();
        if(g!=null){ render(g); g.dispose(); }
    }
    private void render(Graphics g){
        g.drawImage(img,0,0,w*scale,h*scale,null);
    }
}
