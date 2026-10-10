package wolphun.vm;

/**
 * Desmontador PIP2. Espelha EXATAMENTE o decodificador de Pip2.executeOne() — se os dois
 * divergirem, o errado e este arquivo.
 *
 * Formato da instrucao: palavra de 32 bits little-endian.
 *   op = w & 0xff | d = (w>>8) & 0xff | s = (w>>16) & 0xff | t = (w>>24) & 0xff
 * d/s/t sao OFFSETS EM BYTES no banco de registradores (r32(o) = R[o>>2]), entao o numero do
 * registrador e offset>>2. Opcodes 0x50..0x66 consomem uma palavra EXTRA (imediato, info de
 * pool, ou alvo de desvio) — o tamanho da instrucao e 8 bytes nesses casos e 4 nos demais.
 *
 * Uso:
 *   java -cp build wolphun.vm.Disasm &lt;rom.mpn&gt; 0xENDERECO [numeroDeInstrucoes]
 *   java -cp build wolphun.vm.Disasm &lt;rom.mpn&gt; --xref vMapInit
 *
 * O modo --xref varre a secao de codigo inteira procurando CALLl para um import com esse nome e
 * desmonta o trecho ANTES de cada chamada — que e onde os argumentos sao montados.
 */
public final class Disasm {

    final Pip2 vm;
    public Disasm(Pip2 vm){ this.vm = vm; }

    /** nome legivel do registrador a partir do offset em bytes */
    static String reg(int off){
        switch(off){
            case Pip2.ZERO: return "zero";
            case Pip2.SP:   return "sp";
            case Pip2.RA:   return "ra";
            case Pip2.FP:   return "fp";
            case Pip2.P0:   return "p0";
            case Pip2.P1:   return "p1";
            case Pip2.P2:   return "p2";
            case Pip2.P3:   return "p3";
            case Pip2.R0:   return "r0";
            case Pip2.R1:   return "r1";
            case Pip2.PC:   return "pc";
            default:        return "r" + (off>>2);
        }
    }

    /** true se o opcode consome a palavra seguinte (imediato / info de pool / alvo de desvio) */
    public static boolean hasImm(int op){ return op>=0x4A && op<=0x66; }

    /** tamanho em bytes da instrucao neste endereco */
    public int size(int pc){ return hasImm(vm.read32(pc)&0xff) ? 8 : 4; }

    /** resolve a palavra de imediato/pool como o fetchImm() do interpretador */
    String imm(int w){
        if((w & 0x80000000)!=0) {
            int raw = (w & 0x7FFFFFFF) | ((w<<1) & 0x80000000);   // decodeRaw
            return String.format("0x%x", raw);
        }
        int idx = w - 1;
        if(idx>=0 && idx<vm.poolValue.length){
            String nm = vm.poolName[idx];
            String kind = vm.poolIsImport[idx] ? "import" : "pool";
            return String.format("0x%x <%s#%d%s>", (int)vm.poolValue[idx], kind, idx,
                                 nm!=null && !nm.isEmpty() ? " "+nm : "");
        }
        return "pool#" + idx + "(?)";
    }

    /** nome do import chamado por um CALLl neste endereco, ou null */
    public String importAt(int pc){
        if((vm.read32(pc)&0xff) != 0x5C) return null;
        int info = vm.read32(pc+4);
        if((info & 0x80000000)!=0) return null;
        int idx = info - 1;
        if(idx>=0 && idx<vm.poolValue.length && vm.poolIsImport[idx]) return vm.poolName[idx];
        return null;
    }

    /** desmonta UMA instrucao; devolve o texto (sem o endereco) */
    public String one(int pc){
        int w = vm.read32(pc);
        int op=w&0xff, d=(w>>>8)&0xff, s=(w>>>16)&0xff, t=(w>>>24)&0xff;
        int nx = vm.read32(pc+4);                       // palavra extra (se houver)
        String D=reg(d), S=reg(s), T=reg(t);
        switch(op){
            case 0x00: return "BREAKPOINT";
            case 0x01: return "NOP";
            case 0x02: return "ADD   "+D+" = "+S+" + "+T;
            case 0x03: return "AND   "+D+" = "+S+" & "+T;
            case 0x04: return "MUL   "+D+" = "+S+" * "+T;
            case 0x05: return "DIV   "+D+" = "+S+" / "+T;
            case 0x06: return "DIVU  "+D+" = "+S+" /u "+T;
            case 0x07: return "OR    "+D+" = "+S+" | "+T;
            case 0x08: return "XOR   "+D+" = "+S+" ^ "+T;
            case 0x09: return "SUB   "+D+" = "+S+" - "+T;
            case 0x0A: return "SLL   "+D+" = "+S+" << "+T;
            case 0x0B: return "SRA   "+D+" = "+S+" >> "+T;
            case 0x0C: return "SRL   "+D+" = "+S+" >>> "+T;
            case 0x0D: return "NOT   "+D+" = ~"+S;
            case 0x0E: return "NEG   "+D+" = -"+S;
            case 0x0F: return "EXSB  "+D+" = (s8)"+S;
            case 0x10: return "EXSH  "+D+" = (s16)"+S;
            case 0x11: return "MOV   "+D+" = "+S;
            case 0x12: return "ADDB  "+D+".b = "+S+".b + "+T+".b";
            case 0x13: return "SUBB  "+D+".b = "+S+".b - "+T+".b";
            case 0x14: return "ANDB  "+D+".b = "+S+".b & "+T+".b";
            case 0x15: return "ORB   "+D+".b = "+S+".b | "+T+".b";
            case 0x16: return "MOVB  "+D+".b = "+S+".b";
            case 0x17: return "ADDH  "+D+".h = "+S+".h + "+T+".h";
            case 0x18: return "SUBH  "+D+".h = "+S+".h - "+T+".h";
            case 0x19: return "ANDH  "+D+".h = "+S+".h & "+T+".h";
            case 0x1A: return "ORH   "+D+".h = "+S+".h | "+T+".h";
            case 0x1B: return "MOVH  "+D+".h = "+S+".h";
            case 0x1C: return "SLLi  "+D+" = "+S+" << "+t;
            case 0x1D: return "SRAi  "+D+" = "+S+" >> "+t;
            case 0x1E: return "SRLi  "+D+" = "+S+" >>> "+t;
            case 0x1F: return "ADDQ  "+D+" = "+S+" + "+(byte)t;
            case 0x20: return "MULQ  "+D+" = "+S+" * "+t;
            case 0x21: return "ADDBi "+D+".b = "+S+".b + "+t;
            case 0x22: return "ANDBi "+D+".b = "+S+".b & "+t;
            case 0x23: return "ORBi  "+D+".b = "+S+".b | "+t;
            case 0x24: return "SLLB  "+D+".b = "+S+".b << "+t;
            case 0x25: return "SRLB  "+D+".b = "+S+".b >>> "+t;
            case 0x26: return "SRAB  "+D+".b = "+S+".b >> "+t;
            case 0x27: return "ADDHi "+D+".h = "+S+".h + "+(byte)t;
            case 0x28: return "ANDHi "+D+".h = "+S+".h & "+t;
            case 0x29: return "SLLH  "+D+".h = "+S+".h << "+t;
            case 0x2A: return "SRLH  "+D+".h = "+S+".h >>> "+t;
            case 0x2B: return "SRAH  "+D+".h = "+S+".h >> "+t;
            case 0x2C: return bri("BEQI ", D, (byte)s, t, pc);
            case 0x2D: return bri("BNEI ", D, (byte)s, t, pc);
            case 0x2E: return bri("BGEI ", D, (byte)s, t, pc);
            case 0x2F: return bri("BGEUI", D, s, t, pc);

            case 0x30: return bri("BGTI ", D, (byte)s, t, pc);
            case 0x31: return bri("BGTUI", D, s, t, pc);
            case 0x32: return bri("BLEI ", D, (byte)s, t, pc);
            case 0x33: return bri("BLEUI", D, s, t, pc);
            case 0x34: return bri("BLTI ", D, (byte)s, t, pc);
            case 0x35: return bri("BLTUI", D, s, t, pc);
            case 0x36: return bri("BEQIB", D+".b", s, t, pc);
            case 0x37: return bri("BNEIB", D+".b", s, t, pc);
            case 0x38: return bri("BGEIB", D+".b", (byte)s, t, pc);
            case 0x39: return bri("BGEUIB",D+".b", s, t, pc);
            case 0x3A: return bri("BGTIB", D+".b", (byte)s, t, pc);
            case 0x3B: return bri("BGTUIB",D+".b", s, t, pc);
            case 0x3C: return bri("BLEIB", D+".b", (byte)s, t, pc);
            case 0x3D: return bri("BLEUIB",D+".b", s, t, pc);
            case 0x3E: return bri("BLTIB", D+".b", (byte)s, t, pc);
            case 0x3F: return bri("BLTUIB",D+".b", s, t, pc);

            case 0x40: return "LDQ   "+D+" = "+(short)((w>>>16)&0xffff);
            case 0x41: return "JPr   -> "+D;
            case 0x42: return "CALLr -> "+D;
            case 0x43: return "STORE "+D+" .. "+s+" regs";
            case 0x44: return "RESTORE "+D+" .. "+s+" regs";
            case 0x45: return "RET   (restore "+D+" .. "+s+")";
            case 0x46: return "KILLTASK";
            case 0x47: return "SLEEP";
            case 0x48: return "SYSCPY memcpy("+D+", "+S+", "+T+")";
            case 0x49: return "SYSSET memset("+D+", "+S+".b, "+T+")";

            case 0x4A: return "ADDi  "+D+" = "+S+" + "+imm(nx);
            case 0x4B: return "ANDi  "+D+" = "+S+" & "+imm(nx);
            case 0x4C: return "MULi  "+D+" = "+S+" * "+imm(nx);
            case 0x4D: return "DIVi  "+D+" = "+S+" / "+imm(nx);
            case 0x4E: return "DIVUi "+D+" = "+S+" /u "+imm(nx);
            case 0x4F: return "ORi   "+D+" = "+S+" | "+imm(nx);
            case 0x50: return "XORi  "+D+" = "+S+" ^ "+imm(nx);
            case 0x51: return "SUBi  "+D+" = "+S+" - "+imm(nx);
            case 0x52: return "STB   ["+S+" + "+imm(nx)+"] = "+D+".b";
            case 0x53: return "STH   ["+S+" + "+imm(nx)+"] = "+D+".h";
            case 0x54: return "STW   ["+S+" + "+imm(nx)+"] = "+D;
            case 0x55: return "LDB   "+D+" = (s8) ["+S+" + "+imm(nx)+"]";
            case 0x56: return "LDH   "+D+" = (s16)["+S+" + "+imm(nx)+"]";
            case 0x57: return "LDW   "+D+" = ["+S+" + "+imm(nx)+"]";
            case 0x58: return "LDBU  "+D+" = (u8) ["+S+" + "+imm(nx)+"]";
            case 0x59: return "LDHU  "+D+" = (u16)["+S+" + "+imm(nx)+"]";
            case 0x5A: return "LDI   "+D+" = "+imm(nx);
            case 0x5B: return "JPl   -> "+target(pc, nx);
            case 0x5C: {
                String nm = importAt(pc);
                return "CALLl -> " + (nm!=null ? "import "+nm : target(pc, nx));
            }
            case 0x5D: return brr("BEQ  ", D, S, pc, nx);
            case 0x5E: return brr("BNE  ", D, S, pc, nx);
            case 0x5F: return brr("BGE  ", D, S, pc, nx);
            case 0x60: return brr("BGEU ", D, S, pc, nx);
            case 0x61: return brr("BGT  ", D, S, pc, nx);
            case 0x62: return brr("BGTU ", D, S, pc, nx);
            case 0x63: return brr("BLE  ", D, S, pc, nx);
            case 0x64: return brr("BLEU ", D, S, pc, nx);
            case 0x65: return brr("BLT  ", D, S, pc, nx);
            case 0x66: return brr("BLTU ", D, S, pc, nx);
            default:   return String.format("??? op=0x%02x d=%d s=%d t=%d", op, d, s, t);
        }
    }

    // desvio com deslocamento no campo t: brI faz PC = PC + (sx8(t)-1)*4, com PC ja em pc+4
    private String bri(String mn, String D, int simm, int t, int pc){
        int dest = pc + 4 + (((byte)t)-1)*4;
        return mn+" "+D+", "+simm+"  -> "+String.format("0x%x", dest);
    }
    // desvio com alvo na palavra seguinte: brR faz PC = pcb + imm - 4, com pcb = pc+4
    private String brr(String mn, String D, String S, int pc, int nx){
        return mn+" "+D+", "+S+"  -> "+target(pc, nx);
    }
    private String target(int pc, int info){
        int pcb = pc + 4;
        if((info & 0x80000000)!=0){
            int raw = (info & 0x7FFFFFFF) | ((info<<1) & 0x80000000);
            return String.format("0x%x", pcb + raw - 4);
        }
        int idx = info - 1;
        if(idx>=0 && idx<vm.poolValue.length) return String.format("0x%x <pool#%d>", (int)vm.poolValue[idx], idx);
        return "pool#"+idx+"(?)";
    }

    /** desmonta um trecho */
    public String range(int pc, int count){
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<count;i++){
            int w = vm.read32(pc);
            int sz = size(pc);
            sb.append(String.format("  0x%06x: %08x", pc, w));
            if(sz==8) sb.append(String.format(" %08x", vm.read32(pc+4))); else sb.append("         ");
            sb.append("  ").append(one(pc)).append('\n');
            pc += sz;
        }
        return sb.toString();
    }

    /**
     * Varre a secao de codigo e devolve os enderecos de todo CALLl para o import 'name'.
     * A varredura e por passo de 4 bytes (nao segue o fluxo), entao pode haver falso positivo;
     * o contexto desmontado deixa claro quando e o caso.
     */
    public java.util.List<Integer> xref(String name){
        java.util.List<Integer> hits = new java.util.ArrayList<Integer>();
        int lo = vm.codeBase, hi = vm.codeBase + vm.exe.codeSize;
        for(int pc=lo; pc<hi-4; pc+=4){
            String nm = importAt(pc);
            if(nm!=null && nm.equals(name)) hits.add(pc);
        }
        return hits;
    }

    // ---------------- CLI ----------------
    public static void main(String[] args) throws Exception {
        if(args.length < 2){
            System.out.println("uso: Disasm <rom.mpn> 0xENDERECO [n]   |   Disasm <rom.mpn> --xref <nomeDoImport> [nInstrAntes]");
            return;
        }
        byte[] raw = java.nio.file.Files.readAllBytes(new java.io.File(args[0]).toPath());
        Vmgp exe = new Vmgp(raw);
        int heapBytes = (int)Math.max(4L*1024*1024, exe.heapBytesRequested());
        Pip2 vm = new Pip2(exe, heapBytes, 256*1024);
        Disasm dis = new Disasm(vm);

        System.out.printf("code=[0x%x .. 0x%x)  data=0x%x  bss=0x%x  heap=0x%x%n",
            vm.codeBase, vm.codeBase+exe.codeSize, vm.dataBase, vm.bssBase, vm.heapBase);

        if(args[1].equals("--xref")){
            String nome = args[2];
            int before = args.length>3 ? Integer.parseInt(args[3]) : 14;
            java.util.List<Integer> hits = dis.xref(nome);
            System.out.println("CALLl para "+nome+": "+hits.size()+" ocorrencia(s)");
            for(int h : hits){
                System.out.printf("%n=== chamada em 0x%x — contexto (%d instrucoes antes) ===%n", h, before);
                // Instrucoes tem tamanho variavel (4 ou 8), entao nao da p/ recuar contando bytes.
                // Sincroniza: tenta varios pontos de partida alinhados a 4 e fica com o PRIMEIRO cujo
                // fluxo de instrucoes caia EXATAMENTE em h (os outros desalinham e batem no meio).
                int start = -1;
                for(int cand = Math.max(vm.codeBase, h - before*8 - 64); cand < h; cand += 4){
                    int p = cand, guard = 0; boolean ok = false;
                    while(p < h && guard++ < 8192) p += dis.size(p);
                    if(p == h) ok = true;
                    if(ok){ start = cand; break; }
                }
                if(start < 0) start = Math.max(vm.codeBase, h - before*4);
                // conta quantas instrucoes existem de start ate h, e recua p/ mostrar ~before delas
                java.util.List<Integer> addrs = new java.util.ArrayList<Integer>();
                for(int p = start; p < h; p += dis.size(p)) addrs.add(p);
                int from = addrs.size() > before ? addrs.get(addrs.size()-before) : start;
                System.out.print(dis.range(from, Math.min(before, addrs.size()) + 2));
            }
        } else {
            int pc = (int)Long.decode(args[1]).longValue();
            int n  = args.length>2 ? Integer.parseInt(args[2]) : 40;
            System.out.print(dis.range(pc, n));
        }
    }
}
