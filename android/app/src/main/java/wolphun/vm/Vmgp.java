package wolphun.vm;

import java.io.*;
import java.util.*;

/**
 * Loader/parser de um executavel VMGP (.mpn) mophun ja DESCRIPTOGRAFADO.
 * Expoe as secoes (codigo, dados), a tabela de pool (relocacao/imports),
 * as strings (nomes de simbolos) e os recursos (sprites/tiles/mapas/etc).
 *
 * Layout: header(40) | code | data | resource | pool | string
 */
public final class Vmgp {
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
    }

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
