package mophun;

import java.io.*;
import java.math.BigInteger;
import java.util.*;

/**
 * Descriptografia de ROMs mophun (Sony Ericsson), portada do MophunDecrypt (JaGoTu):
 *  - so a secao de CODIGO e cifrada, palavra a palavra, com um XTEA de 32 bits (decryptblock);
 *  - a chave simetrica (4x u32) esta RSA-cifrada (expoente 3) no recurso META do proprio jogo:
 *      key = (metaNum^3 mod N)[0x14 : 0x14+16], onde metaNum = META[0:0x80] (little-endian);
 *  - N e um dos modulos RSA das chaves SE, embutidos abaixo (extraidos da motuxality.dll).
 *
 * Tudo em memoria; a copia descriptografada fica em cache (~/.mophunemu/decrypted/) e o
 * arquivo original NUNCA e alterado.
 */
public final class MophunCrypt {
    private static final long M = 0xFFFFFFFFL;
    private static final BigInteger THREE = BigInteger.valueOf(3);

    // Modulos RSA (128 bytes, ordem little-endian) das chaves SE. Cobrem todo o arquivo SE:
    // 0x1c1100 (maioria) e 0x1c1200 (usado pelo HoneyCave2, entre outros).
    private static final String[] MODULI_LE = {
        "fb95fc7cc45419fe736e0aa92600d24305be58faafc6bedde6019dc0f243debf" +
        "7fc57af0054666f0a216071e0c056ade365d432ecccd2bba19abb11af7923908" +
        "3d72adcdef0673968e36e00c7262582a2c5b2e66e51f0c0fb98bfc774dd1e3907" +
        "2453a2fba7f1abc93c3008d3587e5b38d7b1977d2cc35c5d045d153a58208de",
        "03f7ea71135168233aec66348f0cc4fcea31da87e40dff171324a9e327fc8180" +
        "1f356fdb2c93ff66adb1b482f33ea1aba4829d821be29ccdc0bcb161eb28c158" +
        "75f281b881cfb6a52d48aebed5d58b365a60e928c2d69fd3a66960b2296dd511" +
        "f6fdaadaf0134db56895fb218291c98b63ed136b1df48921269b5687ade024ef"
    };
    private static BigInteger[] moduli;
    static {
        moduli = new BigInteger[MODULI_LE.length];
        for(int i=0;i<MODULI_LE.length;i++) moduli[i] = leToBig(hex(MODULI_LE[i]));
    }

    // ---------------- API ----------------

    /**
     * Garante uma copia descriptografada de 'mpn' em cache e a devolve. Se ja e VMGP
     * descriptografado, devolve o proprio arquivo. Devolve null se nao houver chave (nao-SE).
     */
    public static File ensureDecrypted(File mpn) throws IOException {
        byte[] d = readAll(mpn);
        if(d.length<44 || d[0]!='V'||d[1]!='M'||d[2]!='G'||d[3]!='P') return mpn; // deixa a VM lidar
        if(!GameLibrary.looksEncrypted(mpn)) return mpn;                         // ja descriptografado
        File cached = cachedValid(mpn);
        if(cached!=null) return cached;                                         // reaproveita cache
        File cache = cacheFileFor(mpn);
        cache.getParentFile().mkdirs();
        byte[] dec = decrypt(d);
        if(dec==null) return null;
        writeAll(cache, dec);
        return cache;
    }

    /** caminho do cache descriptografado p/ esta ROM (pode nao existir ainda). */
    public static File cacheFileFor(File mpn){
        File cacheDir = new File(Config.appDir(), "decrypted");
        String tag = Integer.toHexString(mpn.getAbsolutePath().hashCode() & 0x7fffffff);
        return new File(cacheDir, safe(stripExt(mpn.getName()))+"_"+tag+".mpn");
    }
    /** cache valido (existe e >= mtime do original), ou null se ainda precisa descriptografar. */
    public static File cachedValid(File mpn){
        File c = cacheFileFor(mpn);
        return (c.isFile() && c.lastModified()>=mpn.lastModified() && c.length()>0) ? c : null;
    }
    /** Descriptografa 'mpn' e escreve em 'dest' (copia permanente ou substituir original). */
    public static boolean decryptTo(File mpn, File dest) throws IOException {
        byte[] dec = decrypt(readAll(mpn));
        if(dec==null) return false;
        File parent = dest.getParentFile();
        if(parent!=null && !parent.exists()) parent.mkdirs();
        writeAll(dest, dec);
        return true;
    }

    /** Descriptografa os bytes de um .mpn VMGP. Devolve novo array, ou null se sem chave/META. */
    public static byte[] decrypt(byte[] d){
        if(d.length<44 || d[0]!='V'||d[1]!='M'||d[2]!='G'||d[3]!='P') return null;
        int codeSize = u32(d,12), dataSize = u32(d,16);
        int HDR=40, resOff = HDR + codeSize + dataSize;
        byte[] meta = findMeta(d, resOff);
        if(meta==null) return null;
        int[] key = findKey(meta);
        if(key==null) return null;
        byte[] out = d.clone();
        int end = codeSize - (codeSize % 4);
        for(int i=0;i<end;i+=4){
            long w = u32le(out, HDR+i);
            putU32le(out, HDR+i, decryptblock(w, key));
        }
        return out;
    }

    /** true se ha um modulo RSA que valida o META deste ROM (ou seja, da pra descriptografar). */
    public static boolean hasKeyFor(File mpn){
        try {
            byte[] d = readAll(mpn);
            if(d.length<44 || d[0]!='V') return false;
            int codeSize=u32(d,12), dataSize=u32(d,16);
            byte[] meta = findMeta(d, 40+codeSize+dataSize);
            return meta!=null && findKey(meta)!=null;
        } catch(Exception e){ return false; }
    }

    // ---------------- interno ----------------

    static byte[] findMeta(byte[] d, int resOff){
        try {
            List<Integer> offs = new ArrayList<Integer>();
            int o = resOff;
            while(o+4<=d.length){ int v=u32(d,o); if(v==0) break; offs.add(v); o+=4; }
            for(int ro: offs){
                int s = resOff + ro;
                if(s+9+0x98<=d.length && d[s]=='M'&&d[s+1]=='E'&&d[s+2]=='T'&&d[s+3]=='A'){
                    byte[] meta = new byte[0x98];
                    System.arraycopy(d, s+9, meta, 0, 0x98);
                    return meta;
                }
            }
        } catch(Exception e){}
        return null;
    }

    // RSA: metaNum^3 mod N -> checa padding -> extrai 4x u32 da chave simetrica
    static int[] findKey(byte[] meta){
        BigInteger metaNum = leToBig(Arrays.copyOfRange(meta, 0, 0x80));
        for(BigInteger N: moduli){
            if(N.compareTo(metaNum)<=0) continue;
            byte[] rb = bigToLe(metaNum.modPow(THREE, N), 128);
            boolean pad=true;
            for(int i=0x26;i<0x80;i++){ int b=rb[i]&0xff; if(b!=0&&b!=1&&b!=0xff){ pad=false; break; } }
            if(!pad) continue;
            int[] key = new int[4];
            for(int k=0;k<4;k++) key[k] = u32(rb, 0x14+k*4);
            return key;
        }
        return null;
    }

    // XTEA de 32 bits desenrolado (decryptblock do MophunDecrypt/Source.cpp). key = 4x u32.
    static int decryptblock(long block, int[] key){
        long r0,r1,r2,r3,r4,r5,r6,r7,r8,r9,r10;
        r4 = block & M;
        r10 = key[0]&M; r8 = key[1]&M; r7 = key[2]&M; r9 = key[3]&M;
        r0 = (r4 << 16) & M; r5 = r0 >> 16;
        r0 = (r5 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r5 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r3 = (r0 + r5) & M;
        r1 = (r7 + 0x5400) & M; r0 = (r1 + 0xF) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r4 = ((r4 >> 16) - r1) & M;
        r0 = (r4 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r4 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r3 = (r0 + r4) & M;
        r1 = (r7 + 0xDA00) & M; r0 = (r1 + 0x56) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r5 = (r5 - r1) & M;
        r0 = (r5 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r5 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r3 = (r0 + r5) & M;
        r1 = (r9 + 0xDA00) & M; r0 = (r1 + 0x56) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r4 = (r4 - r1) & M;
        r0 = (r4 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r4 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r3 = (r0 + r4) & M;
        r1 = (r8 + 0x6000) & M; r0 = (r1 + 0x9D) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r6 = (r5 - r1) & M;
        r0 = (r6 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r6 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r1 = (r10 + 0x6000) & M; r3 = (r0 + r6) & M; r0 = (r1 + 0x9D) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r0 = (r10 + 0xE600) & M; r5 = (r4 - r1) & M; r1 = (r0 + 0xE4) & M;
        r0 = (r5 << 16) & M; r4 = (r1 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r5 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r2 = (r0 + r5) & M;  r1 = r2 ^ (r4 >> 16);
        r6 = (r6 - r1) & M;
        r0 = (r6 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r6 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r2 = (r0 + r6) & M;  r1 = r2 ^ (r4 >> 16);
        r4 = (r5 - r1) & M;
        r0 = (r4 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r4 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r3 = (r0 + r4) & M;
        r1 = (r9 + 0x6D00) & M; r0 = (r1 + 0x2B) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r6 = (r6 - r1) & M;
        r0 = (r6 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r6 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r3 = (r0 + r6) & M;
        r1 = (r8 + 0x6D00) & M; r0 = (r1 + 0x2B) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r5 = (r4 - r1) & M;
        r0 = (r7 + 0xF300) & M; r1 = (r0 + 0x72) & M; r4 = (r1 << 16) & M;
        r0 = (r5 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r5 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r2 = (r0 + r5) & M;  r1 = r2 ^ (r4 >> 16);
        r6 = (r6 - r1) & M;
        r0 = (r6 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r6 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r2 = (r0 + r6) & M;  r1 = r2 ^ (r4 >> 16);
        r4 = (r5 - r1) & M;
        r0 = (r4 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r4 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r1 = (r8 + 0x7900) & M; r3 = (r0 + r4) & M; r0 = (r1 + 0xB9) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r5 = (r6 - r1) & M;
        r0 = (r5 << 16) & M; r1 = r0 >> 16;
        r2 = (r1 << 4) & M;  r0 = (r2 << 16) & M; r3 = r0 >> 16;
        r1 = (r5 << 16) & M; r0 = r3 ^ (r1 >> 21);
        r1 = (r9 + 0x7900) & M; r3 = (r0 + r5) & M; r0 = (r1 + 0xB9) & M; r2 = (r0 << 16) & M; r1 = r3 ^ (r2 >> 16);
        r3 = (r4 - r1) & M;
        r0 = (r3 << 16) & M; r4 = r0 >> 16;
        r1 = (r4 << 4) & M;  r0 = (r1 << 16) & M; r2 = r0 >> 16;
        r1 = (r3 << 16) & M; r0 = r2 ^ (r1 >> 21);
        r3 = (r0 + r3) & M;
        r1 = (r10 << 16) & M; r0 = r3 ^ (r1 >> 16);
        r2 = (r5 - r0) & M;
        r1 = (r2 << 16) & M; r0 = r1 >> 16;
        r0 = r0 | ((r4 << 16) & M);
        return (int)(r0 & M);
    }

    // ---------------- utils ----------------
    static int  u32(byte[] d,int o){ return (d[o]&0xff)|((d[o+1]&0xff)<<8)|((d[o+2]&0xff)<<16)|((d[o+3]&0xff)<<24); }
    static long u32le(byte[] d,int o){ return u32(d,o) & 0xffffffffL; }
    static void putU32le(byte[] d,int o,long v){ d[o]=(byte)v; d[o+1]=(byte)(v>>8); d[o+2]=(byte)(v>>16); d[o+3]=(byte)(v>>24); }

    static BigInteger leToBig(byte[] le){
        byte[] be = new byte[le.length];
        for(int i=0;i<le.length;i++) be[i] = le[le.length-1-i];
        return new BigInteger(1, be);
    }
    static byte[] bigToLe(BigInteger v, int len){
        byte[] be = v.toByteArray();                 // big-endian, pode ter 0 a mais ou menos
        byte[] le = new byte[len];
        for(int i=0;i<be.length && i<len;i++) le[i] = be[be.length-1-i];   // reverte p/ LE
        return le;
    }
    static byte[] hex(String s){
        int n=s.length()/2; byte[] b=new byte[n];
        for(int i=0;i<n;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return b;
    }
    static byte[] readAll(File f) throws IOException {
        byte[] d = new byte[(int)f.length()];
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
        try { in.readFully(d); } finally { in.close(); }
        return d;
    }
    static void writeAll(File f, byte[] d) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(d); } finally { out.close(); }
    }
    static String stripExt(String n){ int i=n.lastIndexOf('.'); return i>0? n.substring(0,i): n; }
    static String safe(String n){ return n.replaceAll("[^A-Za-z0-9_.-]","_"); }
}
