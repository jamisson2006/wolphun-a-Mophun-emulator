package wolphun.vm;

/**
 * Interpretador da VM PIP2 do mophun (o "processador" virtual do jogo).
 * Registradores de 32 bits enderecados por byte-offset; instrucoes de 4 ou 8
 * bytes; palavra = [opcode|d|s|t]. Roda o bytecode REAL do jogo -> fidelidade.
 *
 * Convencao de chamada (ABI): args em p0..p3 e depois pilha; retorno em r0.
 * Portado da implementacao aberta RadratSoftworks/nofun (PIP2/*).
 */
public final class Pip2 {
    // ---- registradores (byte-offset) ----
    public static final int ZERO=0, SP=4, RA=8, FP=12;
    public static final int S0=16, P0=48, P1=52, P2=56, P3=60, R0=120, R1=124, PC=128;

    public final int[] R = new int[33];        // R[i] = registrador no byte-offset i*4
    public byte[] mem;                          // memoria linear da VM
    public int base;                            // endereco logico do inicio (code)
    public int codeBase, dataBase, bssBase, heapBase, heapEnd, stackTop;

    public final Vmgp exe;
    public Sdk sdk;
    public boolean stopped = false;
    public long instr = 0;
    public long stubCalls = 0;

    // pool resolvido
    final long[]   poolValue;
    final boolean[] poolIsImport;
    final String[] poolName;

    public Pip2(Vmgp exe, int heapBytes, int stackBytes){
        this.exe = exe;
        this.poolValue    = new long[exe.poolCount];
        this.poolIsImport = new boolean[exe.poolCount];
        this.poolName     = new String[exe.poolCount];
        layoutAndLoad(heapBytes, stackBytes);
    }

    static int align(int v){ return (v + 15) & ~15; }

    private void layoutAndLoad(int heapBytes, int stackBytes){
        base = 0x00010000;
        codeBase = base;
        dataBase = codeBase + align(exe.codeSize);
        bssBase  = dataBase + align(exe.dataSize);
        heapBase = bssBase  + align(exe.bssSize);
        heapEnd  = heapBase + heapBytes;
        stackTop = heapEnd  + stackBytes;
        int total = stackTop - base + 16;
        mem = new byte[total];

        System.arraycopy(exe.all, exe.codeOff, mem, codeBase-base, exe.codeSize);
        System.arraycopy(exe.all, exe.dataOff, mem, dataBase-base, exe.dataSize);
        // bss ja zerado

        relocate();

        R[SP>>2] = stackTop;
        R[FP>>2] = stackTop;
        R[PC>>2] = codeBase;      // entry point = inicio do codigo
    }

    private void relocate(){
        for (int i=0;i<exe.poolCount;i++){
            Vmgp.PoolItem it = exe.pool[i];
            switch(it.type){
                case 2: // Import
                    poolIsImport[i]=true; poolName[i]=exe.getString(it.meta); poolValue[i]=0; break;
                case 1: case 3: { // Local / Global
                    int b = (it.itemTarget==1)?codeBase:(it.itemTarget==2)?dataBase:bssBase;
                    poolValue[i] = (b + it.target) & 0xffffffffL; break;
                }
                case 7: // Const32
                    poolValue[i] = it.target & 0xffffffffL; break;
                case 4: { // SectionRelativeReloc
                    int addr = ((it.itemTarget==1)?codeBase:dataBase) + it.target;
                    int v = read32(addr);
                    int b = (it.meta==1)?codeBase:(it.meta==2)?dataBase:bssBase;
                    write32(addr, v + b);
                    poolValue[i] = (v + b) & 0xffffffffL; break;
                }
                case 5: case 6: // Swap16/32 (host LE: sem troca)
                    poolValue[i] = it.meta & 0xffffffffL; break;
                case 8: { // SymAdd: poolItems[meta-1] + target
                    int ref = it.meta-1;
                    long rv = (ref>=0 && ref<poolValue.length)? poolValue[ref] : 0;
                    poolValue[i] = (rv + it.target) & 0xffffffffL; break;
                }
                default: poolValue[i]=0;
            }
        }
    }

    // Roda os CONSTRUTORES globais (C++ static init) ANTES do main, como o runtime mophun faz.
    // O toolchain mophun exporta um simbolo Global "~C" apontando p/ a __CTOR_LIST__ (lista de
    // enderecos de construtores em data, terminada em 0). Jogos em C puro (ex.: HC2) nao tem "~C"
    // -> no-op. Jogos C++ (ex.: HC1) inicializam aqui o sistema de entidades/sprites; sem isto,
    // os globais ficam zerados -> entidades clonadas/sem animacao. Chamar DEPOIS de vm.sdk ser
    // atribuido (os construtores chamam SDK, ex.: vGetCaps) e ANTES do loop principal.
    public void runConstructors(){
        long listAddr = -1;
        for(int i=0;i<exe.poolCount;i++){
            Vmgp.PoolItem it = exe.pool[i];
            if(it.type==3 && "~C".equals(exe.getString(it.meta))){ listAddr = poolValue[i]; break; }
        }
        boolean log = System.getenv("CTORLOG")!=null;
        if(listAddr<0){ if(log) System.out.println("[CTOR] sem lista ~C"); return; }
        int addr = (int)listAddr;
        if(log) System.out.printf("[CTOR] lista ~C em 0x%x%n", addr);
        for(int n=0; n<256; n++){                     // enderecos absolutos ate o terminador 0
            int ctor = read32(addr); addr += 4;
            if(ctor==0) break;
            long i0=instr;
            callFunction(ctor);
            if(log) System.out.printf("[CTOR] #%d -> 0x%x  (%d instrucoes, parado=%s)%n", n, ctor, instr-i0, stopped);
        }
    }
    // Chama uma funcao da VM (PC=fn, RA=sentinela) e roda ate ela retornar. Preserva/restaura o estado.
    private void callFunction(int fn){
        final int SENT = 0xFFFC;                      // fora do codigo; RET volta p/ ca -> paramos antes de executar
        int sRA=R[RA>>2], sPC=R[PC>>2], sSP=R[SP>>2], sFP=R[FP>>2]; boolean sStop=stopped;
        R[RA>>2]=SENT; R[PC>>2]=fn;
        long g=0;
        while(!stopped && R[PC>>2]!=SENT && g<50_000_000L){ step(); g++; }
        stopped=sStop; R[RA>>2]=sRA; R[PC>>2]=sPC; R[SP>>2]=sSP; R[FP>>2]=sFP;
    }

    // ================= memoria =================
    public int idx(int addr){ return addr - base; }
    public int  read8 (int a){ int i=idx(a); return (i>=0&&i<mem.length)? (mem[i]&0xff):0; }
    public int  read16(int a){ return read8(a)|(read8(a+1)<<8); }
    public int  read32(int a){ return read8(a)|(read8(a+1)<<8)|(read8(a+2)<<16)|(read8(a+3)<<24); }
    // DEBUG (env WWATCH=0xADDR): loga quem escreve num endereco — acha o codigo que corrompe um campo.
    public int wwatch = System.getenv("WWATCH")!=null ? (int)Long.decode(System.getenv("WWATCH").trim()).longValue() : 0;
    public int wwatchLen = System.getenv("WWATCHLEN")!=null? Integer.parseInt(System.getenv("WWATCHLEN").trim()) : 4;
    public int wwatchHits = 0;
    static final int WWMAX = System.getenv("WWMAX")!=null? Integer.parseInt(System.getenv("WWMAX").trim()) : 60;
    public void write8 (int a,int v){ int i=idx(a); if(i>=0&&i<mem.length) mem[i]=(byte)v;
        if(wwatch!=0 && a>=wwatch && a<wwatch+wwatchLen && wwatchHits<WWMAX){
            wwatchHits++;
            System.out.printf("[WW] escreve @0x%x = 0x%02x  PC=0x%x RA=0x%x fp=0x%x instr=%d%n",
                a, v&0xff, R[PC>>2], R[RA>>2], R[FP>>2], instr);
        } }
    public void write16(int a,int v){ write8(a,v); write8(a+1,v>>8); }
    public void write32(int a,int v){ write8(a,v); write8(a+1,v>>8); write8(a+2,v>>16); write8(a+3,v>>24); }

    // ================= registradores =================
    int r32(int o){ return R[o>>2]; }
    void w32(int o,int v){ if((o>>2)!=0) R[o>>2]=v; }
    int r16(int o){ return (R[o>>2] >>> ((o&2)*8)) & 0xffff; }
    void w16(int o,int v){ if((o>>2)==0) return; int i=o>>2,sh=(o&2)*8; R[i]=(R[i]&~(0xffff<<sh))|((v&0xffff)<<sh); }
    int r8(int o){ return (R[o>>2] >>> ((o&3)*8)) & 0xff; }
    void w8(int o,int v){ if((o>>2)==0) return; int i=o>>2,sh=(o&3)*8; R[i]=(R[i]&~(0xff<<sh))|((v&0xff)<<sh); }

    public int getReg(int off){ return R[off>>2]; }
    public void setReg(int off,int v){ if((off>>2)!=0) R[off>>2]=v; }

    static int sx8(int v){ return (int)(byte)v; }
    static int sx16(int v){ return (int)(short)v; }
    static int decodeRaw(int w){ return (w & 0x7FFFFFFF) | ((w<<1) & 0x80000000); }

    long fetchImm(){
        int w = read32(R[PC>>2]); R[PC>>2]+=4;
        if ((w & 0x80000000)!=0) return decodeRaw(w) & 0xffffffffL;
        int idx = w - 1;                 // refs de pool sao 1-based (nofun: poolDatas[n-1])
        if (idx>=0 && idx<poolValue.length) return poolValue[idx] & 0xffffffffL;
        return 0;
    }

    // ================= execucao =================
    // ---------------- DEPURADOR: rastreio de PC com dump de registradores ----------------
    // PCTRACE=0x25d7c,0x25e08  -> a cada vez que o PC chega num desses enderecos, imprime os
    // registradores-chave e tenta ler cada um como string (revela ponteiros de nome/caminho).
    // PCTRACEMAX=N limita o numero de dumps (padrao 40). Ferramenta generica de diagnostico:
    // serve p/ qualquer jogo onde um argumento "some" entre chamadas.
    public int[] pcTraceAddrs = parsePcTrace();
    public int pcTraceMax = System.getenv("PCTRACEMAX")!=null? Integer.parseInt(System.getenv("PCTRACEMAX").trim()) : 40;
    public int pcTraceHits = 0;
    // ITRACE=lo-hi: imprime cada instrucao executada cuja PC caia na faixa, com os registradores
    // de d/s/t resolvidos. Ferramenta de desmontagem VIVA (mostra os valores reais, nao so o codigo).
    static int[] itRange(){ String v=System.getenv("ITRACE"); if(v==null) return new int[]{0,0};
        String[] q=v.split("-"); return new int[]{(int)Long.decode(q[0].trim()).longValue(), (int)Long.decode(q[1].trim()).longValue()}; }
    static final int[] ITR = itRange(); static final int ITLO=ITR[0], ITHI=ITR[1];
    static final int ITMAX = System.getenv("ITMAX")!=null? Integer.parseInt(System.getenv("ITMAX").trim()) : 200;
    int itN=0;
    private static int[] parsePcTrace(){
        String v = System.getenv("PCTRACE");
        if(v==null || v.trim().isEmpty()) return null;
        String[] parts = v.trim().split(",");
        int[] r = new int[parts.length];
        for(int i=0;i<parts.length;i++) r[i]=(int)Long.decode(parts[i].trim()).longValue();
        return r;
    }
    private String peekStr(int addr){
        if(addr<=0) return null;
        StringBuilder sb=new StringBuilder();
        for(int k=0;k<32;k++){
            int i=idx(addr+k);
            if(i<0||i>=mem.length) return null;
            int c=mem[i]&0xff;
            if(c==0) break;
            if(c<32||c>126) return null;
            sb.append((char)c);
        }
        return sb.length()>0 ? sb.toString() : "<vazio>";
    }
    private void pcTraceDump(int pc){
        pcTraceHits++;
        StringBuilder sb=new StringBuilder();
        sb.append(String.format("[PCT] PC=0x%x instr=%d  SP=0x%x RA=0x%x%n", pc, instr, R[SP>>2], R[RA>>2]));
        int[] regs = {1,2,3,4,5,6,7,12,13,14,15,30};           // r1..r30 relevantes (numeros de registrador)
        for(int rn : regs){
            int v = R[rn];
            String st = peekStr(v);
            sb.append(String.format("      r%-2d = %-12d (0x%08x)%s", rn, v, v, st!=null? "  \""+st+"\"" : ""));
            // segue o ponteiro: [reg] como palavra, e a string apontada por ela (revela va_list -> arg %s)
            int i0=idx(v);
            if(i0>=0 && i0+3<mem.length){
                int wv = (mem[i0]&0xff)|((mem[i0+1]&0xff)<<8)|((mem[i0+2]&0xff)<<16)|((mem[i0+3]&0xff)<<24);
                String ds = peekStr(wv);
                if(ds!=null) sb.append(String.format("   -> [%d] = %d \"%s\"", v, wv, ds));
                else if(wv!=0) sb.append(String.format("   -> [%d] = %d", v, wv));
            }
            sb.append(String.format("%n"));
        }
        System.out.print(sb);
    }

    // ---------------- DEPURADOR: buffer circular dos ultimos PCs ----------------
    // LASTPC=N guarda os ultimos N enderecos executados; dumpLastPcs() imprime o rastro.
    // Serve p/ descobrir COMO o programa chegou num ponto (ex.: queda para codigo errado).
    public final int lastPcN = System.getenv("LASTPC")!=null? Integer.parseInt(System.getenv("LASTPC").trim()) : 0;
    private final int[] lastPcBuf = lastPcN>0? new int[lastPcN] : null;
    private int lastPcPos = 0; private long lastPcCount = 0;
    public void dumpLastPcs(){
        if(lastPcBuf==null) return;
        int n = (int)Math.min(lastPcCount, lastPcBuf.length);
        System.out.printf("[RASTRO] ultimos %d enderecos executados (mais antigo -> mais recente):%n", n);
        StringBuilder sb=new StringBuilder("   ");
        for(int k=0;k<n;k++){
            int v = lastPcBuf[(lastPcPos - n + k + lastPcBuf.length*2) % lastPcBuf.length];
            sb.append(String.format("0x%x ", v));
            if((k+1)%10==0){ System.out.println(sb); sb=new StringBuilder("   "); }
        }
        if(sb.length()>3) System.out.println(sb);
    }

    // ================= MULTITAREFA COOPERATIVA (igual ao nofun) =================
    // Varios jogos criam uma tarefa (vCreateTask) e esperam por ela (vTaskAlive) enquanto a tarefa
    // roda o trabalho de verdade. Sem escalonador, a tarefa nunca rodava e o jogo ficava em espera
    // ocupada p/ sempre (Worms World Party: 1,3 milhao de vGetTickCount, tela preta).
    // Modelo: cada tarefa guarda uma copia dos registradores; a troca acontece em SLEEP (yield),
    // KILLTASK/retorno-p/-RA-sentinela (fim da tarefa). Fila FIFO, sem preempcao.
    public static final int TASK_END = 0xFFF0;      // RA das tarefas: chegar aqui = tarefa terminou
    public static final class Task {
        public int id; public final int[] ctx = new int[33];
        public int stackBlock = 0; public int recv = 0;
    }
    public final java.util.ArrayList<Task> taskQueue = new java.util.ArrayList<Task>();
    public final java.util.HashMap<Integer,Task> tasks = new java.util.HashMap<Integer,Task>();
    public Task currentTask = null;
    public long sleepCount=0, switchCount=0;
    private int nextTaskId = 1;

    public int createTask(int entry, int p0, int p1, int p2, int stackTopAddr, int stackBlock){
        Task t = new Task();
        t.id = nextTaskId++; t.stackBlock = stackBlock;
        t.ctx[PC>>2] = entry;  t.ctx[SP>>2] = stackTopAddr;  t.ctx[RA>>2] = TASK_END;
        t.ctx[12] = p0; t.ctx[13] = p1; t.ctx[14] = p2;      // P0..P2 = r12..r14
        tasks.put(t.id, t); taskQueue.add(t);
        if(System.getenv("TASKLOG")!=null) System.out.printf("[TAREFAS] criada #%d entry=0x%x sp=0x%x instr=%d%n", t.id, entry, stackTopAddr, instr);
        return t.id;
    }
    public boolean taskAlive(int id){ return tasks.containsKey(id); }
    public int thisTaskId(){ return currentTask!=null? currentTask.id : 0; }
    public void disposeTask(int id){
        Task t = tasks.remove(id);
        if(t!=null){ taskQueue.remove(t); if(t.stackBlock!=0 && sdk!=null) sdk.freePtr(t.stackBlock); }
    }
    private void saveCurrentTask(){
        if(currentTask==null) return;
        System.arraycopy(R, 0, currentTask.ctx, 0, R.length);
    }
    private void loadTask(Task t){
        currentTask = t;
        System.arraycopy(t.ctx, 0, R, 0, R.length);
    }
    /** Passa p/ a proxima tarefa da fila. Fila vazia = nao ha mais nada a executar -> para a VM. */
    private void runNextTask(){
        if(taskQueue.isEmpty()){ stopped=true; return; }
        saveCurrentTask();
        switchCount++;
        loadTask(taskQueue.remove(0));
    }
    /** SLEEP: devolve a vez. Com uma tarefa so, ela volta imediatamente (no-op, como antes). */
    public void yieldTask(){
        if(taskQueue.isEmpty()) return;             // nada p/ alternar
        if(currentTask==null) currentTask = mainTask();
        saveCurrentTask();
        taskQueue.add(currentTask);
        switchCount++;
        loadTask(taskQueue.remove(0));
    }
    /** Fim da tarefa atual (KILLTASK, ou retorno p/ TASK_END/0). */
    public void terminateTask(){
        if(System.getenv("TASKLOG")!=null) System.out.printf("[TAREFAS] termina #%d PC=0x%x instr=%d fila=%d%n", currentTask!=null?currentTask.id:-1, R[PC>>2], instr, taskQueue.size());
        if(currentTask!=null && currentTask.id!=0){
            tasks.remove(currentTask.id);
            if(currentTask.stackBlock!=0 && sdk!=null) sdk.freePtr(currentTask.stackBlock);
            currentTask = null;
        }
        if(taskQueue.isEmpty()){ stopped=true; return; }
        loadTask(taskQueue.remove(0));
    }
    private Task mainTask(){
        Task t = tasks.get(0);
        if(t==null){ t = new Task(); t.id = 0; tasks.put(0, t); }
        return t;
    }

    public int pcWatch=0; public long pcWatchHits=0;
    public boolean fpWatch=false;
    public void run(long maxInstr){
        long end = instr + maxInstr;
        if(pcWatch!=0){
            while(!stopped && instr<end){ if(R[PC>>2]==pcWatch) pcWatchHits++; step(); }
            return;
        }
        if(fpWatch){
            while(!stopped && instr<end){
                int before=R[3]; int pc0=R[PC>>2];
                step();
                int after=R[3];
                if(before!=after && (after&0xffffffffL)>0x8000L && (before&0xffffffffL)<=0x8000L){
                    int w=read32(pc0);
                    System.err.printf("[FPWATCH] fp %d->%d (0x%x) @PC=0x%x op=0x%02x%n", before, after, after, pc0, w&0xff);
                }
            }
            return;
        }
        while(!stopped && instr<end){ step(); }
    }

    public long traceSteps=0;
    int prevPc=0; boolean derailReported=false;
    public void step(){
        int pc0 = R[PC>>2];
        if(pc0==TASK_END){ terminateTask(); return; }   // tarefa retornou p/ a sentinela
        if(pc0==0){                            // ret p/ RA=0 = fim de task, termino limpo
            if(!taskQueue.isEmpty()){ terminateTask(); return; }
            stopped=true;
            if(System.getenv("WHYSTOP")!=null) System.out.printf("[PARADA] MOTIVO DA PARADA: RET para RA=0 (main retornou) prevPC=0x%x instr=%d%n", prevPc, instr);
            return; }
        if(!derailReported && (pc0 < codeBase || pc0 >= codeBase+exe.codeSize)){
            derailReported=true;
            System.err.printf("WARNING: PC outside code @instr %d: prevPC=0x%x -> PC=0x%x (off 0x%x)  RA=0x%x SP=0x%x%n",
                instr, prevPc, pc0, pc0-codeBase, R[RA>>2], R[SP>>2]);
            stopped=true; return;
        }
        prevPc=pc0;
        if(lastPcBuf!=null){ lastPcBuf[lastPcPos]=pc0; lastPcPos=(lastPcPos+1)%lastPcBuf.length; lastPcCount++; }
        if(pcTraceAddrs!=null && pcTraceHits<pcTraceMax){
            for(int a : pcTraceAddrs) if(a==pc0){ pcTraceDump(pc0); break; }
        }
        int word = read32(pc0); R[PC>>2]=pc0+4;
        int op=word&0xff, d=(word>>>8)&0xff, s=(word>>>16)&0xff, t=(word>>>24)&0xff;
        instr++;
        if(ITLO!=0 && pc0>=ITLO && pc0<=ITHI && itN<ITMAX){ itN++;
        System.out.printf("  [IT] PC=0x%x op=0x%02x d=%d(r%d=%d) s=%d(r%d=%d) t=%d(r%d=%d)%n",
        pc0, op, d, d>>2, r32(d), s, s>>2, r32(s), t, t>>2, r32(t));
            System.out.printf("         imm(pc+4)=%d (0x%x)  [s+imm]=0x%x%n", read32(pc0+4), read32(pc0+4), r32(s)+read32(pc0+4)); }
        if(instr<=traceSteps) System.out.printf("  [%d] PC=0x%x op=0x%02x d=%d s=%d t=%d  sp=0x%x%n",instr,pc0,op,d,s,t,R[SP>>2]);
        switch(op){
            case 0x00: /*BREAKPOINT*/ break;
            case 0x01: /*NOP*/ break;
            case 0x02: w32(d, r32(s)+r32(t)); break;               // ADD
            case 0x03: w32(d, r32(s)&r32(t)); break;               // AND
            case 0x04: w32(d, r32(s)*r32(t)); break;               // MUL
            case 0x05: w32(d, r32(t)==0?0:r32(s)/r32(t)); break;   // DIV
            case 0x06: w32(d, r32(t)==0?0:(int)((r32(s)&0xffffffffL)/(r32(t)&0xffffffffL))); break; // DIVU
            case 0x07: w32(d, r32(s)|r32(t)); break;               // OR
            case 0x08: w32(d, r32(s)^r32(t)); break;               // XOR
            case 0x09: w32(d, r32(s)-r32(t)); break;               // SUB
            case 0x0A: w32(d, r32(s) << (r32(t)&0xff)); break;     // SLL
            case 0x0B: w32(d, r32(s) >> (r32(t)&0xff)); break;     // SRA
            case 0x0C: w32(d, r32(s) >>> (r32(t)&0xff)); break;    // SRL
            case 0x0D: w32(d, ~r32(s)); break;                     // NOT
            case 0x0E: w32(d, -r32(s)); break;                     // NEG
            case 0x0F: w32(d, sx8(r8(s))); break;                  // EXSB
            case 0x10: w32(d, sx16(r16(s))); break;                // EXSH
            case 0x11: w32(d, r32(s)); break;                      // MOV
            case 0x12: w8(d, r8(s)+r8(t)); break;                  // ADDB
            case 0x13: w8(d, r8(s)-r8(t)); break;                  // SUBB
            case 0x14: w8(d, r8(s)&r8(t)); break;                  // ANDB
            case 0x15: w8(d, r8(s)|r8(t)); break;                  // ORB
            case 0x16: w8(d, r8(s)); break;                        // MOVB
            case 0x17: w16(d, r16(s)+r16(t)); break;               // ADDH
            case 0x18: w16(d, r16(s)-r16(t)); break;               // SUBH
            case 0x19: w16(d, r16(s)&r16(t)); break;               // ANDH
            case 0x1A: w16(d, r16(s)|r16(t)); break;               // ORH
            case 0x1B: w16(d, r16(s)); break;                      // MOVH
            case 0x1C: w32(d, r32(s) << t); break;                 // SLLi
            case 0x1D: w32(d, r32(s) >> t); break;                 // SRAi
            case 0x1E: w32(d, r32(s) >>> t); break;                // SRLi
            case 0x1F: w32(d, r32(s) + sx8(t)); break;             // ADDQ
            case 0x20: w32(d, r32(s) * t); break;                  // MULQ
            case 0x21: w8(d, r8(s)+t); break;                      // ADDBi
            case 0x22: w8(d, r8(s)&t); break;                      // ANDBi
            case 0x23: w8(d, r8(s)|t); break;                      // ORBi
            case 0x24: w8(d, r8(s)<<t); break;                     // SLLB
            case 0x25: w8(d, r8(s)>>>t); break;                    // SRLB
            case 0x26: w8(d, sx8(r8(s))>>t); break;                // SRAB
            case 0x27: w16(d, r16(s)+sx8(t)); break;               // ADDHi
            case 0x28: w16(d, r16(s)&t); break;                    // ANDHi
            case 0x29: w16(d, r16(s)<<t); break;                   // SLLH
            case 0x2A: w16(d, r16(s)>>>t); break;                  // SRLH
            case 0x2B: w16(d, sx16(r16(s))>>t); break;             // SRAH
            // ---- branch imediato (brI): offset = (sbyte(t)-1)*4 ----
            case 0x2C: if(r32(d)==sx8(s)) brI(t); break;                              // BEQI
            case 0x2D: if(r32(d)!=sx8(s)) brI(t); break;                              // BNEI
            case 0x2E: if(r32(d)>=sx8(s)) brI(t); break;                              // BGEI
            case 0x2F: if((r32(d)&0xffffffffL)>=(s&0xffL)) brI(t); break;             // BGEUI
            case 0x30: if(r32(d)>sx8(s)) brI(t); break;                              // BGTI
            case 0x31: if((r32(d)&0xffffffffL)>(s&0xffL)) brI(t); break;              // BGTUI
            case 0x32: if(r32(d)<=sx8(s)) brI(t); break;                             // BLEI
            case 0x33: if((r32(d)&0xffffffffL)<=(s&0xffL)) brI(t); break;             // BLEUI
            case 0x34: if(r32(d)<sx8(s)) brI(t); break;                              // BLTI
            case 0x35: if((r32(d)&0xffffffffL)<(s&0xffL)) brI(t); break;              // BLTUI
            case 0x36: if(r8(d)==s) brI(t); break;                                    // BEQIB
            case 0x37: if(r8(d)!=s) brI(t); break;                                    // BNEIB
            case 0x38: if(sx8(r8(d))>=sx8(s)) brI(t); break;                          // BGEIB
            case 0x39: if(r8(d)>=s) brI(t); break;                                    // BGEUIB
            case 0x3A: if(sx8(r8(d))>sx8(s)) brI(t); break;                           // BGTIB
            case 0x3B: if(r8(d)>s) brI(t); break;                                     // BGTUIB
            case 0x3C: if(sx8(r8(d))<=sx8(s)) brI(t); break;                          // BLEIB
            case 0x3D: if(r8(d)<=s) brI(t); break;                                    // BLEUIB
            case 0x3E: if(sx8(r8(d))<sx8(s)) brI(t); break;                           // BLTIB
            case 0x3F: if(r8(d)<s) brI(t); break;                                     // BLTUIB
            case 0x40: w32(d, sx16((word>>>16)&0xffff)); break;    // LDQ
            case 0x41: R[PC>>2]=r32(d); break;                     // JPr
            case 0x42: w32(RA, R[PC>>2]); R[PC>>2]=r32(d); break;  // CALLr
            case 0x43: store(d,s); break;                          // STORE (d=start,s=count)
            case 0x44: restore(d,s); break;                        // RESTORE
            case 0x45: restore(d,s); R[PC>>2]=r32(RA); break;      // RET
            case 0x46: terminateTask();                            // KILLTASK: encerra a TAREFA (para a VM se for a ultima)
                if(System.getenv("WHYSTOP")!=null) System.out.printf("[PARADA] MOTIVO DA PARADA: KILLTASK @PC=0x%x RA=0x%x instr=%d%n", pc0, R[RA>>2], instr);
                break;
            case 0x47: sleepCount++; yieldTask(); break;                         // SLEEP: devolve a vez p/ outra tarefa
            case 0x48: memcpy(r32(d), r32(s), r32(t)); break;      // SYSCPY
            case 0x49: memset(r32(d), r8(s), r32(t)); break;       // SYSSET
            case 0x4A: w32(d, r32(s)+(int)fetchImm()); break;      // ADDi
            case 0x4B: w32(d, r32(s)&(int)fetchImm()); break;      // ANDi
            case 0x4C: w32(d, r32(s)*(int)fetchImm()); break;      // MULi
            case 0x4D: { int im=(int)fetchImm(); w32(d, im==0?0:r32(s)/im); break; } // DIVi
            case 0x4E: { long im=fetchImm(); w32(d, im==0?0:(int)((r32(s)&0xffffffffL)/im)); break; } // DIVUi
            case 0x4F: w32(d, r32(s)|(int)fetchImm()); break;      // ORi
            case 0x50: w32(d, r32(s)^(int)fetchImm()); break;      // XORi
            case 0x51: w32(d, r32(s)-(int)fetchImm()); break;      // SUBi
            case 0x52: { int a=r32(s)+(int)fetchImm(); write8(a, r8(d)); break; }   // STBd
            case 0x53: { int a=r32(s)+(int)fetchImm(); write16(a, r16(d)); break; } // STHd
            case 0x54: { int a=r32(s)+(int)fetchImm(); write32(a, r32(d)); break; } // STWd
            case 0x55: { int a=r32(s)+(int)fetchImm(); w32(d, sx8(read8(a))); break; }  // LDBd
            case 0x56: { int a=r32(s)+(int)fetchImm(); w32(d, sx16(read16(a))); break; }// LDHd
            case 0x57: { int a=r32(s)+(int)fetchImm(); w32(d, read32(a)); break; }      // LDWd
            case 0x58: { int a=r32(s)+(int)fetchImm(); w32(d, read8(a)); break; }       // LDBUd
            case 0x59: { int a=r32(s)+(int)fetchImm(); w32(d, read16(a)); break; }      // LDHUd
            case 0x5A: w32(d, (int)fetchImm()); break;             // LDI
            case 0x5B: {   // JPl: imediato RAW = pc-relativo; ref de POOL = endereco ABSOLUTO (igual calll).
                           // Antes usava fetchImm()+pcb+im-4, tratando o endereco de pool JA resolvido como
                           // offset -> descarrilava (ex.: Football Pro jpl pool[955] -> 0x5dce0 fora do codigo).
                int pcb=R[PC>>2]; int info=read32(pcb);
                if((info & 0x80000000)!=0){ int raw=decodeRaw(info); R[PC>>2]=pcb+raw-4; }
                else { int pidx=info-1; R[PC>>2]=(int)((pidx>=0 && pidx<poolValue.length)? poolValue[pidx] : (pcb+4)); }
                break;
            } // JPl
            case 0x5C: calll(); break;                             // CALLl
            case 0x5D: brR(r32(d)==r32(s)); break;                                    // BEQ
            case 0x5E: brR(r32(d)!=r32(s)); break;                                    // BNE
            case 0x5F: brR(r32(d)>=r32(s)); break;                                    // BGE (signed)
            case 0x60: brR((r32(d)&0xffffffffL)>=(r32(s)&0xffffffffL)); break;        // BGEU
            case 0x61: brR(r32(d)>r32(s)); break;                                     // BGT
            case 0x62: brR((r32(d)&0xffffffffL)>(r32(s)&0xffffffffL)); break;         // BGTU
            case 0x63: brR(r32(d)<=r32(s)); break;                                    // BLE
            case 0x64: brR((r32(d)&0xffffffffL)<=(r32(s)&0xffffffffL)); break;        // BLEU
            case 0x65: brR(r32(d)<r32(s)); break;                                     // BLT
            case 0x66: brR((r32(d)&0xffffffffL)<(r32(s)&0xffffffffL)); break;         // BLTU
            default:
                System.err.printf("PIP2: opcode 0x%02x not implemented @0x%x (instr %d)%n", op, pc0, instr);
                dumpLastPcs();
                stopped=true;
        }
    }

    private void brI(int t){ R[PC>>2] = R[PC>>2] + (sx8(t)-1)*4; }
    private void brR(boolean cond){
        if(cond){ int pcb=R[PC>>2]; long im=fetchImm(); R[PC>>2]=(int)(pcb+im-4); }
        else { R[PC>>2]+=4; }
    }
    private void calll(){
        int pc = R[PC>>2];                 // aponta p/ infoWord
        int info = read32(pc);
        if ((info & 0x80000000)!=0){
            int raw = decodeRaw(info);
            w32(RA, pc+4);
            R[PC>>2] = pc + raw - 4;
        } else {
            int pidx = info - 1;                 // refs de pool sao 1-based (nofun: poolDatas[n-1])
            if (pidx>=0 && pidx<poolValue.length && poolIsImport[pidx]){
                R[PC>>2] = pc+4;
                if (sdk!=null) sdk.invoke(poolName[pidx]);
            } else {
                // Nao-import: seta RA e pula pro alvo (fiel ao nofun, sem conceito de "stub").
                int target = (int)(pidx>=0 && pidx<poolValue.length? poolValue[pidx] : 0);
                w32(RA, pc+4);
                R[PC>>2] = target;
            }
        }
    }
    private void store(int start,int count){
        int sp=R[SP>>2], end=start+count;
        if(start==0){ sp-=4; write32(sp, r32(RA)); }
        else for(int i=start;i<end;i+=4){ sp-=4; write32(sp, r32(i)); }
        R[SP>>2]=sp;
    }
    private void restore(int start,int count){
        int sp=R[SP>>2], end=start-count;
        if(start==0){ w32(RA, read32(sp)); sp+=4; }
        else for(int i=start;i>end;i-=4){ w32(i, read32(sp)); sp+=4; }
        R[SP>>2]=sp;
    }
    void memcpy(int dst,int src,int n){ for(int i=0;i<n;i++) write8(dst+i, read8(src+i)); }
    void memset(int dst,int v,int n){ for(int i=0;i<n;i++) write8(dst+i, v); }

    // helpers p/ o SDK ler argumentos (p0..p3, depois pilha) e retornar em r0
    public int arg(int i){
        if(i<4) return R[(P0>>2)+i];
        return read32(R[SP>>2] + (i-4)*4);
    }
    public void ret(int v){ R[R0>>2]=v; }
}
