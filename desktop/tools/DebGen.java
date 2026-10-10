import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Empacotador .deb em Java puro (sem dpkg-deb, sem WSL, sem ar/tar do sistema).
 * Permite gerar o pacote Debian/Ubuntu do wolphun a partir do Windows.
 *
 *   java -cp build DebGen <pasta-staging> <saida.deb>
 *
 * A pasta de staging e a raiz do sistema de arquivos do pacote:
 *   <staging>/DEBIAN/control          (obrigatorio; + postinst/prerm/... se quiser)
 *   <staging>/usr/...                 (o conteudo instalado)
 *
 * Um .deb e so um arquivo "ar" com 3 membros, nesta ordem:
 *   debian-binary ("2.0\n") | control.tar.gz | data.tar.gz
 * Escrevemos ar e tar (ustar) na mao — sao formatos curtos e estaveis.
 *
 * Permissoes (o Windows nao tem bit de execucao, entao vao por convencao):
 *   diretorios                        0755
 *   DEBIAN/postinst|preinst|prerm|postrm|config   0755
 *   qualquer arquivo em usr/bin, usr/sbin, usr/games ou *.sh   0755
 *   o resto                           0644
 */
public final class DebGen {

    public static void main(String[] args) throws Exception {
        if(args.length < 2){
            System.err.println("uso: DebGen <pasta-staging> <saida.deb>");
            System.exit(2);
        }
        File root = new File(args[0]);
        File out  = new File(args[1]);
        File debianDir = new File(root, "DEBIAN");
        if(!new File(debianDir, "control").isFile())
            throw new IOException("faltando " + new File(debianDir,"control"));

        // ---- 1) data.tar.gz : tudo menos DEBIAN/ ----
        List<File> dataFiles = new ArrayList<File>();
        collect(root, root, dataFiles, true);
        Collections.sort(dataFiles, BY_PATH);
        ByteArrayOutputStream dataRaw = new ByteArrayOutputStream();
        Tar data = new Tar(dataRaw);
        StringBuilder md5s = new StringBuilder();
        long instalado = 0;                                   // KiB, p/ o campo Installed-Size
        for(File f : dataFiles){
            String rel = rel(root, f);
            if(f.isDirectory()){ data.dir("./" + rel + "/", 0755); continue; }
            byte[] b = read(f);
            b = semCR(rel, b);
            data.file("./" + rel, b, modo(rel), f.lastModified()/1000L);
            md5s.append(md5(b)).append("  ").append(rel).append('\n');
            instalado += (b.length + 1023) / 1024;
        }
        data.finish();
        byte[] dataGz = gzip(dataRaw.toByteArray());

        // ---- 2) control.tar.gz : control + md5sums + scripts de manutencao ----
        String control = new String(read(new File(debianDir,"control")), "UTF-8").replace("\r\n","\n");
        if(!control.endsWith("\n")) control += "\n";
        if(control.indexOf("Installed-Size:") < 0)            // o instalador mostra o tamanho
            control = control.replaceFirst("(?m)^Description:", "Installed-Size: " + instalado + "\nDescription:");

        ByteArrayOutputStream ctlRaw = new ByteArrayOutputStream();
        Tar ctl = new Tar(ctlRaw);
        long agora = System.currentTimeMillis()/1000L;
        ctl.dir("./", 0755);
        ctl.file("./control", control.getBytes("UTF-8"), 0644, agora);
        ctl.file("./md5sums", md5s.toString().getBytes("UTF-8"), 0644, agora);
        File[] extras = debianDir.listFiles();
        if(extras != null){
            Arrays.sort(extras, BY_PATH);
            for(File f : extras){
                String n = f.getName();
                if(!f.isFile() || n.equals("control")) continue;
                ctl.file("./" + n, read(f), modo("DEBIAN/" + n), f.lastModified()/1000L);
            }
        }
        ctl.finish();
        byte[] ctlGz = gzip(ctlRaw.toByteArray());

        // ---- 3) o arquivo ar ----
        OutputStream o = new BufferedOutputStream(new FileOutputStream(out));
        o.write("!<arch>\n".getBytes("US-ASCII"));
        arMember(o, "debian-binary", "2.0\n".getBytes("US-ASCII"), agora);
        arMember(o, "control.tar.gz", ctlGz, agora);
        arMember(o, "data.tar.gz",    dataGz, agora);
        o.close();

        System.out.println("deb gerado: " + out.getPath()
            + "  (" + (out.length()/1024) + " KiB, instalado ~" + instalado + " KiB, "
            + dataFiles.size() + " entradas)");
    }

    // ------------------------------------------------------------------ ar

    private static void arMember(OutputStream o, String nome, byte[] dados, long mtime) throws IOException {
        StringBuilder h = new StringBuilder();
        pad(h, nome, 16); pad(h, Long.toString(mtime), 12);
        pad(h, "0", 6); pad(h, "0", 6); pad(h, "100644", 8);
        pad(h, Integer.toString(dados.length), 10);
        h.append("`\n");
        o.write(h.toString().getBytes("US-ASCII"));
        o.write(dados);
        if((dados.length & 1) != 0) o.write('\n');            // membros comecam em offset par
    }
    private static void pad(StringBuilder sb, String s, int n){
        sb.append(s);
        for(int i=s.length(); i<n; i++) sb.append(' ');
    }

    // ----------------------------------------------------------------- tar

    /** escritor tar formato ustar (POSIX 1003.1-1988) — o que o dpkg espera. */
    static final class Tar {
        private final OutputStream o;
        Tar(OutputStream o){ this.o = o; }

        void dir(String nome, int modo) throws IOException {
            header(nome, 0, modo, System.currentTimeMillis()/1000L, '5');
        }
        void file(String nome, byte[] b, int modo, long mtime) throws IOException {
            header(nome, b.length, modo, mtime, '0');
            o.write(b);
            int resto = b.length % 512;
            if(resto != 0) o.write(new byte[512 - resto]);    // blocos de 512
        }
        void finish() throws IOException { o.write(new byte[1024]); }   // 2 blocos zerados = fim

        private void header(String nome, int tam, int modo, long mtime, char tipo) throws IOException {
            byte[] h = new byte[512];
            if(nome.length() > 100) throw new IOException("caminho longo demais p/ ustar: " + nome);
            put(h, 0,   nome, 100);
            put(h, 100, oct(modo, 7), 8);
            put(h, 108, oct(0, 7), 8);                        // uid 0 = root
            put(h, 116, oct(0, 7), 8);                        // gid 0 = root
            put(h, 124, oct(tam, 11), 12);
            put(h, 136, oct(mtime, 11), 12);
            for(int i=148;i<156;i++) h[i] = ' ';              // checksum: espacos durante o calculo
            h[156] = (byte)tipo;
            put(h, 257, "ustar", 6); h[263]='0'; h[264]='0';  // magic + version "00"
            put(h, 265, "root", 32); put(h, 297, "root", 32);
            int soma = 0; for(int i=0;i<512;i++) soma += (h[i] & 0xff);
            put(h, 148, oct(soma, 6), 7); h[154] = 0; h[155] = ' ';
            o.write(h);
        }
        private static void put(byte[] h, int off, String s, int len){
            byte[] b;
            try { b = s.getBytes("UTF-8"); } catch(UnsupportedEncodingException e){ throw new RuntimeException(e); }
            System.arraycopy(b, 0, h, off, Math.min(b.length, len));
        }
        private static String oct(long v, int dig){
            String s = Long.toOctalString(v);
            while(s.length() < dig) s = "0" + s;
            return s;
        }
    }

    // ------------------------------------------------------------- utilidades

    private static final Comparator<File> BY_PATH = new Comparator<File>(){
        public int compare(File a, File b){ return a.getPath().replace('\\','/').compareTo(b.getPath().replace('\\','/')); }
    };

    private static void collect(File root, File dir, List<File> out, boolean raiz){
        File[] fs = dir.listFiles();
        if(fs == null) return;
        for(File f : fs){
            if(raiz && f.isDirectory() && f.getName().equals("DEBIAN")) continue;   // vai no control.tar.gz
            if(f.isDirectory()){ out.add(f); collect(root, f, out, false); }
            else out.add(f);
        }
    }

    private static String rel(File root, File f){
        String r = root.getAbsolutePath().replace('\\','/');
        String p = f.getAbsolutePath().replace('\\','/');
        if(!r.endsWith("/")) r += "/";
        return p.startsWith(r) ? p.substring(r.length()) : p;
    }

    /** o Windows nao guarda o bit de execucao; deduzimos pelo caminho. */
    private static int modo(String rel){
        String p = rel.replace('\\','/');
        if(p.startsWith("usr/bin/") || p.startsWith("usr/sbin/") || p.startsWith("usr/games/")) return 0755;
        if(p.endsWith(".sh")) return 0755;
        if(p.startsWith("DEBIAN/")){
            String n = p.substring(7);
            if(n.equals("postinst")||n.equals("preinst")||n.equals("prerm")||n.equals("postrm")||n.equals("config"))
                return 0755;
        }
        return 0644;
    }

    /**
     * Tira o CR de arquivos que o Linux le como TEXTO e quebram com CRLF: um script com
     * "#!/bin/sh\r" da "bad interpreter", e um .desktop com CRLF e recusado. So mexe nestes
     * caminhos (la nao mora binario), entao nao ha risco de corromper o .jar ou o .png.
     */
    private static byte[] semCR(String rel, byte[] b){
        String p = rel.replace('\\','/');
        boolean texto = p.startsWith("usr/bin/") || p.endsWith(".desktop") || p.endsWith(".sh");
        if(!texto) return b;
        int n = 0;
        for(int i=0;i<b.length;i++) if(!(b[i]=='\r' && i+1<b.length && b[i+1]=='\n')) b[n++] = b[i];
        if(n == b.length) return b;
        byte[] r = new byte[n];
        System.arraycopy(b, 0, r, 0, n);
        return r;
    }

    private static byte[] read(File f) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
        byte[] b = new byte[(int)f.length()];
        try { in.readFully(b); } finally { in.close(); }
        return b;
    }

    private static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(bo);
        g.write(b); g.finish(); g.close();
        return bo.toByteArray();
    }

    private static String md5(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(b);
        StringBuilder s = new StringBuilder();
        for(int i=0;i<d.length;i++){
            int v = d[i] & 0xff;
            if(v < 16) s.append('0');
            s.append(Integer.toHexString(v));
        }
        return s.toString();
    }
}
