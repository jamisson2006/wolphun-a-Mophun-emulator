/* Pip2.h - interpretador da VM PIP2 do mophun (o "processador" virtual do jogo).
 * Port de src/wolphun/vm/Pip2.java. Registradores de 32 bits enderecados por
 * byte-offset; instrucoes de 4 ou 8 bytes; palavra = [opcode|d|s|t].
 *
 * Diferencas em relacao ao Java:
 *  - Removido TODO o codigo de depuracao por variavel de ambiente (PCTRACE,
 *    ITRACE, WWATCH, LASTPC, traceSteps...) - inutil no aparelho e so pesa.
 *  - Sem excecoes: acessos de memoria fora de faixa sao clampados/ignorados.
 *  - Multitarefa cooperativa reimplementada com listas ligadas (sem HashMap).
 */
#ifndef WOLPHUN_PIP2_H
#define WOLPHUN_PIP2_H

#include "vmcommon.h"
#include "Vmgp.h"

class Sdk;

class Pip2 {
public:
    /* registradores (byte-offset) */
    enum {
        ZERO=0, SP=4, RA=8, FP=12,
        S0=16, P0=48, P1=52, P2=56, P3=60, R0=120, R1=124, PC=128
    };
    enum { NREG = 64 };           /* folga sobre os 33 do Java (d/s/t sao bytes) */
    enum { TASK_END = 0xFFF0 };

    i32 R[NREG];                  /* R[i] = registrador no byte-offset i*4 */
    u8* mem;                      /* memoria linear da VM */
    int memLen;
    int base;                     /* endereco logico do inicio (code) */
    int codeBase, dataBase, bssBase, heapBase, heapEnd, stackTop;

    Vmgp* exe;
    Sdk*  sdk;                    /* atribuido pela casca apos construir */
    bool  stopped;
    i64   instr;
    i64   stubCalls;

    Pip2(Vmgp* exe, int heapBytes, int stackBytes);
    ~Pip2();

    static int align(int v){ return (v + 15) & ~15; }

    /* roda os construtores globais (C++ static init) antes do main. */
    void runConstructors();

    /* execucao */
    void run(i64 maxInstr);
    void step();

    /* memoria */
    int  idx(int addr) const { return addr - base; }
    int  read8 (int a) const { int i=idx(a); return (i>=0 && i<memLen) ? (mem[i]&0xff) : 0; }
    int  read16(int a) const { return read8(a) | (read8(a+1)<<8); }
    int  read32(int a) const { return read8(a) | (read8(a+1)<<8) | (read8(a+2)<<16) | (read8(a+3)<<24); }
    void write8 (int a,int v){ int i=idx(a); if(i>=0 && i<memLen) mem[i]=(u8)v; }
    void write16(int a,int v){ write8(a,v); write8(a+1,v>>8); }
    void write32(int a,int v){ write8(a,v); write8(a+1,v>>8); write8(a+2,v>>16); write8(a+3,v>>24); }

    /* registradores */
    int  getReg(int off) const { return R[off>>2]; }
    void setReg(int off,int v){ if((off>>2)!=0) R[off>>2]=v; }

    /* ABI p/ o SDK: args em p0..p3 depois pilha; retorno em r0 */
    int  arg(int i) const { if(i<4) return R[(P0>>2)+i]; return read32(R[SP>>2] + (i-4)*4); }
    void ret(int v){ R[R0>>2]=v; }

    /* ---- multitarefa cooperativa ---- */
    struct Task {
        int id;
        i32 ctx[NREG];
        int stackBlock;
        int recv;
        Task* qnext;              /* fila FIFO */
        Task* lnext;              /* lista de todas as tarefas (lookup/dispose) */
    };
    int  createTask(int entry, int p0, int p1, int p2, int stackTopAddr, int stackBlock);
    bool taskAlive(int id);
    int  thisTaskId() const { return currentTask ? currentTask->id : 0; }
    /* acesso usado pelo SDK (vSend / vReceive / vReceiveAny) */
    Task* findTaskPub(int id){ return findTask(id); }
    Task* currentTaskPub(){ return currentTask; }
    void disposeTask(int id);
    void yieldTask();
    void terminateTask();

    i64 sleepCount, switchCount;

private:
    void layoutAndLoad(int heapBytes, int stackBytes);
    void relocate();
    void callFunction(int fn);

    /* pool resolvido */
    u32*         poolValue;
    bool*        poolIsImport;
    const char** poolName;
    /* id da API do SDK por entrada de pool, resolvido na PRIMEIRA chamada (-2 = ainda
       nao resolvido). Evita um strcmp por chamada de SDK no caminho quente - importa
       num ARM de 369 MHz, onde um jogo faz dezenas de milhares de chamadas por segundo. */
    int*         poolApiId;

    /* acesso a sub-registradores (16/8 bits dentro de um registrador de 32) */
    int  r32(int o) const { return R[o>>2]; }
    void w32(int o,int v){ if((o>>2)!=0) R[o>>2]=v; }
    int  r16(int o) const { return (int)(((u32)R[o>>2] >> ((o&2)*8)) & 0xffff); }
    void w16(int o,int v){ if((o>>2)==0) return; int i=o>>2,sh=(o&2)*8; R[i]=(R[i] & ~(0xffff<<sh)) | ((v&0xffff)<<sh); }
    int  r8(int o) const { return (int)(((u32)R[o>>2] >> ((o&3)*8)) & 0xff); }
    void w8(int o,int v){ if((o>>2)==0) return; int i=o>>2,sh=(o&3)*8; R[i]=(R[i] & ~(0xff<<sh)) | ((v&0xff)<<sh); }

    u32 fetchImm();

    /* branches / chamadas */
    void brI(int t){ R[PC>>2] = R[PC>>2] + (sx8(t)-1)*4; }
    void brR(bool cond);
    void calll();
    void store(int start,int count);
    void restore(int start,int count);
    void doMemcpy(int dst,int src,int n){ for(int i=0;i<n;i++) write8(dst+i, read8(src+i)); }
    void doMemset(int dst,int v,int n){ for(int i=0;i<n;i++) write8(dst+i, v); }

    static int sx8(int v){ return (i32)(i8)v; }
    static int sx16(int v){ return (i32)(i16)v; }
    static int decodeRaw(int w){ return (w & 0x7FFFFFFF) | ((w<<1) & 0x80000000); }

    /* tarefas: listas ligadas */
    Task* qHead; Task* qTail;     /* fila FIFO */
    Task* allHead;                /* todas as tarefas vivas */
    Task* currentTask;
    int   nextTaskId;
    bool  derailed;

    Task* findTask(int id);
    void  addAll(Task* t);
    void  removeAll(Task* t);
    void  enqueue(Task* t);
    Task* dequeue();
    void  removeFromQueue(Task* t);
    bool  queueEmpty() const { return qHead==0; }
    void  saveCurrentTask();
    void  loadTask(Task* t);
    Task* mainTask();
};

#endif /* WOLPHUN_PIP2_H */
