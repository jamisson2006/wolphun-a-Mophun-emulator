/* Pip2.h - the interpreter for Mophun's PIP2 VM, the game's virtual "processor".
 * Ported from src/wolphun/vm/Pip2.java. 32-bit registers addressed by byte offset;
 * instructions of 4 or 8 bytes; a word is [opcode|d|s|t].
 *
 * Differences from the Java version:
 *  - ALL the environment-variable debugging code is gone (PCTRACE, ITRACE, WWATCH,
 *    LASTPC, traceSteps...) -- useless on the phone and pure overhead.
 *  - No exceptions: out-of-range memory accesses are clamped or ignored.
 *  - Cooperative multitasking reimplemented with linked lists, no HashMap.
 */
#ifndef WOLPHUN_PIP2_H
#define WOLPHUN_PIP2_H

#include "vmcommon.h"
#include "Vmgp.h"

class Sdk;

class Pip2 {
public:
    /* registers, addressed by byte offset */
    enum {
        ZERO=0, SP=4, RA=8, FP=12,
        S0=16, P0=48, P1=52, P2=56, P3=60, R0=120, R1=124, PC=128
    };
    enum { NREG = 64 };           /* headroom over Java's 33, since d/s/t are bytes */
    enum { TASK_END = 0xFFF0 };

    i32 R[NREG];                  /* R[i] = the register at byte offset i*4 */
    u8* mem;                      /* the VM's linear memory */
    int memLen;
    int base;                     /* logical address of the start (code) */
    int codeBase, dataBase, bssBase, heapBase, heapEnd, stackTop;

    Vmgp* exe;
    Sdk*  sdk;                    /* assigned by the shell after construction */
    bool  stopped;
    i64   instr;
    i64   stubCalls;

    Pip2(Vmgp* exe, int heapBytes, int stackBytes);
    ~Pip2();

    static int align(int v){ return (v + 15) & ~15; }

    /* runs the global constructors (C++ static init) before main. */
    void runConstructors();

    /* execution */
    void run(i64 maxInstr);
    void step();

    /* memory */
    int  idx(int addr) const { return addr - base; }
    int  read8 (int a) const { int i=idx(a); return (i>=0 && i<memLen) ? (mem[i]&0xff) : 0; }
    int  read16(int a) const { return read8(a) | (read8(a+1)<<8); }
    int  read32(int a) const { return read8(a) | (read8(a+1)<<8) | (read8(a+2)<<16) | (read8(a+3)<<24); }
    void write8 (int a,int v){ int i=idx(a); if(i>=0 && i<memLen) mem[i]=(u8)v; }
    void write16(int a,int v){ write8(a,v); write8(a+1,v>>8); }
    void write32(int a,int v){ write8(a,v); write8(a+1,v>>8); write8(a+2,v>>16); write8(a+3,v>>24); }

    /* registers */
    int  getReg(int off) const { return R[off>>2]; }
    void setReg(int off,int v){ if((off>>2)!=0) R[off>>2]=v; }

    /* SDK ABI: arguments in p0..p3 then on the stack; the result in r0 */
    int  arg(int i) const { if(i<4) return R[(P0>>2)+i]; return read32(R[SP>>2] + (i-4)*4); }
    void ret(int v){ R[R0>>2]=v; }

    /* ---- cooperative multitasking ---- */
    struct Task {
        int id;
        i32 ctx[NREG];
        int stackBlock;
        int recv;
        Task* qnext;              /* the FIFO queue */
        Task* lnext;              /* the list of every task, for lookup and dispose */
    };
    int  createTask(int entry, int p0, int p1, int p2, int stackTopAddr, int stackBlock);
    bool taskAlive(int id);
    int  thisTaskId() const { return currentTask ? currentTask->id : 0; }
    /* accessors the SDK uses (vSend / vReceive / vReceiveAny) */
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

    /* the resolved pool */
    u32*         poolValue;
    bool*        poolIsImport;
    const char** poolName;
    /* The SDK API id per pool entry, resolved on the FIRST call (-2 = not resolved
       yet). This avoids one strcmp per SDK call on the hot path -- it matters on a
       369 MHz ARM, where a game makes tens of thousands of calls per second. */
    int*         poolApiId;

    /* sub-register access: 16 or 8 bits inside a 32-bit register */
    int  r32(int o) const { return R[o>>2]; }
    void w32(int o,int v){ if((o>>2)!=0) R[o>>2]=v; }
    int  r16(int o) const { return (int)(((u32)R[o>>2] >> ((o&2)*8)) & 0xffff); }
    void w16(int o,int v){ if((o>>2)==0) return; int i=o>>2,sh=(o&2)*8; R[i]=(R[i] & ~(0xffff<<sh)) | ((v&0xffff)<<sh); }
    int  r8(int o) const { return (int)(((u32)R[o>>2] >> ((o&3)*8)) & 0xff); }
    void w8(int o,int v){ if((o>>2)==0) return; int i=o>>2,sh=(o&3)*8; R[i]=(R[i] & ~(0xff<<sh)) | ((v&0xff)<<sh); }

    u32 fetchImm();

    /* branches and calls */
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

    /* tasks: linked lists */
    Task* qHead; Task* qTail;     /* the FIFO queue */
    Task* allHead;                /* every live task */
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
