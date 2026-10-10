/* Vmgp.cpp - port de src/wolphun/vm/Vmgp.java (ver Vmgp.h). */
#include "Vmgp.h"
#include "MophunCrypt.h"

Vmgp::Vmgp()
: heapRaw(0), heapSize(0), flags(0), stackSize(0),
  codeSize(0), dataSize(0), bssSize(0), resSize(0), poolCount(0), stringSize(0),
  all(0), allLen(0),
  codeOff(0), dataOff(0), resOff(0), poolOff(0), strOff(0),
  pool(0), resStart(0), resLen(0), resCount(0),
  archiveZip(0), archiveZipLen(0) {}

Vmgp::~Vmgp(){
    delete[] all;
    delete[] pool;
    delete[] resStart;
    delete[] resLen;
    /* archiveZip aponta p/ dentro de `all` (fatia), NAO alocado separado: nao deletar. */
}

Vmgp* Vmgp::Create(const u8* d, int len, const char** err){
    const char* dummy; if (!err) err = &dummy;
    Vmgp* v = new Vmgp();
    v->allLen = len;
    v->all = new u8[len];
    wpMemCopy(v->all, d, len);

    /* DRM: nas ROMs originais da Sony Ericsson a secao de CODIGO vem cifrada (o
       cabecalho nao). Sem decifrar, o parse passa e a VM executa ruido: o PC sai da
       area de codigo na primeira instrucao e a tela fica preta para sempre -- foi
       exatamente o que acontecia no E63 e no EKA2L1.
       Decifra aqui, na copia em memoria; o arquivo do usuario nunca e alterado. */
    const bool looked    = MophunCrypt::looksEncrypted(v->all, len);
    const bool decrypted = looked && MophunCrypt::decryptInPlace(v->all, len);

    /* O parse vem primeiro mesmo quando a decifragem falhou: uma ROM "packed"
       (S60/PPC, comprimida) tambem parece ruido para a heuristica, e a mensagem
       dela e mais util que a de DRM. */
    if (!v->parse(err)){ delete v; return 0; }

    if (looked && !decrypted)
    {
        /* passou no parse (cabecalho e texto claro) mas o codigo continua cifrado:
           executar isso daria tela preta. Melhor dizer o que houve. */
        *err = "ROM protegida por DRM: nenhuma chave Sony Ericsson serve para este arquivo.";
        delete v; return 0;
    }
    v->wasEncrypted = decrypted;
    return v;
}

bool Vmgp::parse(const char** err){
    const u8* d = all;
    int len = allLen;
    const char* dummy; if (!err) err = &dummy;

    if (len < HEADER_SIZE || d[0]!='V'||d[1]!='M'||d[2]!='G'||d[3]!='P'){
        *err = "Nao e um arquivo VMGP valido (.mpn descriptografado)";
        return false;
    }
    heapRaw   = rdU32(d,4);
    heapSize  = ((u64)heapRaw) << 2;
    flags     = rdU16(d,8);
    stackSize = ((i64)rdU16(d,10)) << 2;
    codeSize  = (int)rdU32(d,12);
    dataSize  = (int)rdU32(d,16);
    bssSize   = (int)rdU32(d,20);
    resSize   = (int)rdU32(d,24);
    /* rdU32(d,28) = unk / packed-end */
    poolCount = (int)rdU32(d,32);
    stringSize= (int)rdU32(d,36);

    codeOff = HEADER_SIZE;
    dataOff = codeOff + codeSize;
    resOff  = dataOff + dataSize;
    poolOff = resOff + resSize;
    strOff  = poolOff + poolCount * 8;

    /* ROM "packed" (S60/PPC): code+data comprimidos (LZ). O campo @28 (0 em ROMs
       normais) guarda o fim de code+data comprimidos; os tamanhos do header sao os
       DESCOMPRIMIDOS, entao poolOff cai alem do arquivo. Nao descomprimimos aqui:
       erro claro (use a versao "_decompressed" do ROM). */
    u32 u28 = rdU32(d,28);
    if (u28 != 0 || (i64)strOff + stringSize > (i64)len){
        *err = "ROM comprimido (packed S60/PPC) nao suportado - use a versao descompactada (\"_decompressed\").";
        return false;
    }

    /* pool */
    pool = new PoolItem[poolCount > 0 ? poolCount : 1];
    for (int i=0;i<poolCount;i++){
        u32 seg = rdU32(d, poolOff + i*8);
        u32 tgt = rdU32(d, poolOff + i*8 + 4);
        pool[i].type       = (int)(seg & 0xF);
        pool[i].itemTarget = (int)((seg >> 4) & 0xF);
        pool[i].meta       = (int)(seg >> 8);
        pool[i].target     = (int)tgt;
    }

    /* tabela de recursos: offsets relativos a resOff, terminada por 0. */
    int maxRes = (poolOff - resOff) / 4;
    if (maxRes < 0) maxRes = 0;
    int* tmp = new int[maxRes > 0 ? maxRes : 1];
    int n = 0;
    int o = resOff;
    while (o + 4 <= poolOff){
        int val = (int)rdU32(d,o);
        if (val == 0) break;
        tmp[n++] = val;
        o += 4;
    }
    resCount = n;
    resStart = new int[n > 0 ? n : 1];
    resLen   = new int[n > 0 ? n : 1];
    for (int i=0;i<n;i++){
        int s = resOff + tmp[i];
        int e = (i+1 < n) ? resOff + tmp[i+1] : poolOff;
        resStart[i] = s;
        resLen[i]   = e - s;
    }
    delete[] tmp;

    scanEmbeddedZip();  /* STUB nesta etapa */
    return true;
}

/* STUB: o VFS de ZIP embutido sera portado numa etapa dedicada (precisa inflate).
   Sem ele, jogos 3D que guardam assets num ZIP recebem arquivos vazios; jogos 2D
   e 3D simples nao sao afetados. */
void Vmgp::scanEmbeddedZip(){
    archiveZip = 0;
    archiveZipLen = 0;
}

const u8* Vmgp::vfsGet(const char* /*name*/, int* outLen) const {
    if (outLen) *outLen = 0;
    return 0;
}

const char* Vmgp::getString(int off) const {
    /* strings sao 0-terminadas dentro de `all`; devolve ponteiro direto. */
    int p = strOff + off;
    if (p < 0 || p >= allLen) return "";
    return (const char*)(all + p);
}

bool Vmgp::importsSymbol(const char* name) const {
    for (int i=0;i<poolCount;i++){
        if (pool[i].type==2 && wpStrCmp(name, getString(pool[i].meta))==0)
            return true;
    }
    return false;
}
