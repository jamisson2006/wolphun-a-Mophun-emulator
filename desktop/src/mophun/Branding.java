package mophun;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Identidade do aplicativo (nome, versao, autor) + carregamento do icone.
 *
 * O emulador se chama "wolphun emulator", da Maned Software (referencia ao
 * lobo-guara / maned wolf, mascote do autor). O logo (assets/wolphun.png) e
 * carregado como recurso do classpath (out/wolphun.png, copiado pelo build)
 * com fallback para o arquivo em disco, entao o icone aparece rodando de
 * qualquer diretorio.
 */
public final class Branding {
    private Branding(){}

    public static final String APP_NAME = "wolphun emulator";
    public static final String VERSION  = "0.3.0";          // 3a beta: teclas de aparelho (SE 202-217), presets de teclado,
                                                        // som vBeep, legibilidade dos temas, paridade com o Android
    public static final String CHANNEL  = "beta";            // canal de lancamento
    public static final String VENDOR   = "Maned Software";  // "estudio" (mascote: lobo-guara/maned wolf)
    public static final String AUTHOR   = "Jamisson Tavares";
    public static final String YEAR     = "2026";
    public static final String URL      = "youtube.com/@jamissontavares7671";
    /** Apoio ao projeto. O QR (assets/kofi-qr.png) aponta para esta mesma URL, entao
        quem estiver no celular escaneia e quem estiver no PC clica -- ver kofiQr(). */
    public static final String KOFI     = "https://ko-fi.com/jamissontavares";

    /** "wolphun emulator 0.1.0 beta" */
    public static String versionLine(){ return APP_NAME + " " + VERSION + " " + CHANNEL; }

    private static Image full;        // logo em tamanho cheio (carregado 1x)
    private static Image kofi;        // QR do Ko-fi (carregado 1x)
    private static boolean kofiTentado;
    private static List<Image> multi; // varios tamanhos p/ setIconImages

    /** O logo em tamanho cheio, ou null se nao encontrado. */
    public static synchronized Image logo(){
        if(full != null) return full;
        // 1) recurso no classpath (out/wolphun.png) -> funciona de qualquer cwd
        try {
            InputStream in = Branding.class.getResourceAsStream("/wolphun.png");
            if(in != null){ full = javax.imageio.ImageIO.read(in); in.close(); if(full!=null) return full; }
        } catch(Throwable t){}
        // 2) arquivo em disco (rodando da raiz do projeto, ou dev)
        String[] paths = { "assets/wolphun.png", "wolphun.png", "../assets/wolphun.png" };
        for(String p : paths){
            try { File f = new File(p); if(f.isFile()){ full = javax.imageio.ImageIO.read(f); if(full!=null) return full; } }
            catch(Throwable t){}
        }
        return null;
    }

    /** O QR do Ko-fi, ou null se nao encontrado. Mesma busca em dois passos do logo:
        recurso do classpath primeiro (funciona de qualquer diretorio, e e o caminho do
        .exe empacotado), arquivo em disco depois (rodando da raiz do projeto).
        kofiTentado evita repetir a busca em disco a cada abertura do "Sobre" quando o
        arquivo realmente nao existe -- o logo nao tem essa guarda e insiste a toa. */
    public static synchronized Image kofiQr(){
        if(kofi != null || kofiTentado) return kofi;
        kofiTentado = true;
        try {
            InputStream in = Branding.class.getResourceAsStream("/kofi-qr.png");
            if(in != null){ kofi = javax.imageio.ImageIO.read(in); in.close(); if(kofi!=null) return kofi; }
        } catch(Throwable t){}
        String[] paths = { "assets/kofi-qr.png", "kofi-qr.png", "../assets/kofi-qr.png" };
        for(String s : paths){
            try { File f = new File(s); if(f.isFile()){ kofi = javax.imageio.ImageIO.read(f); if(kofi!=null) return kofi; } }
            catch(Throwable t){}
        }
        return null;
    }

    /** Icones em varios tamanhos (16..256) para a janela; lista vazia se sem logo. */
    public static synchronized List<Image> icons(){
        if(multi != null) return multi;
        multi = new ArrayList<Image>();
        Image src = logo();
        if(src == null) return multi;
        int[] sizes = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };
        for(int s : sizes){
            BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = b.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, s, s, null);
            g.dispose();
            multi.add(b);
        }
        return multi;
    }

    /** Aplica o icone (varios tamanhos) a uma janela; no-op se sem logo. */
    public static void applyIcon(java.awt.Window w){
        List<Image> ic = icons();
        if(!ic.isEmpty()){
            try { w.setIconImages(ic); }
            catch(Throwable t){ Image l = logo(); if(l != null) try { ((java.awt.Frame)w).setIconImage(l); } catch(Throwable t2){} }
        }
    }
}
