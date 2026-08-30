package mophun;

import java.io.*;
import java.util.*;

/**
 * Varre a pasta-raiz de ROMs. Convencao do arquivo mophun: cada jogo em uma
 * subpasta contendo um ou mais .mpn (as vezes variantes _rus/_cracked/_expired)
 * e screenshots (screen00.png/screen01.png). Tambem aceita .mpn soltos na raiz.
 */
public final class GameLibrary {

    public static final class Game implements Comparable<Game> {
        public final String name;         // nome de exibicao (nome da pasta ou do arquivo)
        public final File   mpn;          // ROM principal
        public final File   screenshot;   // miniatura (pode ser null)
        public final List<File> variants; // todas as .mpn da pasta (p/ escolher outra versao)
        Game(String name, File mpn, File shot, List<File> variants){
            this.name=name; this.mpn=mpn; this.screenshot=shot; this.variants=variants;
        }
        @Override public int compareTo(Game o){ return name.compareToIgnoreCase(o.name); }
        @Override public String toString(){ return name; }
    }

    /** lista os jogos sob 'root' (uma pasta por jogo + .mpn soltos na raiz). */
    public static List<Game> scan(File root){
        List<Game> out = new ArrayList<Game>();
        if(root==null || !root.isDirectory()) return out;
        File[] entries = root.listFiles();
        if(entries==null) return out;
        Arrays.sort(entries);
        for(File e: entries){
            if(e.isDirectory()){
                Game g = fromFolder(e);
                if(g!=null) out.add(g);
            } else if(isMpn(e)){
                out.add(new Game(stripExt(e.getName()), e, siblingShot(root, e), Collections.singletonList(e)));
            }
        }
        Collections.sort(out);
        return out;
    }

    static Game fromFolder(File dir){
        File[] files = dir.listFiles();
        if(files==null) return null;
        List<File> mpns = new ArrayList<File>();
        File shot = null;
        for(File f: files){
            if(isMpn(f)) mpns.add(f);
            else if(shot==null && isShot(f)) shot = f;
        }
        // 2a passada: prefere screen01 (costuma ser gameplay), senao qualquer screen*
        for(File f: files) if(isShot(f) && f.getName().toLowerCase().contains("screen01")){ shot=f; break; }
        if(mpns.isEmpty()) return null;
        Collections.sort(mpns);
        File primary = pickPrimary(mpns);
        return new Game(dir.getName(), primary, shot, mpns);
    }

    // escolhe a MELHOR variante para JOGAR: prioriza a descriptografada (roda), pois "_cracked"
    // = DRM removido. Entre iguais, evita _rus (variante de idioma). So cai no cifrado se nao
    // houver descriptografada (dai o launch avisa que provavelmente nao roda).
    static File pickPrimary(List<File> mpns){
        File decClean=null, decAny=null, encClean=null, encAny=null;
        for(File f: mpns){
            boolean enc = looksEncrypted(f);
            boolean rus = f.getName().toLowerCase().contains("_rus");
            if(!enc){ if(decAny==null) decAny=f; if(!rus && decClean==null) decClean=f; }
            else    { if(encAny==null) encAny=f; if(!rus && encClean==null) encClean=f; }
        }
        if(decClean!=null) return decClean;
        if(decAny!=null)   return decAny;
        if(encClean!=null) return encClean;
        return encAny!=null? encAny : mpns.get(0);
    }

    static File siblingShot(File dir, File mpn){
        File[] files = dir.listFiles();
        if(files==null) return null;
        for(File f: files) if(isShot(f)) return f;
        return null;
    }

    /**
     * Heuristica de DRM: o code section de um .mpn descriptografado tem muitos bytes
     * repetidos/zero (opcodes + imediatos), enquanto o cifrado (XTEA) parece ruido.
     * Se >190 valores de byte distintos numa janela de 4KB do codigo, provavelmente cifrado.
     */
    public static boolean looksEncrypted(File mpn){
        try {
            int want = (int)Math.min(mpn.length(), 40+4096);
            byte[] d = new byte[want];
            DataInputStream in = new DataInputStream(new FileInputStream(mpn));
            try { in.readFully(d); } finally { in.close(); }
            if(d.length<44 || d[0]!='V'||d[1]!='M'||d[2]!='G'||d[3]!='P') return false; // nao-VMGP: deixa a VM lidar
            int codeSize = (d[12]&0xff)|((d[13]&0xff)<<8)|((d[14]&0xff)<<16)|((d[15]&0xff)<<24);
            int start=40, len=Math.min(codeSize, d.length-40);
            if(len<=64) return false;
            boolean[] seen=new boolean[256]; int distinct=0;
            for(int i=0;i<len;i++){ int b=d[start+i]&0xff; if(!seen[b]){seen[b]=true; distinct++;} }
            return distinct>190;
        } catch(Exception e){ return false; }
    }

    /**
     * Detecta o modelo de aparelho pelo nome do arquivo (ex: [T300], [T610], [multiscreen]).
     * Jogos nao-multiscreen checam o "terminal" (CheckTerminal le o modelo via vGetCaps) e tem
     * assets fixos p/ aquela resolucao -> precisamos reportar o modelo/resolucao certos.
     * Devolve indice de SystemDeviceModel, ou -1 se multiscreen/desconhecido (usa default do usuario).
     */
    public static int detectModel(File mpn){
        String n = mpn.getName().toLowerCase();
        if(n.contains("multiscreen")) return -1;                 // adaptativo
        if(n.contains("t300")) return 0;
        if(n.contains("t310")) return 1;
        if(n.contains("t226")||n.contains("t230")) return 5;
        if(n.contains("t610")||n.contains("t616")||n.contains("t630")||n.contains("t6x0")) return 2;
        if(n.contains("p800")) return 4;
        if(n.contains("p900")) return 10;
        if(n.contains("7650")) return 3;
        if(n.contains("3650")) return 8;
        if(n.contains("6600")) return 11;
        if(n.contains("ngage")||n.contains("n-gage")||n.contains("n_gage")) return 7;
        if(n.contains("a920")) return 6;
        if(n.contains("a925")) return 12;
        if(n.contains("sx1")) return 13;
        if(n.contains("sendo")) return 15;
        return -1;                                               // desconhecido -> default do usuario
    }

    /**
     * Resolucao nativa do jogo, quando o modelo (detectModel) e desconhecido:
     *   1) tag [LxA] no nome do arquivo (ex.: "..._[176x208]_...");
     *   2) senao, dimensoes do screenshot irmao — so aceitas se baterem EXATAMENTE com
     *      uma resolucao de modelo conhecida (evita miniaturas/promos de tamanho aleatorio).
     * Devolve {largura,altura} ou null se indeterminado (dai usa-se o default do usuario).
     */
    public static int[] detectRes(File mpn){
        // 1) tag [LxA] no nome (explicita -> confiavel)
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\[(\\d{2,4})x(\\d{2,4})\\]", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(mpn.getName());
        if(m.find()){
            int w=Integer.parseInt(m.group(1)), h=Integer.parseInt(m.group(2));
            if(w>=48 && h>=48 && w<=1024 && h<=1024) return new int[]{w,h};
        }
        // 2) screenshot irmao (so se casar com uma resolucao de modelo conhecida)
        File dir = mpn.getParentFile();
        if(dir!=null){
            File[] files = dir.listFiles();
            if(files!=null) for(File f: files){
                if(!isShot(f)) continue;
                int[] wh = imageSize(f);
                if(wh!=null && Config.modelForRes(wh[0],wh[1])>=0) return wh;
            }
        }
        return null;
    }

    /** Le so o cabecalho da imagem p/ obter (largura,altura), sem decodificar tudo. null em erro. */
    static int[] imageSize(File img){
        try {
            javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(img);
            if(in==null) return null;
            try {
                java.util.Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(in);
                if(!it.hasNext()) return null;
                javax.imageio.ImageReader r = it.next();
                r.setInput(in);
                int[] wh = { r.getWidth(0), r.getHeight(0) };
                r.dispose();
                return wh;
            } finally { in.close(); }
        } catch(Exception e){ return null; }
    }

    static boolean isMpn(File f){ return f.isFile() && f.getName().toLowerCase().endsWith(".mpn"); }
    static boolean isShot(File f){
        if(!f.isFile()) return false;
        String n = f.getName().toLowerCase();
        return (n.endsWith(".png")||n.endsWith(".jpg")||n.endsWith(".gif")) && n.contains("screen");
    }
    static String stripExt(String n){ int i=n.lastIndexOf('.'); return i>0? n.substring(0,i): n; }
}
