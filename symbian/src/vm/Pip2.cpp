/* Pip2.cpp - port de src/wolphun/vm/Pip2.java (ver Pip2.h). */
#include "Pip2.h"
#include "Sdk.h"

Pip2::Pip2(Vmgp* exe_, int heapBytes, int stackBytes)
: mem(0), memLen(0), base(0),
  codeBase(0), dataBase(0), bssBase(0), heapBase(0), heapEnd(0), stackTop(0),
  exe(exe_), sdk(0), stopped(false), instr(0), stubCalls(0),
  sleepCount(0), switchCount(0),
  poolValue(0), poolIsImport(0), poolName(0),
  qHead(0), qTail(0), allHead(0), currentTask(0), nextTaskId(1), derailed(false)
{
    for (int i=0;i<NREG;i++) R[i]=0;
    int pc = exe->poolCount > 0 ? exe->poolCount : 1;
    poolValue    = new u32[pc];
    poolIsImport = new bool[pc];
    poolName     = new const char*[pc];
    poolApiId    = new int[pc];
    for (int i=0;i<pc;i++){ poolValue[i]=0; poolIsImport[i]=false; poolName[i]=0; poolApiId[i]=-2; }
    layoutAndLoad(heapBytes, stackBytes);
}

Pip2::~Pip2(){
    delete[] mem;
    delete[] poolValue;
    delete[] poolIsImport;
    delete[] poolName;
    delete[] poolApiId;
    Task* t = allHead;
    while (t){ Task* n = t->lnext; delete t; t = n; }
}

void Pip2::layoutAndLoad(int heapBytes, int stackBytes){
    base     = 0x00010000;
    codeBase = base;
    dataBase = codeBase + align(exe->codeSize);
    bssBase  = dataBase + align(exe->dataSize);
    heapBase = bssBase  + align(exe->bssSize);
    heapEnd  = heapBase + heapBytes;
    stackTop = heapEnd  + stackBytes;
    int total = stackTop - base + 16;
    memLen = total;
    mem = new u8[total];
    wpMemSet(mem, 0, total);

    wpMemCopy(mem + (codeBase-base), exe->all + exe->codeOff, exe->codeSize);
    wpMemCopy(mem + (dataBase-base), exe->all + exe->dataOff, exe->dataSize);
    /* bss ja zerado */

    relocate();

    R[SP>>2] = stackTop;
    R[FP>>2] = stackTop;
    R[PC>>2] = codeBase;   /* entry point = inicio do codigo */
}

void Pip2::relocate(){
    for (int i=0;i<exe->poolCount;i++){
        Vmgp::PoolItem& it = exe->pool[i];
        switch (it.type){
            case 2: /* Import */
                poolIsImport[i]=true; poolName[i]=exe->getString(it.meta); poolValue[i]=0; break;
            case 1: case 3: { /* Local / Global */
                int b = (it.itemTarget==1)?codeBase:(it.itemTarget==2)?dataBase:bssBase;
                poolValue[i] = (u32)(b + it.target); break;
            }
            case 7: /* Const32 */
                poolValue[i] = (u32)it.target; break;
            case 4: { /* SectionRelativeReloc */
                int addr = ((it.itemTarget==1)?codeBase:dataBase) + it.target;
                int v = read32(addr);
                int b = (it.meta==1)?codeBase:(it.meta==2)?dataBase:bssBase;
                write32(addr, v + b);
                poolValue[i] = (u32)(v + b); break;
            }
            case 5: case 6: /* Swap16/32 (host LE: sem troca) */
                poolValue[i] = (u32)it.meta; break;
            case 8: { /* SymAdd: poolItems[meta-1] + target */
                int ref = it.meta-1;
                u32 rv = (ref>=0 && ref<exe->poolCount) ? poolValue[ref] : 0;
                poolValue[i] = rv + (u32)it.target; break;
            }
            default: poolValue[i]=0;
        }
    }
}

void Pip2::runConstructors(){
    /* O toolchain mophun exporta um Global "~C" apontando p/ a __CTOR_LIST__
       (lista de enderecos de construtores em data, terminada em 0). Jogos em C
       puro (ex.: HC2) nao tem "~C" -> no-op. */
    i64 listAddr = -1;
    for (int i=0;i<exe->poolCount;i++){
        Vmgp::PoolItem& it = exe->pool[i];
        if (it.type==3 && wpStrCmp("~C", exe->getString(it.meta))==0){ listAddr = poolValue[i]; break; }
    }
    if (listAddr < 0) return;
    int addr = (int)listAddr;
    for (int n=0; n<256; n++){           /* enderecos absolutos ate o terminador 0 */
        int ctor = read32(addr); addr += 4;
        if (ctor==0) break;
        callFunction(ctor);
    }
}

/* Chama uma funcao da VM (PC=fn, RA=sentinela) e roda ate retornar. */
void Pip2::callFunction(int fn){
    const int SENT = 0xFFFC;
    int sRA=R[RA>>2], sPC=R[PC>>2], sSP=R[SP>>2], sFP=R[FP>>2]; bool sStop=stopped;
    R[RA>>2]=SENT; R[PC>>2]=fn;
    i64 g=0;
    while (!stopped && R[PC>>2]!=SENT && g<50000000LL){ step(); g++; }
    stopped=sStop; R[RA>>2]=sRA; R[PC>>2]=sPC; R[SP>>2]=sSP; R[FP>>2]=sFP;
}

u32 Pip2::fetchImm(){
    int w = read32(R[PC>>2]); R[PC>>2]+=4;
    if ((w & 0x80000000)!=0) return (u32)decodeRaw(w);
    int i = w - 1;                       /* refs de pool sao 1-based */
    if (i>=0 && i<exe->poolCount) return poolValue[i];
    return 0;
}

/* ---------------- multitarefa cooperativa ---------------- */
Pip2::Task* Pip2::findTask(int id){
    for (Task* t=allHead; t; t=t->lnext) if (t->id==id) return t;
    return 0;
}
void Pip2::addAll(Task* t){ t->lnext = allHead; allHead = t; }
void Pip2::removeAll(Task* t){
    Task** pp = &allHead;
    while (*pp){ if (*pp==t){ *pp = t->lnext; return; } pp = &(*pp)->lnext; }
}
void Pip2::enqueue(Task* t){
    t->qnext = 0;
    if (qTail) qTail->qnext = t; else qHead = t;
    qTail = t;
}
Pip2::Task* Pip2::dequeue(){
    Task* t = qHead;
    if (!t) return 0;
    qHead = t->qnext;
    if (!qHead) qTail = 0;
    t->qnext = 0;
    return t;
}
void Pip2::removeFromQueue(Task* t){
    Task** pp = &qHead; Task* prev = 0;
    while (*pp){
        if (*pp==t){ *pp = t->qnext; if (qTail==t) qTail = prev; t->qnext=0; return; }
        prev = *pp; pp = &(*pp)->qnext;
    }
}
void Pip2::saveCurrentTask(){
    if (!currentTask) return;
    wpMemCopy(currentTask->ctx, R, (int)sizeof(R));
}
void Pip2::loadTask(Task* t){
    currentTask = t;
    wpMemCopy(R, t->ctx, (int)sizeof(R));
}
Pip2::Task* Pip2::mainTask(){
    Task* t = findTask(0);
    if (!t){ t = new Task(); wpMemSet(t,0,(int)sizeof(Task)); t->id=0; addAll(t); }
    return t;
}
int Pip2::createTask(int entry, int p0, int p1, int p2, int stackTopAddr, int stackBlock){
    Task* t = new Task(); wpMemSet(t,0,(int)sizeof(Task));
    t->id = nextTaskId++; t->stackBlock = stackBlock;
    t->ctx[PC>>2]=entry; t->ctx[SP>>2]=stackTopAddr; t->ctx[RA>>2]=TASK_END;
    t->ctx[12]=p0; t->ctx[13]=p1; t->ctx[14]=p2;    /* P0..P2 = r12..r14 */
    addAll(t); enqueue(t);
    return t->id;
}
bool Pip2::taskAlive(int id){ return findTask(id)!=0; }
void Pip2::disposeTask(int id){
    Task* t = findTask(id);
    if (!t) return;
    removeAll(t); removeFromQueue(t);
    if (t->stackBlock!=0 && sdk) sdk->freePtr(t->stackBlock);
    if (t!=currentTask) delete t;
}
void Pip2::yieldTask(){
    if (queueEmpty()) return;            /* nada p/ alternar */
    if (!currentTask) currentTask = mainTask();
    saveCurrentTask();
    enqueue(currentTask);
    switchCount++;
    loadTask(dequeue());
}
void Pip2::terminateTask(){
    if (currentTask && currentTask->id!=0){
        Task* dead = currentTask;
        removeAll(dead);
        if (dead->stackBlock!=0 && sdk) sdk->freePtr(dead->stackBlock);
        currentTask = 0;
        delete dead;
    }
    if (queueEmpty()){ stopped=true; return; }
    loadTask(dequeue());
}

/* ---------------- execucao ---------------- */
void Pip2::run(i64 maxInstr){
    i64 end = instr + maxInstr;
    while (!stopped && instr<end) step();
}

void Pip2::step(){
    int pc0 = R[PC>>2];
    if (pc0==TASK_END){ terminateTask(); return; }   /* tarefa retornou p/ sentinela */
    if (pc0==0){                                      /* ret p/ RA=0 = fim limpo */
        if (!queueEmpty()){ terminateTask(); return; }
        stopped=true; return;
    }
    if (!derailed && (pc0 < codeBase || pc0 >= codeBase+exe->codeSize)){
        derailed=true; stopped=true; return;          /* PC fora do codigo: aborta seguro */
    }

    int word = read32(pc0); R[PC>>2]=pc0+4;
    int op = word & 0xff;
    int d  = (int)(((u32)word>>8)  & 0xff);
    int s  = (int)(((u32)word>>16) & 0xff);
    int t  = (int)(((u32)word>>24) & 0xff);
    instr++;

    switch (op){
        case 0x00: /*BREAKPOINT*/ break;
        case 0x01: /*NOP*/ break;
        case 0x02: w32(d, r32(s)+r32(t)); break;                                   /* ADD */
        case 0x03: w32(d, r32(s)&r32(t)); break;                                   /* AND */
        case 0x04: w32(d, r32(s)*r32(t)); break;                                   /* MUL */
        case 0x05: w32(d, r32(t)==0?0:r32(s)/r32(t)); break;                       /* DIV */
        case 0x06: w32(d, r32(t)==0?0:(i32)((u32)r32(s)/(u32)r32(t))); break;      /* DIVU */
        case 0x07: w32(d, r32(s)|r32(t)); break;                                   /* OR */
        case 0x08: w32(d, r32(s)^r32(t)); break;                                   /* XOR */
        case 0x09: w32(d, r32(s)-r32(t)); break;                                   /* SUB */
        case 0x0A: w32(d, r32(s) << (r32(t)&0xff)); break;                         /* SLL */
        case 0x0B: w32(d, r32(s) >> (r32(t)&0xff)); break;                         /* SRA (arith) */
        case 0x0C: w32(d, (i32)((u32)r32(s) >> (r32(t)&0xff))); break;             /* SRL (logic) */
        case 0x0D: w32(d, ~r32(s)); break;                                         /* NOT */
        case 0x0E: w32(d, -r32(s)); break;                                         /* NEG */
        case 0x0F: w32(d, sx8(r8(s))); break;                                      /* EXSB */
        case 0x10: w32(d, sx16(r16(s))); break;                                    /* EXSH */
        case 0x11: w32(d, r32(s)); break;                                          /* MOV */
        case 0x12: w8(d, r8(s)+r8(t)); break;                                      /* ADDB */
        case 0x13: w8(d, r8(s)-r8(t)); break;                                      /* SUBB */
        case 0x14: w8(d, r8(s)&r8(t)); break;                                      /* ANDB */
        case 0x15: w8(d, r8(s)|r8(t)); break;                                      /* ORB */
        case 0x16: w8(d, r8(s)); break;                                            /* MOVB */
        case 0x17: w16(d, r16(s)+r16(t)); break;                                   /* ADDH */
        case 0x18: w16(d, r16(s)-r16(t)); break;                                   /* SUBH */
        case 0x19: w16(d, r16(s)&r16(t)); break;                                   /* ANDH */
        case 0x1A: w16(d, r16(s)|r16(t)); break;                                   /* ORH */
        case 0x1B: w16(d, r16(s)); break;                                          /* MOVH */
        case 0x1C: w32(d, r32(s) << t); break;                                     /* SLLi */
        case 0x1D: w32(d, r32(s) >> t); break;                                     /* SRAi */
        case 0x1E: w32(d, (i32)((u32)r32(s) >> t)); break;                         /* SRLi */
        case 0x1F: w32(d, r32(s) + sx8(t)); break;                                 /* ADDQ */
        case 0x20: w32(d, r32(s) * t); break;                                      /* MULQ */
        case 0x21: w8(d, r8(s)+t); break;                                          /* ADDBi */
        case 0x22: w8(d, r8(s)&t); break;                                          /* ANDBi */
        case 0x23: w8(d, r8(s)|t); break;                                          /* ORBi */
        case 0x24: w8(d, r8(s)<<t); break;                                         /* SLLB */
        case 0x25: w8(d, (int)((u32)(r8(s)) >> t)); break;                         /* SRLB */
        case 0x26: w8(d, sx8(r8(s))>>t); break;                                    /* SRAB */
        case 0x27: w16(d, r16(s)+sx8(t)); break;                                   /* ADDHi */
        case 0x28: w16(d, r16(s)&t); break;                                        /* ANDHi */
        case 0x29: w16(d, r16(s)<<t); break;                                       /* SLLH */
        case 0x2A: w16(d, (int)((u32)(r16(s)) >> t)); break;                       /* SRLH */
        case 0x2B: w16(d, sx16(r16(s))>>t); break;                                 /* SRAH */
        /* ---- branch imediato (brI): offset = (sbyte(t)-1)*4 ---- */
        case 0x2C: if(r32(d)==sx8(s)) brI(t); break;                               /* BEQI */
        case 0x2D: if(r32(d)!=sx8(s)) brI(t); break;                               /* BNEI */
        case 0x2E: if(r32(d)>=sx8(s)) brI(t); break;                               /* BGEI */
        case 0x2F: if((u32)r32(d)>=(u32)(s&0xff)) brI(t); break;                   /* BGEUI */
        case 0x30: if(r32(d)>sx8(s)) brI(t); break;                                /* BGTI */
        case 0x31: if((u32)r32(d)>(u32)(s&0xff)) brI(t); break;                    /* BGTUI */
        case 0x32: if(r32(d)<=sx8(s)) brI(t); break;                               /* BLEI */
        case 0x33: if((u32)r32(d)<=(u32)(s&0xff)) brI(t); break;                   /* BLEUI */
        case 0x34: if(r32(d)<sx8(s)) brI(t); break;                                /* BLTI */
        case 0x35: if((u32)r32(d)<(u32)(s&0xff)) brI(t); break;                    /* BLTUI */
        case 0x36: if(r8(d)==s) brI(t); break;                                     /* BEQIB */
        case 0x37: if(r8(d)!=s) brI(t); break;                                     /* BNEIB */
        case 0x38: if(sx8(r8(d))>=sx8(s)) brI(t); break;                           /* BGEIB */
        case 0x39: if(r8(d)>=s) brI(t); break;                                     /* BGEUIB */
        case 0x3A: if(sx8(r8(d))>sx8(s)) brI(t); break;                            /* BGTIB */
        case 0x3B: if(r8(d)>s) brI(t); break;                                      /* BGTUIB */
        case 0x3C: if(sx8(r8(d))<=sx8(s)) brI(t); break;                           /* BLEIB */
        case 0x3D: if(r8(d)<=s) brI(t); break;                                     /* BLEUIB */
        case 0x3E: if(sx8(r8(d))<sx8(s)) brI(t); break;                            /* BLTIB */
        case 0x3F: if(r8(d)<s) brI(t); break;                                      /* BLTUIB */
        case 0x40: w32(d, sx16((int)(((u32)word>>16)&0xffff))); break;             /* LDQ */
        case 0x41: R[PC>>2]=r32(d); break;                                         /* JPr */
        case 0x42: w32(RA, R[PC>>2]); R[PC>>2]=r32(d); break;                      /* CALLr */
        case 0x43: store(d,s); break;                                              /* STORE */
        case 0x44: restore(d,s); break;                                            /* RESTORE */
        case 0x45: restore(d,s); R[PC>>2]=r32(RA); break;                          /* RET */
        case 0x46: terminateTask(); break;                                         /* KILLTASK */
        case 0x47: sleepCount++; yieldTask(); break;                               /* SLEEP */
        case 0x48: doMemcpy(r32(d), r32(s), r32(t)); break;                        /* SYSCPY */
        case 0x49: doMemset(r32(d), r8(s), r32(t)); break;                         /* SYSSET */
        case 0x4A: w32(d, r32(s)+(int)fetchImm()); break;                          /* ADDi */
        case 0x4B: w32(d, r32(s)&(int)fetchImm()); break;                          /* ANDi */
        case 0x4C: w32(d, r32(s)*(int)fetchImm()); break;                          /* MULi */
        case 0x4D: { int im=(int)fetchImm(); w32(d, im==0?0:r32(s)/im); break; }   /* DIVi */
        case 0x4E: { u32 im=fetchImm(); w32(d, im==0?0:(i32)((u32)r32(s)/im)); break; } /* DIVUi */
        case 0x4F: w32(d, r32(s)|(int)fetchImm()); break;                          /* ORi */
        case 0x50: w32(d, r32(s)^(int)fetchImm()); break;                          /* XORi */
        case 0x51: w32(d, r32(s)-(int)fetchImm()); break;                          /* SUBi */
        case 0x52: { int a=r32(s)+(int)fetchImm(); write8(a, r8(d)); break; }      /* STBd */
        case 0x53: { int a=r32(s)+(int)fetchImm(); write16(a, r16(d)); break; }    /* STHd */
        case 0x54: { int a=r32(s)+(int)fetchImm(); write32(a, r32(d)); break; }    /* STWd */
        case 0x55: { int a=r32(s)+(int)fetchImm(); w32(d, sx8(read8(a))); break; } /* LDBd */
        case 0x56: { int a=r32(s)+(int)fetchImm(); w32(d, sx16(read16(a))); break; } /* LDHd */
        case 0x57: { int a=r32(s)+(int)fetchImm(); w32(d, read32(a)); break; }     /* LDWd */
        case 0x58: { int a=r32(s)+(int)fetchImm(); w32(d, read8(a)); break; }      /* LDBUd */
        case 0x59: { int a=r32(s)+(int)fetchImm(); w32(d, read16(a)); break; }     /* LDHUd */
        case 0x5A: w32(d, (int)fetchImm()); break;                                 /* LDI */
        case 0x5B: {   /* JPl: imediato RAW = pc-relativo; ref de POOL = endereco ABSOLUTO. */
            int pcb=R[PC>>2]; int info=read32(pcb);
            if ((info & 0x80000000)!=0){ int raw=decodeRaw(info); R[PC>>2]=pcb+raw-4; }
            else { int pidx=info-1; R[PC>>2]=(pidx>=0 && pidx<exe->poolCount)? (int)poolValue[pidx] : (pcb+4); }
            break;
        }
        case 0x5C: calll(); break;                                                 /* CALLl */
        case 0x5D: brR(r32(d)==r32(s)); break;                                     /* BEQ */
        case 0x5E: brR(r32(d)!=r32(s)); break;                                     /* BNE */
        case 0x5F: brR(r32(d)>=r32(s)); break;                                     /* BGE (signed) */
        case 0x60: brR((u32)r32(d)>=(u32)r32(s)); break;                           /* BGEU */
        case 0x61: brR(r32(d)>r32(s)); break;                                      /* BGT */
        case 0x62: brR((u32)r32(d)>(u32)r32(s)); break;                            /* BGTU */
        case 0x63: brR(r32(d)<=r32(s)); break;                                     /* BLE */
        case 0x64: brR((u32)r32(d)<=(u32)r32(s)); break;                           /* BLEU */
        case 0x65: brR(r32(d)<r32(s)); break;                                      /* BLT */
        case 0x66: brR((u32)r32(d)<(u32)r32(s)); break;                            /* BLTU */
        default:
            /* opcode nao implementado: aborta seguro (sem console no aparelho). */
            stopped=true;
    }
}

void Pip2::brR(bool cond){
    if (cond){ int pcb=R[PC>>2]; u32 im=fetchImm(); R[PC>>2]=(int)(pcb+(int)im-4); }
    else { R[PC>>2]+=4; }
}
void Pip2::calll(){
    int pc = R[PC>>2];               /* aponta p/ infoWord */
    int info = read32(pc);
    if ((info & 0x80000000)!=0){
        int raw = decodeRaw(info);
        w32(RA, pc+4);
        R[PC>>2] = pc + raw - 4;
    } else {
        int pidx = info - 1;         /* refs de pool sao 1-based */
        if (pidx>=0 && pidx<exe->poolCount && poolIsImport[pidx]){
            R[PC>>2] = pc+4;
            if (sdk){
                if (poolApiId[pidx] == -2)            /* resolve uma unica vez */
                    poolApiId[pidx] = Sdk::resolve(poolName[pidx]);
                sdk->invokeId(poolApiId[pidx]);
            }
        } else {
            int target = (pidx>=0 && pidx<exe->poolCount)? (int)poolValue[pidx] : 0;
            w32(RA, pc+4);
            R[PC>>2] = target;
        }
    }
}
void Pip2::store(int start,int count){
    int sp=R[SP>>2], end=start+count;
    if (start==0){ sp-=4; write32(sp, r32(RA)); }
    else for (int i=start;i<end;i+=4){ sp-=4; write32(sp, r32(i)); }
    R[SP>>2]=sp;
}
void Pip2::restore(int start,int count){
    int sp=R[SP>>2], end=start-count;
    if (start==0){ w32(RA, read32(sp)); sp+=4; }
    else for (int i=start;i>end;i-=4){ w32(i, read32(sp)); sp+=4; }
    R[SP>>2]=sp;
}
