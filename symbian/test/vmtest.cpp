/* vmtest.cpp - harness de desktop para o nucleo portavel (sem Symbian).
 * Carrega um .mpn, monta Vmgp + Pip2 + Screen + Sdk REAIS e executa o jogo,
 * mostrando o que foi efetivamente desenhado. Opcionalmente grava o framebuffer
 * num .ppm para inspecao visual.
 *
 * Uso: vmtest [caminho.mpn] [maxInstr] [saida.ppm]
 */
#include "Vmgp.h"
#include "Pip2.h"
#include "Sdk.h"
#include "Screen.h"
#include "Renderer3D.h"
#include "MophunModel.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

/* ---- host do Screen: tempo e "apresentacao" (aqui so conta frames) ---- */
class TestHost : public ScreenHost {
public:
    TestHost() : frames(0), start(clock()), scr(0) { vclock = 0; }
    void Present(){
        frames++;
        /* autoplay simples: pulsa FIRE a cada 25 frames p/ atravessar menus e entrar no
           jogo (o emulador de PC tem o mesmo recurso p/ testes headless). */
        if (scr){
            bool fire = (frames % 25) >= 10 && (frames % 25) < 13;
            scr->setButtonBit(Screen::FIRE, fire);
        }
    }
    Screen* scr;
    /* WPVTICK=<passo>: tempo VIRTUAL (avanca <passo> ms por leitura). Sem isto o relogio
       e real e o jogo evolui diferente a cada execucao -- impossivel comparar dois
       renderizadores quadro a quadro. */
    u32  TickMs(){
        static const char* vt = getenv("WPVTICK");
        if (vt){ vclock += atoi(vt); return (u32)vclock; }
        return (u32)((clock() - start) * 1000 / CLOCKS_PER_SEC);
    }
    long vclock;
    u32  EpochSeconds(){ return (u32)time(0); }
    void DateTime(int* y,int* mo,int* d,int* h,int* mi,int* s){
        time_t t = time(0);
        struct tm* lt = localtime(&t);
        if(y)*y=lt->tm_year+1900; if(mo)*mo=lt->tm_mon+1; if(d)*d=lt->tm_mday;
        if(h)*h=lt->tm_hour; if(mi)*mi=lt->tm_min; if(s)*s=lt->tm_sec;
    }
    int  MessageBox(const char* title, const char* msg){
        printf("  [MsgBox] %s%s%s\n", title?title:"", title?" | ":"", msg?msg:"");
        return 1;
    }
    long frames;
    clock_t start;
};

/* ---- sistema de arquivos de teste: saves em ./saves ---- */
class TestFs : public SdkFileSystem {
public:
    u8* ReadSave(const char* name, int* outLen){
        char path[256];
        sprintf(path, "saves/%s.sav", name);
        FILE* f = fopen(path, "rb");
        if (!f){ if(outLen)*outLen=0; return 0; }
        fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET);
        u8* d = new u8[n>0?n:1];
        if (n>0 && fread(d,1,n,f)!=(size_t)n){ fclose(f); delete[] d; if(outLen)*outLen=0; return 0; }
        fclose(f);
        if(outLen)*outLen=(int)n;
        return d;
    }
    void WriteSave(const char* name, const u8* data, int len){
        char path[256];
        sprintf(path, "saves/%s.sav", name);
        FILE* f = fopen(path, "wb");
        if (!f) return;
        if (len>0 && data) fwrite(data,1,len,f);
        fclose(f);
    }
};

/* ---- saida de som de teste: so registra o que vPlayResource entrega ---- */
class TestAudio : public SdkAudio {
public:
    TestAudio(): calls(0), midi(0){ magic[0]=0; }
    int PlayMusic(const u8* d, int n, bool){
        calls++;
        if (n>=4 && d[0]=='M'&&d[1]=='T'&&d[2]=='h'&&d[3]=='d') midi++;
        if (calls==1 && n>=4){
            for(int i=0;i<4;i++) magic[i] = (d[i]>=32&&d[i]<127) ? (char)d[i] : '.';
            magic[4]=0;
        }
        return 1;
    }
    long calls, midi;
    char magic[5];
};

static void writePpm(const char* path, Screen* s){
    FILE* f = fopen(path, "wb");
    if (!f){ printf("nao gravou %s\n", path); return; }
    fprintf(f, "P6\n%d %d\n255\n", s->w, s->h);
    for (int i=0;i<s->w*s->h;i++){
        u32 c = s->fb[i];
        fputc((int)((c>>16)&0xff), f);
        fputc((int)((c>>8)&0xff), f);
        fputc((int)(c&0xff), f);
    }
    fclose(f);
    printf("framebuffer gravado em %s (%dx%d)\n", path, s->w, s->h);
}

int main(int argc, char** argv){
    const char* path = (argc>1) ? argv[1] : "honeycave2.mpn";
    i64 maxInstr = (argc>2) ? atoi(argv[2]) : 20000000;
    const char* ppm = (argc>3) ? argv[3] : 0;
    /* nome do arquivo sem o diretorio: e dele que sai o modelo do aparelho */
    const char* base = path;
    for (const char* p2 = path; *p2; p2++) if (*p2=='/' || *p2=='\\') base = p2+1;
    int autoW = 176, autoH = 208;
    MophunModel::AutoRes(base, &autoW, &autoH);
    int scrW = (argc>4) ? atoi(argv[4]) : autoW;
    int scrH = (argc>5) ? atoi(argv[5]) : autoH;

    FILE* f = fopen(path, "rb");
    if (!f){ printf("ERRO: nao abriu %s\n", path); return 1; }
    fseek(f,0,SEEK_END); long n = ftell(f); fseek(f,0,SEEK_SET);
    u8* buf = new u8[n];
    if (fread(buf,1,n,f) != (size_t)n){ printf("ERRO: leitura\n"); fclose(f); return 1; }
    fclose(f);
    printf("arquivo: %s (%ld bytes)\n", path, n);

    const char* err = 0;
    Vmgp* exe = Vmgp::Create(buf, (int)n, &err);
    if (!exe){ printf("ERRO parse: %s\n", err); delete[] buf; return 1; }
    printf("VMGP ok: code=%d data=%d bss=%d res=%d pool=%d recursos=%d\n",
        exe->codeSize, exe->dataSize, exe->bssSize, exe->resSize, exe->poolCount,
        exe->resourceCount());
    bool is3d = exe->importsSymbol("vInit3D");
    printf("tipo: %s\n", is3d ? "3D (importa vInit3D)" : "2D");

    int heap  = (int)exe->heapBytesRequested(); if (heap  < 512*1024) heap  = 512*1024;
    int stack = (int)exe->stackSize;            if (stack < 64*1024)  stack = 64*1024;

    TestHost host;
    TestFs   fsys;
    TestAudio aud;
    /* 320x240 = alvo Nokia E-series (paisagem, QWERTY) */
    Screen* scr = new Screen(scrW, scrH, &host);
    host.scr = scr;
    Pip2*   vm  = new Pip2(exe, heap, stack);
    Sdk*    sdk = new Sdk(vm, scr);
    sdk->fs = &fsys;
    sdk->audio = &aud;
    /* modelo do aparelho pelo nome do arquivo: jogos nao-multiscreen abortam com
       "Terminal not supported!" se a resposta de vGetCaps nao for uma que conhecem. */
    {
        int m = MophunModel::FromName(base);
        if (argc>6) m = atoi(argv[6]);     /* forca o modelo, p/ diagnostico */
        if (m >= 0){
            sdk->deviceModel  = m;
            sdk->deviceVendor = MophunModel::VendorFor(m);
            printf("modelo detectado: %d (vendor %d)\n", m, sdk->deviceVendor);
        } else printf("modelo: multiscreen/desconhecido (padrao T610)\n");
    }
    /* o renderer 3D so e criado quando o jogo e 3D (importa vInit3D): num jogo 2D ele
       seria 300 KB de z-buffer parado. */
    Renderer3D* r3d = is3d ? new Renderer3D(vm, scr) : 0;
    sdk->r3d = r3d;
    vm->sdk = sdk;
    printf("mem da VM: %d bytes  tela: %dx%d\n", vm->memLen, scr->w, scr->h);

    vm->runConstructors();
    vm->run(maxInstr);

    /* quantos pixels do framebuffer deixaram de ser pretos = o jogo DESENHOU */
    int nonBlack = 0;
    for (int i=0;i<scr->w*scr->h;i++) if ((scr->fb[i] & 0xffffff) != 0) nonBlack++;

    printf("\n--- RESULTADO ---\n");
    printf("instrucoes executadas : %ld\n", (long)vm->instr);
    printf("parou?                : %s\n", vm->stopped ? "sim" : "nao (limite atingido)");
    printf("frames (vFlipScreen)  : %ld\n", host.frames);
    printf("pixels escritos (2D)  : %ld\n", (long)scr->pixels2d);
    printf("tela nao-preta        : %d de %d (%d%%)\n",
           nonBlack, scr->w*scr->h, nonBlack*100/(scr->w*scr->h));
    printf("APIs nao implementadas: %ld\n", (long)sdk->stubCalls);
    printf("som: sons carregados=%ld  play=%ld  beep=%ld\n",
           sdk->vsound.sndLoaded, sdk->vsound.playCount, sdk->vsound.beepCount);
    printf("vPlayResource: %ld chamadas, %ld MIDI (1o magic: \"%s\")\n",
           aud.calls, aud.midi, aud.magic);

    if (r3d) printf("3D: triangulos=%ld  pixels=%ld\n", (long)r3d->trisDrawn, (long)r3d->pixels3d);

    if (ppm) writePpm(ppm, scr);

    sdk->flushSaves();
    delete r3d; delete sdk; delete vm; delete scr; delete exe; delete[] buf;
    return 0;
}
