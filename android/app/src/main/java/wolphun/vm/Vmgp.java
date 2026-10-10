package wolphun.vm;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Loader/parser de um executavel VMGP (.mpn) mophun ja DESCRIPTOGRAFADO.
 * Expoe as secoes (codigo, dados), a tabela de pool (relocacao/imports),
 * as strings (nomes de simbolos) e os recursos (sprites/tiles/mapas/etc).
 *
 * Layout: header(40) | code | data | resource | pool | string
 */
public final class Vmgp {
    /** heap que a ROM pede, alinhado a 16 (mesma formula do desktop). */
    public long heapBytesRequested(){ return (heapSize + 15L) & ~15L; }

    public static final int HEADER_SIZE = 40;

    // ---- campos do header ----
    public final long heapSize;      // em bytes (ja << 2)
    public final int  flags;
    public final long stackSize;     // em bytes (ja << 2)
    public final int  codeSize, dataSize, bssSize, resSize, poolCount, stringSize;

    // ---- secoes ----
    public final byte[] all;                 // arquivo inteiro
    public final int codeOff, dataOff, resOff, poolOff, strOff;

    public static final class PoolItem {
        public int type;        // 0=End 1=Local 2=Import 3=Global 4=SecRel 5=Sw16 6=Sw32 7=Const32 8=SymAdd
        public int itemTarget;  // 1=code 2=data 4=bss
        public int meta;        // offset de string (imports/globals) ou meta
        public int target;      // offset alvo
    }
    public final PoolItem[] pool;

    // ---- recursos ----
    private final int[] resStart;   // offset absoluto no arquivo
    private final int[] resLen;

    // VFS de ARQUIVO EMBUTIDO (ZIP): ex. Da Vinci Code embute assets num ZIP na secao de dados.
    private final HashMap<String,byte[]> vfs = new HashMap<String,byte[]>();
    private byte[] archiveZip = null;

    public Vmgp(byte[] d) {
        this.all = d;
        if (d.length < HEADER_SIZE || d[0]!='V'||d[1]!='M'||d[2]!='G'||d[3]!='P')
            throw new IllegalArgumentException("Not a valid VMGP file");
        heapSize  = (u32(d,4) << 2);
        flags     = u16(d,8);
        stackSize = ((long)u16(d,10)) << 2;
        codeSize  = (int)u32(d,12);
        dataSize  = (int)u32(d,16);
        bssSize   = (int)u32(d,20);
        resSize   = (int)u32(d,24);
        // u32(d,28) = unk
        poolCount = (int)u32(d,32);
        stringSize= (int)u32(d,36);

        codeOff = HEADER_SIZE;
        dataOff = codeOff + codeSize;
        resOff  = dataOff + dataSize;
        poolOff = resOff + resSize;
        strOff  = poolOff + poolCount * 8;

        // ROM "packed" (S60/PPC): code+data comprimidos com LZ; o campo @28 (0 em ROMs normais) guarda o
        // offset do fim de code+data comprimidos. Os tamanhos do header sao os DESCOMPRIMIDOS, entao poolOff
        // cai muito alem do arquivo -> a leitura do pool estourava com ArrayIndexOutOfBounds (ex.: Worms World
        // Party). Detecta e da erro claro (o parser nao descomprime; use a versao "_decompressed" do ROM).
        long u28 = u32(d,28);
        if (u28!=0 || (long)strOff + stringSize > d.length)
            throw new IllegalArgumentException("ROM comprimido (packed S60/PPC) nao suportado — use a versao descompactada do jogo (\"_decompressed\").");

        // pool
        pool = new PoolItem[poolCount];
        for (int i=0;i<poolCount;i++){
            long seg = u32(d, poolOff + i*8);
            long tgt = u32(d, poolOff + i*8 + 4);
            PoolItem p = new PoolItem();
            p.type = (int)(seg & 0xF);
            p.itemTarget = (int)((seg >> 4) & 0xF);
            p.meta = (int)(seg >> 8);
            p.target = (int)tgt;
            pool[i] = p;
        }

        // tabela de recursos: lista de offsets relativos a resOff, terminada por 0
        ArrayList<Integer> offs = new ArrayList<Integer>();
        int o = resOff;
        while (o+4 <= poolOff) {
            int v = (int)u32(d,o);
            if (v==0) break;
            offs.add(v); o+=4;
        }
        resStart = new int[offs.size()];
        resLen   = new int[offs.size()];
        for (int i=0;i<offs.size();i++){
            int s = resOff + offs.get(i);
            int e = (i+1<offs.size()) ? resOff+offs.get(i+1) : poolOff;
            resStart[i]=s; resLen[i]=e-s;
        }

        scanEmbeddedZip();
    }

    private void scanEmbeddedZip(){
        try {
            int lf = indexOf(all, new byte[]{0x50,0x4b,0x03,0x04}, 0);
            if (lf < 0) return;
            int eo = lastIndexOf(all, new byte[]{0x50,0x4b,0x05,0x06});
            if (eo < 0 || eo <= lf) return;
            int end = eo + 22 + u16(all, eo+20);
            if (end > all.length) end = all.length;
            byte[] zip = Arrays.copyOfRange(all, lf, end);
            HashMap<String,byte[]> m = new HashMap<String,byte[]>();
            ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip));
            ZipEntry e; byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null){
                if (e.isDirectory()){ zin.closeEntry(); continue; }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n; while ((n = zin.read(buf)) > 0) bos.write(buf,0,n);
                zin.closeEntry();
                m.put(normVfs(e.getName()), bos.toByteArray());
            }
            zin.close();
            if (!m.isEmpty()){ vfs.putAll(m); archiveZip = zip; }
        } catch (Throwable t){ }
    }
    static String normVfs(String n){
        if (n==null) return "";
        n = n.replace((char)92,'/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/"))  n = n.substring(1);
        return n.toLowerCase();
    }
    private static int indexOf(byte[] hay, byte[] needle, int from){
        outer: for (int i=Math.max(0,from); i+needle.length<=hay.length; i++){
            for (int j=0;j<needle.length;j++) if (hay[i+j]!=needle[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static int lastIndexOf(byte[] hay, byte[] needle){
        outer: for (int i=hay.length-needle.length; i>=0; i--){
            for (int j=0;j<needle.length;j++) if (hay[i+j]!=needle[j]) continue outer;
            return i;
        }
        return -1;
    }
    public boolean hasArchive(){ return archiveZip != null; }
    public byte[] archiveBytes(){ return archiveZip; }
    public byte[] vfsGet(String name){ return name==null? null : vfs.get(normVfs(name)); }
    public int vfsCount(){ return vfs.size(); }

    public static Vmgp load(String path) throws IOException {
        File f = new File(path);
        byte[] d = new byte[(int)f.length()];
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
        in.readFully(d); in.close();
        return new Vmgp(d);
    }

    public int resourceCount(){ return resStart.length; }
    public int resourceOffset(int i){ return resStart[i]; }
    public int resourceLength(int i){ return resLen[i]; }

    public String getString(int off){
        int p = strOff + off, e = p;
        while (e < all.length && all[e]!=0) e++;
        return new String(all, p, e-p);
    }

    // true se o executavel IMPORTA o simbolo 'name' (pool type 2 = Import; meta = offset da string).
    // Usado p/ detectar "geracao" do jogo (nofun: importa vInit3D => SDK >=1.50).
    public boolean importsSymbol(String name){
        for (PoolItem p : pool)
            if (p.type==2 && name.equals(getString(p.meta))) return true;
        return false;
    }

    static long u32(byte[] d,int o){
        return (d[o]&0xffL)|((d[o+1]&0xffL)<<8)|((d[o+2]&0xffL)<<16)|((d[o+3]&0xffL)<<24);
    }
    static int u16(byte[] d,int o){ return (d[o]&0xff)|((d[o+1]&0xff)<<8); }
}
