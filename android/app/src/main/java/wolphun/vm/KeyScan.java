package wolphun.vm;

/**
 * Analisador ESTATICO de entrada (teclas) sobre o codigo PIP2 de uma ROM.
 *
 * Varre a secao de codigo procurando as chamadas dos imports de entrada e RECUPERA os argumentos
 * constantes por propagacao de constantes PARA TRAS a partir do ponto de chamada:
 *
 *   vTestKey(key)      -> resolve o valor de p0 (o codigo da tecla)
 *   vGetButtonData()   -> resolve a MASCARA aplicada ao retorno (ANDi/ANDBi depois da chamada)
 *   vScanKeys()        -> apenas conta (o retorno e comparado, nao ha argumento)
 *   vGetPointerPos()   -> apenas conta (indica jogo com ponteiro/touch)
 *
 * Vantagem sobre instrumentar em tempo de execucao: enxerga TODAS as teclas que o jogo consulta em
 * qualquer tela, inclusive as que um script de teclas nunca alcanca.
 *
 * Quando a tecla nao e imediata e vem de uma LEITURA DE MEMORIA com indice variavel, o caso e
 * reportado como TABELA com o endereco base — e assim que um jogo consulta um bloco inteiro de
 * codigos de aparelho (200..217) dentro de um laco.
 *
 * Uso:
 *   java -cp build wolphun.vm.KeyScan &lt;rom.mpn&gt; [--v]
 */
public final class KeyScan {

    final Pip2 vm;
    KeyScan(Pip2 vm){ this.vm = vm; }

    // ---- primitivas de decodificacao (proprias, p/ o arquivo ser identico nos 2 projetos: o
    //      Disasm e ferramenta de linha de comando e so existe no desktop) ----

    /** true se o opcode consome a palavra seguinte (imediato / info de pool / alvo de desvio) */
    static boolean hasImm(int op){ return op>=0x4A && op<=0x66; }
    /** tamanho em bytes da instrucao neste endereco (4 ou 8) */
    int size(int pc){ return hasImm(vm.read32(pc)&0xff) ? 8 : 4; }
    /** nome legivel do registrador a partir do offset em bytes */
    static String reg(int off){
        switch(off){
            case Pip2.ZERO: return "zero";  case Pip2.SP: return "sp";  case Pip2.RA: return "ra";
            case Pip2.FP:   return "fp";    case Pip2.P0: return "p0";  case Pip2.P1: return "p1";
            case Pip2.P2:   return "p2";    case Pip2.P3: return "p3";  case Pip2.R0: return "r0";
            case Pip2.R1:   return "r1";    case Pip2.PC: return "pc";
            default: return "r" + (off>>2);
        }
    }
    /** nome do import chamado por um CALLl neste endereco, ou null */
    String importAt(int pc){
        if((vm.read32(pc)&0xff) != 0x5C) return null;
        int info = vm.read32(pc+4);
        if((info & 0x80000000)!=0) return null;
        int idx = info - 1;
        if(idx>=0 && idx<vm.poolValue.length && vm.poolIsImport[idx]) return vm.poolName[idx];
        return null;
    }
    /** enderecos de todo CALLl para o import 'name' (varredura de 4 em 4, nao segue o fluxo) */
    java.util.List<Integer> xref(String name){
        java.util.List<Integer> hits = new java.util.ArrayList<Integer>();
        int lo = vm.codeBase, hi = vm.codeBase + vm.exe.codeSize;
        for(int pc=lo; pc<hi-4; pc+=4){
            String nm = importAt(pc);
            if(nm!=null && nm.equals(name)) hits.add(pc);
        }
        return hits;
    }

    /** nomes oficiais dos codigos de tecla (vmgp.h + vmgpsonyericsson.h) */
    static String keyName(int k){
        switch(k){
            case 200: return "SE_VOLUME_UP";
            case 201: return "SE_VOLUME_DOWN";
            case 202: return "SE_YES (atender/verde)";
            case 203: return "SE_NO (desligar/vermelho = VOLTAR)";
            case 204: return "SE_CLEAR (tecla C = apagar)";
            case 205: return "SE_LEFT (joystick)";
            case 206: return "SE_RIGHT (joystick)";
            case 207: return "SE_FLIP_CLOSE";
            case 208: return "SE_FLIP_OPEN";
            case 209: return "SE_PHF (fone)";
            case 210: return "SE_UP (joystick)";
            case 211: return "SE_DOWN (joystick)";
            case 212: return "SE_LEFT_UP";
            case 213: return "SE_RIGHT_DOWN";
            case 214: return "SE_NAV_SELECT (aperta o joystick)";
            case 215: return "SE_VOICENOTE";
            case 216: return "SE_SELECT";
            case 217: return "SE_OPTION";
            case 8:   return "BACKSPACE";
            case 13:  return "ENTER";
            case 27:  return "ESC (softkey de sair no aparelho)";
        }
        if(k>=32 && k<127) return "tecla " + (char)k;
        return "?";
    }

    /** bits de vGetButtonData (vmgp.h) */
    static String maskName(long m){
        StringBuilder sb = new StringBuilder();
        if((m&0x01)!=0) sb.append("UP|");
        if((m&0x02)!=0) sb.append("DOWN|");
        if((m&0x04)!=0) sb.append("LEFT|");
        if((m&0x08)!=0) sb.append("RIGHT|");
        if((m&0x10)!=0) sb.append("FIRE|");
        if((m&0x20)!=0) sb.append("SELECT|");
        if((m&0x40)!=0) sb.append("POINTER_DOWN|");
        if((m&0x80)!=0) sb.append("POINTER_ALTDOWN|");
        if((m&0x100)!=0) sb.append("FIRE2|");
        long resto = m & ~0x1ffL;
        if(resto!=0) sb.append(String.format("0x%x|", resto));
        if(sb.length()==0) return "(nenhum)";
        return sb.substring(0, sb.length()-1);
    }

    static boolean writesD(int op){
        if(op<=0x01) return false;
        if(op<=0x2B) return true;              // aritmetica / mov / shifts
        if(op<=0x3F) return false;             // desvios com imediato
        if(op==0x40) return true;              // LDQ
        if(op==0x41||op==0x42) return false;   // JPr / CALLr
        if(op==0x43) return false;             // STORE (memoria)
        if(op==0x44||op==0x45) return true;    // RESTORE / RET mexem em varios
        if(op<=0x49) return false;             // KILLTASK/SLEEP/SYSCPY/SYSSET
        if(op<=0x51) return true;              // ADDi..SUBi
        if(op<=0x54) return false;             // STB/STH/STW
        if(op<=0x5A) return true;              // LDB..LDI
        return false;                          // JPl/CALLl/desvios
    }

    /** valor de um imediato/pool como o fetchImm() do interpretador */
    long immVal(int w){
        if((w & 0x80000000)!=0) return (w & 0x7FFFFFFF) | ((w<<1) & 0x80000000);
        int idx = w - 1;
        if(idx>=0 && idx<vm.poolValue.length) return vm.poolValue[idx];
        return Long.MIN_VALUE;
    }

    /** enderecos das instrucoes que levam a 'at', sincronizando o fluxo de tamanho variavel */
    int[] prelude(int at, int maxInstr){
        int start = -1;
        for(int cand = Math.max(vm.codeBase, at - maxInstr*8 - 64); cand < at; cand += 4){
            int p = cand, guard = 0;
            while(p < at && guard++ < 16384) p += size(p);
            if(p == at){ start = cand; break; }
        }
        if(start < 0) return new int[0];
        java.util.List<Integer> a = new java.util.ArrayList<Integer>();
        for(int p = start; p < at; p += size(p)) a.add(p);
        int[] out = new int[a.size()];
        for(int i=0;i<out.length;i++) out[i]=a.get(i);
        return out;
    }

    static final class Val {
        boolean known; long v; String note;
        static Val of(long v){ Val r=new Val(); r.known=true; r.v=v; return r; }
        static Val no(String n){ Val r=new Val(); r.known=false; r.note=n; return r; }
    }

    /** propaga constantes PARA TRAS: valor do registrador (offset em bytes) 'want' ao chegar em 'at' */
    Val back(int at, int want){
        int[] pre = prelude(at, 80);
        long addend = 0;
        for(int i = pre.length-1; i >= 0; i--){
            int pc = pre[i];
            int w = vm.read32(pc);
            int op=w&0xff, d=(w>>>8)&0xff, s=(w>>>16)&0xff, t=(w>>>24)&0xff;
            int nx = vm.read32(pc+4);

            if(op==0x5C || op==0x42){           // uma chamada anterior destroi p0..p3 / r0
                if(want==Pip2.P0||want==Pip2.P1||want==Pip2.P2||want==Pip2.P3||want==Pip2.R0)
                    return Val.no("vem do retorno/argumento de uma chamada anterior em 0x"+Integer.toHexString(pc));
                continue;
            }
            if(!writesD(op) || d!=want) continue;

            switch(op){
                case 0x40: return Val.of((short)((w>>>16)&0xffff) + addend);          // LDQ
                case 0x5A: { long v=immVal(nx); if(v==Long.MIN_VALUE) return Val.no("pool nao resolvido");
                             return Val.of(v + addend); }                              // LDI
                case 0x11: case 0x16: case 0x1B: case 0x0F: case 0x10:                 // MOV/MOVB/MOVH/EXS*
                    want = s; continue;
                case 0x1F: if(s==Pip2.ZERO) return Val.of((byte)t + addend);           // ADDQ
                           addend += (byte)t; want = s; continue;
                case 0x4A: { long v=immVal(nx);                                        // ADDi
                             if(s==Pip2.ZERO && v!=Long.MIN_VALUE) return Val.of(v + addend);
                             if(v==Long.MIN_VALUE) return Val.no("ADDi com pool");
                             addend += v; want = s; continue; }
                case 0x4F: { long v=immVal(nx);                                        // ORi
                             if(s==Pip2.ZERO && v!=Long.MIN_VALUE) return Val.of(v + addend);
                             return Val.no("ORi em 0x"+Integer.toHexString(pc)); }
                case 0x55: case 0x58: case 0x56: case 0x59: case 0x57: {                // LDB/LDBU/LDH/LDHU/LDW
                    long a = immVal(nx);
                    String tipo = (op==0x55||op==0x58)?"byte":(op==0x57?"word":"half");
                    if(a==Long.MIN_VALUE) return Val.no("carga de memoria (pool)");
                    if(s==Pip2.ZERO){                                                   // endereco fixo
                        int addr=(int)a;
                        long v = (op==0x55||op==0x58)? vm.read8(addr) : (op==0x57? vm.read32(addr) : vm.read16(addr));
                        return Val.of(v + addend);
                    }
                    // Indice variavel: TABELA. O caso tipico e um laco que varre um vetor de codigos
                    // de tecla (`p3 = LDI <tabela>` ... `LDBU p0=[p3+0]; p3++`). Resolvendo o registrador
                    // base recursivamente chegamos ao endereco do vetor e podemos despejar os codigos.
                    Val b = back(pc, s);
                    if(b.known && (int)b.v != 0){
                        String t2 = dumpTable((int)(b.v + a));
                        if(t2!=null) return Val.no("TABELA@0x"+Integer.toHexString((int)(b.v+a))+" -> "+t2);
                    }
                    return Val.no(String.format("TABELA %s em 0x%x indexada por %s", tipo, (int)a, reg(s)));
                }
                default:
                    return Val.no(String.format("op 0x%02x em 0x%x (palavra %08x)", op, pc, w));
            }
        }
        return Val.no("nao achei a definicao no bloco");
    }

    /**
     * Despeja um vetor de codigos de tecla: le bytes enquanto forem codigos plausiveis
     * (ASCII imprimivel ou 200..217). Devolve null se o primeiro byte ja nao servir.
     */
    String dumpTable(int addr){
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for(; n<48; n++){
            int v = vm.read8(addr+n);
            boolean plaus = (v>=32 && v<127) || (v>=200 && v<=217) || v==8 || v==13;
            if(!plaus) break;
            if(n>0) sb.append(", ");
            sb.append(v).append('=').append(keyName(v));
        }
        if(n==0) return null;
        return n + " codigo(s): " + sb;
    }

    /**
     * Mascaras aplicadas ao retorno de uma chamada (r0), por propagacao de CONTAMINACAO para frente:
     * segue as copias do valor por varios registradores e coleta todo AND com constante. Quando o
     * valor e GUARDADO numa global, o endereco entra em 'globais' — a segunda passada (masksOnGlobal)
     * procura quem le de la, que e como a maioria dos jogos testa os botoes (guarda o bitmask num
     * campo e compara depois, longe da chamada).
     */
    /** onde o bitmask foi guardado: casa (registrador base, deslocamento) + um pc de referencia */
    static final class Slot {
        int base, off, pc;
        Slot(int base,int off,int pc){ this.base=base; this.off=off; this.pc=pc; }
        public boolean equals(Object o){ return o instanceof Slot && ((Slot)o).base==base && ((Slot)o).off==off; }
        public int hashCode(){ return base*31+off; }
    }

    /**
     * Segue a contaminacao para frente a partir de 'reg' em 'pc'. Coleta:
     *   out   — mascaras de AND com constante (bits de vGetButtonData)
     *   cmp   — constantes COMPARADAS com o valor (os codigos que o jogo espera do vScanKeys)
     *   slots — onde o valor foi guardado, p/ continuar a busca de quem le de la
     */
    void follow(int pc, int reg, int passos, java.util.Set<Long> out, java.util.Set<Long> cmp,
                java.util.Set<Slot> slots){
        java.util.Set<Integer> taint = new java.util.HashSet<Integer>();
        taint.add(reg);
        for(int n=0; n<passos && !taint.isEmpty(); n++){
            int w = vm.read32(pc);
            int op=w&0xff, d=(w>>>8)&0xff, s=(w>>>16)&0xff, t=(w>>>24)&0xff;
            int nx = vm.read32(pc+4);
            boolean ts = taint.contains(s), tt = taint.contains(t);

            // desvios com imediato: o registrador esta em 'd' e a constante em 's'
            if(op>=0x2C && op<=0x3F){
                if(cmp!=null && taint.contains(d)){
                    boolean sgn = (op==0x2C||op==0x2D||op==0x2E||op==0x30||op==0x32||op==0x34
                                || op==0x38||op==0x3A||op==0x3C||op==0x3E);
                    cmp.add((long)(sgn ? (byte)s : s));
                }
                pc += size(pc); continue;                 // desvio nao escreve registrador
            }
            if(op==0x4B && ts){ long m=immVal(nx); if(m!=Long.MIN_VALUE && m!=0) out.add(m); }   // ANDi
            else if((op==0x22||op==0x28) && ts){ if(t!=0) out.add((long)t); }                     // ANDBi/ANDHi
            else if(op==0x03 && (ts||tt)){                                                        // AND reg,reg
                Val m = back(pc, ts ? t : s);
                if(m.known && m.v!=0 && m.v!=-1) out.add(m.v);
            }
            else if((op==0x51||op==0x50) && ts && cmp!=null){          // SUBi / XORi = teste de igualdade
                long m=immVal(nx); if(m!=Long.MIN_VALUE && m>0) cmp.add(m);
                if(op==0x50){ taint.add(d); pc += size(pc); continue; }
            }
            else if((op==0x5D||op==0x5E) && cmp!=null && (taint.contains(d)||ts)){                 // BEQ/BNE reg,reg
                Val m = back(pc, taint.contains(d) ? s : d);
                if(m.known) cmp.add(m.v);
            }
            else if((op==0x54||op==0x53||op==0x52) && taint.contains(d)){                          // STW/STH/STB
                long a = immVal(nx);
                if(a!=Long.MIN_VALUE) slots.add(new Slot(s, (int)a, pc));
            }
            else if(op==0x5C) break;                        // chamada: r0 e os p* morrem aqui

            if(op==0x11 || op==0x16 || op==0x1B){ if(ts) taint.add(d); else taint.remove(d); }     // MOV*
            else if(writesD(op)) taint.remove(d);
            pc += size(pc);
        }
    }

    void masksAfter(int at, java.util.Set<Long> out, java.util.Set<Slot> slots){
        follow(at + size(at), Pip2.R0, 70, out, null, slots);
    }

    /** entrada da funcao que contem 'pc': a instrucao seguinte ao ultimo RET/JPr ra anterior */
    int funcEntry(int pc){
        int[] pre = prelude(pc, 400);
        for(int i = pre.length-1; i >= 0; i--){
            int w = vm.read32(pre[i]);
            int op = w&0xff, d = (w>>>8)&0xff;
            boolean fim = (op==0x41 && d==Pip2.RA) || op==0x45 || op==0x46;   // JPr ra / RET / KILLTASK
            if(fim) return pre[i] + size(pre[i]);
        }
        return pre.length>0 ? pre[0] : pc;
    }

    /** enderecos dos CALLl (nao-import) que chamam 'entry' */
    java.util.List<Integer> callersOf(int entry){
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        int lo = vm.codeBase, hi = vm.codeBase + vm.exe.codeSize;
        for(int pc=lo; pc<hi-8; pc+=4){
            if((vm.read32(pc)&0xff) != 0x5C) continue;
            int info = vm.read32(pc+4);
            int dest;
            if((info & 0x80000000)!=0) dest = pc + ((info & 0x7FFFFFFF) | ((info<<1) & 0x80000000));
            else {
                int idx = info - 1;
                if(idx<0 || idx>=vm.poolValue.length || vm.poolIsImport[idx]) continue;
                dest = (int)vm.poolValue[idx];
            }
            if(dest == entry) out.add(pc);
        }
        return out;
    }

    /**
     * Como masksAfter/scanCodes, mas se nada aparecer no ponto de chamada o valor esta sendo
     * DEVOLVIDO por uma funcao auxiliar (`int lerBotoes(){ return vGetButtonData(); }`) — nesse caso
     * sobe um nivel e analisa quem chamou a auxiliar. Sem isso, jogos que embrulham a leitura de
     * entrada (Brain Vita, Flintstones, Revs!) nao revelam mascara nenhuma.
     */
    void deep(int at, java.util.Set<Long> out, java.util.Set<Long> cmp, java.util.Set<Slot> slots, int nivel){
        int antesM = out.size(), antesC = cmp==null?0:cmp.size();
        follow(at + size(at), Pip2.R0, 70, out, cmp, slots);
        if(nivel<=0) return;
        if(out.size()!=antesM || (cmp!=null && cmp.size()!=antesC)) return;   // ja resolveu aqui
        int entry = funcEntry(at);
        if(entry==at) return;
        java.util.List<Integer> cs = callersOf(entry);
        if(cs.isEmpty() || cs.size()>40) return;
        for(int c : cs) deep(c, out, cmp, slots, nivel-1);
    }

    /** codigos que o jogo compara com o retorno de vScanKeys (o "teclado" que ele espera) */
    void scanCodes(int at, java.util.Set<Long> cmp, java.util.Set<Slot> slots){
        follow(at + size(at), Pip2.R0, 70, new java.util.HashSet<Long>(), cmp, slots);
    }

    /** codigos comparados por quem LE do lugar onde o retorno do vScanKeys foi guardado */
    void codesOnSlot(Slot sl, java.util.Set<Long> cmp){
        int lo = vm.codeBase, hi = vm.codeBase + vm.exe.codeSize;
        if(sl.base==Pip2.SP || sl.base==Pip2.FP){
            lo = Math.max(lo, sl.pc - 0x1000); hi = Math.min(hi, sl.pc + 0x1000);
        }
        java.util.Set<Slot> ignora = new java.util.HashSet<Slot>();
        java.util.Set<Long> lixo = new java.util.HashSet<Long>();
        for(int pc=lo; pc<hi-8; pc+=4){
            int w = vm.read32(pc);
            int op = w&0xff;
            if(op!=0x57 && op!=0x59 && op!=0x58 && op!=0x56 && op!=0x55) continue;
            if((w>>>16&0xff) != sl.base) continue;
            if(immVal(vm.read32(pc+4)) != sl.off) continue;
            follow(pc + 8, (w>>>8)&0xff, 40, lixo, cmp, ignora);
        }
    }

    /**
     * Mascaras aplicadas a quem LE do lugar onde o bitmask foi guardado. Se a base e uma global
     * (zero/registrador resolvido) a busca e no codigo inteiro; se e sp/fp o lugar e uma variavel
     * LOCAL e a busca fica numa janela em volta da escrita, que e aproximadamente a mesma funcao.
     */
    void masksOnSlot(Slot sl, java.util.Set<Long> out){
        int lo = vm.codeBase, hi = vm.codeBase + vm.exe.codeSize;
        boolean local = (sl.base==Pip2.SP || sl.base==Pip2.FP);
        if(local){ lo = Math.max(lo, sl.pc - 0x1000); hi = Math.min(hi, sl.pc + 0x1000); }
        java.util.Set<Slot> ignora = new java.util.HashSet<Slot>();
        for(int pc=lo; pc<hi-8; pc+=4){
            int w = vm.read32(pc);
            int op = w&0xff;
            if(op!=0x57 && op!=0x59 && op!=0x58 && op!=0x56 && op!=0x55) continue;   // LDW/LDHU/LDBU/LDH/LDB
            if((w>>>16&0xff) != sl.base) continue;
            if(immVal(vm.read32(pc+4)) != sl.off) continue;
            follow(pc + 8, (w>>>8)&0xff, 40, out, null, ignora);
        }
    }

    /**
     * DETECCAO EM TEMPO DE CARGA: conjunto dos codigos de tecla que esta ROM realmente consulta —
     * imediatos do vTestKey, vetores de codigos varridos em laco, e constantes comparadas com o
     * retorno do vScanKeys. Serve p/ a interface decidir QUAIS botoes mostrar em cada jogo, em vez
     * de manter lista de nomes na mao. Nunca lanca: na duvida devolve o que conseguiu achar.
     */
    public static java.util.Set<Integer> keysUsedBy(Pip2 vm){
        java.util.TreeSet<Integer> out = new java.util.TreeSet<Integer>();
        try {
            KeyScan ks = new KeyScan(vm);
            for(int h : ks.xref("vTestKey")){
                Val v = ks.back(h, Pip2.P0);
                if(v.known){ out.add((int)v.v); continue; }
                // argumento vem de um vetor: entra a tabela inteira (ex.: Something Fishy varre 24)
                if(v.note!=null && v.note.startsWith("TABELA@")){
                    int at = (int)Long.decode(v.note.substring(7, v.note.indexOf(' '))).longValue();
                    for(int i=0;i<48;i++){
                        int b = vm.read8(at+i);
                        boolean plaus = (b>=32 && b<127) || (b>=200 && b<=217) || b==8 || b==13;
                        if(!plaus) break;
                        out.add(b);
                    }
                }
            }
            java.util.List<Integer> sk = ks.xref("vScanKeys");
            if(!sk.isEmpty()){
                java.util.TreeSet<Long> codes = new java.util.TreeSet<Long>();
                java.util.Set<Slot> ss = new java.util.HashSet<Slot>();
                for(int h : sk) ks.deep(h, new java.util.HashSet<Long>(), codes, ss, 2);
                for(Slot s : ss) ks.codesOnSlot(s, codes);
                for(long c : codes) if(c>=8 && c<=217) out.add((int)c);
            }
        } catch(Throwable t){ /* diagnostico best-effort: nunca derruba o carregamento do jogo */ }
        return out;
    }

    /** true se o jogo precisa dos botoes de tecla do APARELHO (203 voltar / 204 apagar). */
    public static boolean needsPhoneKeys(Pip2 vm){
        java.util.Set<Integer> k = keysUsedBy(vm);
        return k.contains(SE_NO) || k.contains(SE_CLEAR);
    }
    /** true se o jogo le DIGITOS literais do teclado do telefone. */
    public static boolean needsNumberKeys(Pip2 vm){
        java.util.Set<Integer> k = keysUsedBy(vm);
        int n = 0;
        for(int c='0'; c<='9'; c++) if(k.contains(c)) n++;
        return n >= 3 || k.contains((int)'*') || k.contains((int)'#');
    }
    static final int SE_NO = 203, SE_CLEAR = 204;

    public static void main(String[] args) throws Exception {
        if(args.length < 1){ System.out.println("uso: KeyScan <rom.mpn> [--v]"); return; }
        boolean verbose = args.length>1 && args[1].equals("--v");
        byte[] raw = java.nio.file.Files.readAllBytes(new java.io.File(args[0]).toPath());
        Vmgp exe = new Vmgp(raw);
        Pip2 vm = new Pip2(exe, 4*1024*1024, 256*1024);   // a varredura so le code/data
        KeyScan ks = new KeyScan(vm);

        System.out.println("### " + new java.io.File(args[0]).getName());

        // --- vTestKey: recupera o codigo da tecla de cada chamada
        java.util.List<Integer> tk = ks.xref("vTestKey");
        java.util.TreeMap<Long,Integer> keys = new java.util.TreeMap<Long,Integer>();
        java.util.List<String> duvidas = new java.util.ArrayList<String>();
        for(int h : tk){
            Val v = ks.back(h, Pip2.P0);
            if(v.known) keys.put(v.v, (keys.containsKey(v.v)?keys.get(v.v):0)+1);
            else duvidas.add(String.format("  0x%06x: %s", h, v.note));
        }
        System.out.printf("vTestKey: %d chamada(s), %d resolvida(s)%n", tk.size(), tk.size()-duvidas.size());
        for(java.util.Map.Entry<Long,Integer> e : keys.entrySet()){
            long k = e.getKey();
            System.out.printf("   %4d  %-34s  x%d%n", k, keyName((int)k), e.getValue());
        }
        for(String d : duvidas) System.out.println("   NAO RESOLVIDA " + d);

        // --- vGetButtonData: quais bits sao testados
        java.util.List<Integer> gb = ks.xref("vGetButtonData");
        java.util.TreeSet<Long> masks = new java.util.TreeSet<Long>();
        java.util.Set<Slot> slots = new java.util.HashSet<Slot>();
        for(int h : gb) ks.deep(h, masks, null, slots, 2);
        for(Slot s : slots) ks.masksOnSlot(s, masks);
        System.out.printf("vGetButtonData: %d chamada(s)%s%n", gb.size(),
                          slots.isEmpty() ? "" : " (bitmask guardado em "+slots.size()+" variavel(is))");
        long uniao = 0;
        for(long m : masks){
            uniao |= m;
            System.out.printf("   0x%04x  %s%n", m, maskName(m));
        }
        if(uniao!=0) System.out.printf("   UNIAO  0x%04x  %s%n", uniao, maskName(uniao));

        // --- vScanKeys: contra QUE codigos o retorno e comparado
        java.util.List<Integer> sk = ks.xref("vScanKeys");
        if(!sk.isEmpty()){
            java.util.TreeSet<Long> codes = new java.util.TreeSet<Long>();
            java.util.Set<Slot> ss = new java.util.HashSet<Slot>();
            for(int h : sk) ks.deep(h, new java.util.HashSet<Long>(), codes, ss, 2);
            for(Slot s : ss) ks.codesOnSlot(s, codes);
            System.out.printf("vScanKeys: %d chamada(s)%s%n", sk.size(),
                              ss.isEmpty() ? "" : " (retorno guardado em "+ss.size()+" variavel(is))");
            StringBuilder sb = new StringBuilder();
            for(long c : codes){
                if(c<8 || c>217) continue;                       // fora de faixa: nao e codigo de tecla
                if(sb.length()>0) sb.append(",  ");
                sb.append(c).append(' ').append(keyName((int)c));
            }
            if(sb.length()>0) System.out.println("   espera: " + sb);
        }
        for(String imp : new String[]{"vGetPointerPos","vGetKeyRepeat","vSetKeyRepeat"}){
            int n = ks.xref(imp).size();
            if(n>0) System.out.printf("%s: %d chamada(s)%n", imp, n);
        }
        if(verbose){
            for(int h : tk){
                System.out.printf("%n--- vTestKey em 0x%x ---%n", h);
                int[] pre = ks.prelude(h, 8);
                for(int a : pre) System.out.printf("  0x%06x: %08x%n", a, vm.read32(a));
            }
        }
    }
}
