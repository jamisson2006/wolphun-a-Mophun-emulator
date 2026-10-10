/* Vmgp.h - loader/parser de um executavel VMGP (.mpn) mophun ja descriptografado.
 * Port de src/wolphun/vm/Vmgp.java. Expoe secoes (code/data), tabela de pool
 * (relocacao/imports), strings e recursos (sprites/tiles/mapas/...).
 *
 * Layout do arquivo: header(40) | code | data | resource | pool | string
 *
 * ERRO: o construtor Java lancava excecao; aqui usamos a fabrica Vmgp::Create()
 * que devolve NULL e preenche *err (sem excecoes -> amigo do Symbian/GCCE).
 *
 * STUB nesta etapa: scanEmbeddedZip (VFS de ZIP embutido) e no-op. So jogos 3D
 * que embutem assets num ZIP (ex.: Da Vinci Code) precisam disso; jogos 2D e a
 * maioria dos 3D simples nao. Sera portado numa etapa dedicada (inflate).
 */
#ifndef WOLPHUN_VMGP_H
#define WOLPHUN_VMGP_H

#include "vmcommon.h"

class Vmgp {
public:
    enum { HEADER_SIZE = 40 };

    struct PoolItem {
        int type;        /* 0=End 1=Local 2=Import 3=Global 4=SecRel 5=Sw16 6=Sw32 7=Const32 8=SymAdd */
        int itemTarget;  /* 1=code 2=data 4=bss */
        int meta;        /* offset de string (imports/globals) ou meta */
        int target;      /* offset alvo */
    };

    /* ---- header ---- */
    u32 heapRaw;     /* campo cru @4 */
    u64 heapSize;    /* heapRaw << 2 (bytes) */
    int flags;
    i64 stackSize;   /* u16 @10 << 2 (bytes) */
    int codeSize, dataSize, bssSize, resSize, poolCount, stringSize;

    /* ---- buffer e offsets de secao ---- */
    u8* all;         /* arquivo inteiro (de propriedade deste objeto) */
    int allLen;
    bool wasEncrypted;  /* a ROM tinha DRM e foi decifrada ao carregar */
    int codeOff, dataOff, resOff, poolOff, strOff;

    /* ---- pool ---- */
    PoolItem* pool;  /* poolCount itens */

    /* heap pedido pelo jogo, alinhado a 16 (formula do nofun). */
    u64 heapBytesRequested() const { return (heapSize + 15u) & ~(u64)15u; }

    /* fabrica: NULL em erro, com *err apontando p/ mensagem estatica. `d` e copiado. */
    static Vmgp* Create(const u8* d, int len, const char** err);
    ~Vmgp();

    int    resourceCount()  const { return resCount; }
    int    resourceOffset(int i) const { return resStart[i]; }
    int    resourceLength(int i) const { return resLen[i]; }

    /* string da secao de strings no offset dado (ponteiro p/ dentro de `all`, 0-terminada). */
    const char* getString(int off) const;

    /* true se o executavel IMPORTA o simbolo (pool type 2 = Import). */
    bool importsSymbol(const char* name) const;

    /* ---- VFS embutido (STUB nesta etapa) ---- */
    bool  hasArchive()  const { return archiveZip != 0; }
    const u8* archiveBytes() const { return archiveZip; }
    int   archiveLen()  const { return archiveZipLen; }
    const u8* vfsGet(const char* name, int* outLen) const; /* NULL enquanto stub */

private:
    Vmgp();
    bool parse(const char** err);
    void scanEmbeddedZip(); /* STUB: no-op por enquanto */

    int* resStart;
    int* resLen;
    int  resCount;

    u8* archiveZip;      /* NULL = sem ZIP embutido */
    int archiveZipLen;
};

#endif /* WOLPHUN_VMGP_H */
