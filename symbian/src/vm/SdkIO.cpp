/* SdkIO.cpp - parte do Sdk: entrada, texto/fontes, strings/printf, caps,
 * streams (recursos do .mpn + arquivos) e descompressao LZ.
 * Port de src/wolphun/vm/Sdk.java. Ver Sdk.h.
 */
#include "Sdk.h"
#include "Pip2.h"
#include "Screen.h"

/* ================= entrada por tecla ASCII (nofun InputDriver) ================= */
/* O keypad do celular mapeia p/ os botoes logicos. vTestKey(ascii):
   2=Up 8=Down 4=Left 6=Right, 5 e asterisco=Fire, cerquilha=Fire2,
   0/217=Select; diagonais 1/3/7/9.
   CODIGOS DE TECLA DO APARELHO: o vmgp.h so diz que acima de 127 sao "implementation
   defined"; a tabela oficial esta em MophunSDK_2.5/pip/include/vmgpsonyericsson.h:
     200 VOLUME_UP 201 VOLUME_DOWN 202 YES 203 NO 204 CLEAR 205 LEFT 206 RIGHT
     207 FLIP_CLOSE 208 FLIP_OPEN 209 PHF 210 UP 211 DOWN 212 LEFT_UP 213 RIGHT_DOWN
     214 NAV_SELECT 215 VOICENOTE 216 SELECT 217 OPTION
   205/206/210/211 sao o JOYSTICK do aparelho (o proprio d-pad) -> derivamos dos bits.
   203 (NO/vermelho) e 204 (CLEAR) NAO tem bit equivalente: sao acoes proprias,
   mapeaveis no menu de controles, e chegam pelo estado de keypad. */
enum {
    SE_YES=202, SE_NO=203, SE_CLEAR=204, SE_LEFT=205, SE_RIGHT=206,
    SE_UP=210, SE_DOWN=211, SE_NAV_SELECT=214, SE_SELECT=216, SE_OPTION=217,
    ASCII_ESC=27
};

int Sdk::testKey(int key){
    if (scr->keypadPressed(key)) return 1;   /* tecla numerica literal (ex.: Bomb Jack) */
    int b = scr->getButtons();
    const int U=Screen::UP, D=Screen::DOWN, L=Screen::LEFT, R=Screen::RIGHT;
    const int F=Screen::FIRE, S=Screen::SELECT, F2=Screen::FIRE2;
    switch (key){
        case '1': return ((b&(U|L))==(U|L))?1:0;
        case '2': return (b&U)!=0?1:0;
        case '3': return ((b&(U|R))==(U|R))?1:0;
        case '4': return (b&L)!=0?1:0;
        case '5': case '*': return (b&F)!=0?1:0;
        case '6': return (b&R)!=0?1:0;
        case '7': return ((b&(D|L))==(D|L))?1:0;
        case '8': return (b&D)!=0?1:0;
        case '9': return ((b&(D|R))==(D|R))?1:0;
        case '0': case SE_OPTION: return (b&S)!=0?1:0;
        /* 214 = softkey esquerda do Sony Ericsson (a que o jogo rotula "Select" no
           rodape). Devolver sempre 0 travava quem confirma por ela: o Kelly Slater
           nunca saia do menu e o Something Fishy nunca aceitava o OK. */
        case SE_NAV_SELECT: return (b&F)!=0?1:0;
        case '#': return (b&F2)!=0?1:0;
        /* JOYSTICK do aparelho = d-pad. O SAAF le a direcao SO por estes codigos. */
        case SE_LEFT:  return (b&L)!=0?1:0;
        case SE_RIGHT: return (b&R)!=0?1:0;
        case SE_UP:    return (b&U)!=0?1:0;
        case SE_DOWN:  return (b&D)!=0?1:0;
        /* tecla verde (atender) e o "select" dedicado valem como confirmar */
        case SE_YES: case SE_SELECT: return (b&F)!=0?1:0;
        /* o ESC das versoes com teclado e a MESMA acao do 203 (voltar/sair) */
        case ASCII_ESC: return scr->keypadPressed(SE_NO)?1:0;
        default: return 0;
    }
}

/* vScanKeys(): ASCII da tecla atual (reverso do testKey; nofun InputDriver.KeyScan) */
int Sdk::scanKeys(){
    for (int a='0'; a<='9'; a++) if (scr->keypadPressed(a)) return a;
    if (scr->keypadPressed('*')) return '*';
    if (scr->keypadPressed('#')) return '#';
    /* teclas de APARELHO antes do d-pad: quem espera "voltar" (Bomb Jack, SAAF) ou
       "apagar" (o pause do Jake) le isso pelo vScanKeys, nao por bit de botao. */
    if (scr->keypadPressed(SE_NO))    return SE_NO;
    if (scr->keypadPressed(SE_CLEAR)) return SE_CLEAR;
    int b = scr->getButtons();
    const int U=Screen::UP, D=Screen::DOWN, L=Screen::LEFT, R=Screen::RIGHT;
    const int F=Screen::FIRE, S=Screen::SELECT, F2=Screen::FIRE2;
    if (b&U){ if (b&L) return '1'; if (b&R) return '3'; return '2'; }
    if (b&D){ if (b&L) return '7'; if (b&R) return '9'; return '8'; }
    if (b&L) return '4';
    if (b&R) return '6';
    if (b&F) return '5';
    if (b&F2) return '*';
    if (b&S) return SE_OPTION;
    return 0;
}

/* ================= strings ================= */
/* le uma string da memoria da VM: latin1 (byte) ou UTF-16LE (unicode) */
const char* Sdk::readStr(int ptr, bool unicode, char* buf, int bufLen){
    int n = 0;
    if (ptr != 0){
        if (unicode){
            int c;
            while ((c = vm->read16(ptr)) != 0 && n < bufLen-1){
                buf[n++] = (char)(c & 0xff);   /* latin1 do plano basico */
                ptr += 2;
            }
        } else {
            int c;
            while ((c = vm->read8(ptr)) != 0 && n < bufLen-1){
                buf[n++] = (char)c;
                ptr += 1;
            }
        }
    }
    buf[n] = 0;
    return buf;
}

/* vitoa/vutoa(val, buf, len, pad): escreve o numero, completa ate 'len' com 'pad' e
   devolve o ponteiro para o '\0' final (o FIM), nao o inicio -- os jogos encadeiam. */
int Sdk::itoa(int val,int buf,bool sign,int len,int pad){
    char tmp[16];
    int n = 0;
    if (sign){
        i64 v = val;
        bool neg = v < 0; if (neg) v = -v;
        if (v == 0) tmp[n++]='0';
        while (v > 0){ tmp[n++] = (char)('0' + (int)(v % 10)); v /= 10; }
        if (neg) tmp[n++]='-';
    } else {
        u32 v = (u32)val;
        if (v == 0) tmp[n++]='0';
        while (v > 0){ tmp[n++] = (char)('0' + (int)(v % 10)); v /= 10; }
    }
    for (int i=0;i<n;i++) vm->write8(buf+i, tmp[n-1-i]);
    for (int k=n; k<len; k++) vm->write8(buf+k, pad);
    int nullPos = (n > len) ? n : len;
    vm->write8(buf+nullPos, 0);
    return buf + nullPos;
}

/* vatoi(strPtr, endPtr): ASCII -> int; endPtr (opcional) recebe o ptr apos o numero */
int Sdk::vatoi(int strPtr,int endPtr){
    int p = strPtr, ch;
    while ((ch = vm->read8(p)&0xff)==' '||ch=='\t'||ch=='\n'||ch=='\r') p++;
    int sign = 1;
    ch = vm->read8(p)&0xff;
    if (ch=='+'||ch=='-'){ if (ch=='-') sign=-1; p++; }
    i64 v = 0;
    while ((ch = vm->read8(p)&0xff) >= '0' && ch <= '9'){ v = v*10 + (ch-'0'); p++; }
    if (endPtr != 0) vm->write32(endPtr, p);
    return (int)(sign * v);
}

/* ================= printf (vSprintf / vSprintfVa) ================= */
/* ABI do PIP2 (MophunAsmRef 1.3.1): os quatro primeiros argumentos vao em $p0-$p3;
   os demais E TAMBEM os argumentos de funcoes stdarg (ellipsis) vao na PILHA. Logo,
   em vSprintf(buf,fmt,...) os args do ... nao estao em $p2/$p3: estao na pilha a
   partir de $sp+0. Lendo de $p2/$p3 o Golf Pro Contest imprimia "PAR 625608". */
int Sdk::nextArg(ArgSrc* a){
    int v = a->vm->read32(a->base + a->off);
    a->off += 4;
    return v;
}

static void appendStr(char* out,int outCap,int* pos,const char* s){
    while (*s && *pos < outCap-1) out[(*pos)++] = *s++;
}

/* formatador printf minimo: %d/%i/%u/%x/%X/%o/%c/%s/%p/%%, com flags(-,0,+,espaco),
   largura e precisao. */
int Sdk::formatC(const char* fmt, ArgSrc* args, char* out, int outCap){
    int pos = 0;
    const char* f = fmt;
    char piece[600];
    char sbuf[520];
    while (*f && pos < outCap-1){
        char c = *f++;
        if (c != '%'){ out[pos++] = c; continue; }
        if (*f == '%'){ out[pos++]='%'; f++; continue; }
        bool left=false, zeroPad=false, plus=false, space=false;
        for (;;){
            if (*f=='-'){ left=true; f++; }
            else if (*f=='0'){ zeroPad=true; f++; }
            else if (*f=='+'){ plus=true; f++; }
            else if (*f==' '){ space=true; f++; }
            else break;
        }
        int width=0;
        while (*f>='0' && *f<='9') width = width*10 + (*f++ - '0');
        int prec=-1;
        if (*f=='.'){ f++; prec=0; while (*f>='0'&&*f<='9') prec = prec*10 + (*f++ - '0'); }
        while (*f=='l'||*f=='h'||*f=='L') f++;
        if (!*f) break;
        char cv = *f++;
        int pn = 0;
        switch (cv){
            case 'd': case 'i': {
                int v = nextArg(args);
                char t[16]; int n=0;
                i64 q = v; bool neg = q<0; if (neg) q = -q;
                if (q==0) t[n++]='0';
                while (q>0){ t[n++] = (char)('0'+(int)(q%10)); q/=10; }
                if (neg) piece[pn++]='-';
                else if (plus) piece[pn++]='+';
                else if (space) piece[pn++]=' ';
                while (n>0) piece[pn++] = t[--n];
                break;
            }
            case 'u': {
                u32 v = (u32)nextArg(args);
                char t[16]; int n=0;
                if (v==0) t[n++]='0';
                while (v>0){ t[n++] = (char)('0'+(int)(v%10)); v/=10; }
                while (n>0) piece[pn++] = t[--n];
                break;
            }
            case 'x': case 'X': {
                u32 v = (u32)nextArg(args);
                const char* dig = (cv=='x') ? "0123456789abcdef" : "0123456789ABCDEF";
                char t[16]; int n=0;
                if (v==0) t[n++]='0';
                while (v>0){ t[n++] = dig[v & 0xf]; v >>= 4; }
                while (n>0) piece[pn++] = t[--n];
                break;
            }
            case 'o': {
                u32 v = (u32)nextArg(args);
                char t[16]; int n=0;
                if (v==0) t[n++]='0';
                while (v>0){ t[n++] = (char)('0'+(int)(v & 7)); v >>= 3; }
                while (n>0) piece[pn++] = t[--n];
                break;
            }
            case 'c': piece[pn++] = (char)(nextArg(args) & 0xff); break;
            case 'p': {
                u32 v = (u32)nextArg(args);
                piece[pn++]='0'; piece[pn++]='x';
                char t[16]; int n=0;
                if (v==0) t[n++]='0';
                while (v>0){ t[n++] = "0123456789abcdef"[v & 0xf]; v >>= 4; }
                while (n>0) piece[pn++] = t[--n];
                break;
            }
            case 's': {
                int p = nextArg(args);
                readStr(p, false, sbuf, (int)sizeof(sbuf));
                int sl = wpStrLen(sbuf);
                if (prec>=0 && sl>prec) sl = prec;
                for (int k=0;k<sl && pn<(int)sizeof(piece)-1;k++) piece[pn++] = sbuf[k];
                break;
            }
            default: piece[pn++]='%'; piece[pn++]=cv; break;
        }
        piece[pn] = 0;
        if (width > pn){
            int pad = width - pn;
            if (left){
                appendStr(out,outCap,&pos,piece);
                for (int k=0;k<pad && pos<outCap-1;k++) out[pos++]=' ';
            } else if (zeroPad && cv!='s' && cv!='c' && pn>0
                       && (piece[0]=='-'||piece[0]=='+'||piece[0]==' ')){
                if (pos<outCap-1) out[pos++]=piece[0];
                for (int k=0;k<pad && pos<outCap-1;k++) out[pos++]='0';
                appendStr(out,outCap,&pos,piece+1);
            } else {
                char pc = (zeroPad && cv!='s' && cv!='c') ? '0' : ' ';
                for (int k=0;k<pad && pos<outCap-1;k++) out[pos++]=pc;
                appendStr(out,outCap,&pos,piece);
            }
        } else {
            appendStr(out,outCap,&pos,piece);
        }
    }
    out[pos] = 0;
    return pos;
}

int Sdk::sprintfReg(int buf,int fmt){
    char fbuf[512];
    char obuf[2048];
    readStr(fmt, false, fbuf, (int)sizeof(fbuf));
    ArgSrc a; a.vm = vm; a.base = vm->getReg(Pip2::SP); a.off = 0; a.stack = true;
    int n = formatC(fbuf, &a, obuf, (int)sizeof(obuf));
    for (int i=0;i<n;i++) vm->write8(buf+i, (u8)obuf[i]);
    vm->write8(buf+n, 0);
    return buf;    /* CRITICO: retorna buf (nao 0) -- o jogo usa como char* */
}

/* vSprintfVa(buf, fmt, ap): ap = ponteiro p/ o va_list (array de u32 na memoria da VM) */
int Sdk::sprintfVa(int buf,int fmt,int ap){
    char fbuf[512];
    char obuf[2048];
    readStr(fmt, false, fbuf, (int)sizeof(fbuf));
    ArgSrc a; a.vm = vm; a.base = ap; a.off = 0; a.stack = false;
    int n = formatC(fbuf, &a, obuf, (int)sizeof(obuf));
    for (int i=0;i<n;i++) vm->write8(buf+i, (u8)obuf[i]);
    vm->write8(buf+n, 0);
    return buf;
}

/* ================= fonte bitmap do jogo (VMGPFONT) ================= */
/* Rele a VMGPFONT apontada por aFontPtr. O Word Panic monta UMA struct e so troca o
   campo palindex antes de cada vPrint (mesmos glifos, paleta diferente p/ o rotulo e
   p/ os digitos do placar); guardar uma copia congelava a paleta da chamada anterior. */
void Sdk::fontRefresh(){
    if (aFontPtr == 0) return;
    aFontData    = vm->read32(aFontPtr+0);
    aFontChartbl = vm->read32(aFontPtr+4);
    aFontBpp     = vm->read8(aFontPtr+8);
    aFontW       = vm->read8(aFontPtr+9);
    aFontH       = vm->read8(aFontPtr+10);
    aFontPal     = vm->read8(aFontPtr+11) & 0xff;
}

/* vSetActiveFont(pFont): guarda o PONTEIRO (nao uma copia): o jogo pode reescrever os
   campos entre um vPrint e outro. Devolve o ponteiro da fonte anterior. */
int Sdk::setActiveFont(int ptr){
    int prev = aFontPtr;
    aFontPtr = ptr;
    if (ptr == 0){ aFontData=aFontChartbl=aFontBpp=aFontW=aFontH=aFontPal=0; return prev; }
    fontRefresh();
    return prev;
}

/* vPrint(mode,x,y,str): texto com a fonte bitmap ATIVA. Cada caractere ocupa
   width x height pixels; glyph = chartbl[ch] (0xff = ausente). Bits LSB-first, linhas
   contiguas, cada caractere alinhado a byte. Largura FIXA. */
void Sdk::vPrintImpl(int mode,int x,int y,const char* str){
    fontRefresh();                      /* a struct pode ter mudado desde o vSetActiveFont */
    if (aFontPtr==0 || aFontW<=0 || aFontH<=0){
        scr->drawText(x, y, str, getColor(foreColor));   /* sem fonte do jogo: a do sistema */
        return;
    }
    if (!str || !*str) return;
    int bpp = (aFontBpp<=0) ? 1 : aFontBpp;
    int mask = (1<<bpp)-1;
    int glyphBytes = (aFontW*aFontH*bpp + 7) >> 3;       /* caractere alinhado a byte */
    u32 fg = getColor(foreColor);
    /* `mode` e um TransferMode (vmgp.h XferModes): MODE_BLOCK(0) = sem transparencia,
       MODE_TRANS(1) = indice 0 transparente. Ignorar isso quebra quem usa vPrint como
       "printf opaco": o Graveyard Adventure reimprime TIME/AIR por cima do valor antigo
       a cada frame contando com o modo bloco p/ apagar -- em transparente os digitos se
       somavam ate virar um bloco branco solido. */
    bool blockMode = (mode & 1)==0;
    /* Em bloco o indice 0 vira cor solida. Numa fonte MONOCROMATICA (bpp=1) nao ha
       paleta e a unica cor possivel e o backColor. Numa fonte INDEXADA (bpp>=2) o
       indice 0 e uma entrada de paleta como outra qualquer: o Word Panic conta com isso
       p/ repintar o azul do interior da caixa do placar (backColor preto enchia de preto). */
    u32 bg = (aFontBpp>1) ? scr->palette[aFontPal & 0xff] : getColor(backColor);
    int penX = x;
    for (const unsigned char* p=(const unsigned char*)str; *p; ++p){
        int ch = *p & 0xff;
        int gi = vm->read8(aFontChartbl + ch);
        if (gi != 0xff){                                 /* 0xff = ausente (so avanca) */
            int base = aFontData + gi*glyphBytes;
            for (int row=0; row<aFontH; row++){
                int dy = y+row; if (dy<scr->clipY0||dy>=scr->clipY1) continue;
                for (int col=0; col<aFontW; col++){
                    int dx = penX+col; if (dx<scr->clipX0||dx>=scr->clipX1) continue;
                    int bitPos = (row*aFontW+col)*bpp;
                    int val = (vm->read8(base+(bitPos>>3)) >> (bitPos&7)) & mask;
                    u32 argb;
                    if (val==0){ if (!blockMode) continue; argb = bg; }
                    else argb = (bpp==1) ? fg : scr->palette[(aFontPal+val) & 0xff];
                    scr->put(dx,dy,argb);
                }
            }
        }
        penX += aFontW;                                  /* largura FIXA */
    }
}

/* extent empacotado: bits altos = altura, bits baixos = largura (formato mophun) */
int Sdk::textExtent(const char* s){
    int w = scr->textWidth(s), h = scr->fontHeight();
    return (h << 16) | (w & 0xffff);
}

/* ================= vGetCaps ================= */
/* VMDateTime = 8 bytes: year(u16)@0, day(u16)@2, month(b)@4, hour(b)@5, minute(b)@6,
   second(b)@7. CRITICO: escrever EXATAMENTE 8 bytes (o jogo passa buffer na PILHA
   junto ao RA; zerar demais corrompe o endereco de retorno). */
void Sdk::writeDateTime(int ptr){
    int y=2008,mo=1,d=1,hh=0,mi=0,ss=0;
    if (scr && scr->host) scr->host->DateTime(&y,&mo,&d,&hh,&mi,&ss);
    vm->write16(ptr+0, y);
    vm->write16(ptr+2, d);
    vm->write8 (ptr+4, mo);
    vm->write8 (ptr+5, hh);
    vm->write8 (ptr+6, mi);
    vm->write8 (ptr+7, ss);
}

int Sdk::getCaps(int queryType,int buf){
    if (buf==0) return 0;
    /* CapsQueryType (nofun): Video=0, Input=1, Sound=2, Communication=3, System=4 */
    switch (queryType){
        case 0:  /* VIDEOCAPS {u16 size, u16 flags, u16 width, u16 height} (vmgpcaps.h) */
            /* flags: os 8 BITS BAIXOS = FORMATO grafico (enum VideoFormats de vmgpvfmt.h);
               bits altos = VCAPS_3D(0x1000) e VCAPS_ORIENTATION(0x2000). Formatos: 6=IND256,
               7=RGB332, 8=RGB565... "requires 256 colors" = formato >= 6. Reportamos 7
               (RGB332), que e EXATAMENTE o framebuffer deste emulador.
               VCAPS_3D so quando o JOGO e 3D (importa vInit3D): jogos 3D estritos
               (Anarchy Boxing 3D, Martial Arts 3D) abortam com "3D not supported!" se o bit
               faltar, mas jogos 2D simplistas (Black Deal) comparam o flags INTEIRO com o
               range de formato e rejeitam qualquer bit alto (0x3007=12295 > 8). */
            vm->write16(buf+0, 8);
            vm->write16(buf+2, videoFormat | (version150 ? 0x1000 : 0));
            vm->write16(buf+4, scr->w);
            vm->write16(buf+6, scr->h);
            return 1;
        case 4:  /* System: {u16 size,flags, u32 deviceId, u32 vendorFlags} */
            vm->write16(buf+0, 12);
            vm->write16(buf+2, 0x25);                     /* Unicode|Vibrate|OpenURL */
            vm->write32(buf+4, (deviceModel<<16)|deviceVendor);
            vm->write32(buf+8, 0);
            return 1;
        case 2:  /* Sound: {u16 size,flags, u32 config}. vendor=3 -> HC2 liga o som */
            vm->write16(buf+0, 8);
            vm->write16(buf+2, 0xFFFF);
            vm->write32(buf+4, 3);
            return 1;
        case 3:  /* Communication: struct CAPSHDR {u16 size; u16 flags;} = SO 4 BYTES.
                    O Martial Arts 3D reserva exatamente 4 bytes na PILHA p/ este buffer,
                    entao escrever 8 caia em cima do ENDERECO DE RETORNO salvo, zerando-o:
                    o RET saltava p/ 0 e a VM encerrava em 910 instrucoes. */
            vm->write16(buf+0, 4);
            vm->write16(buf+2, 0);
            return 1;
        default: /* Input/etc */
            vm->write16(buf+0, 8);
            vm->write16(buf+2, 0);
            vm->write32(buf+4, 0);
            return 1;
    }
}

/* ================= streams ================= */
/* Flags de stream (StreamFlags.cs do nofun) */
enum { SF_READ=0x100, SF_WRITE=0x200, SF_BINARY=0x400,
       SF_CREATE=0x800, SF_TRUNC=0x1000, SF_EXCL=0x2000 };

int Sdk::streamAlloc(){
    for (int i=1;i<MAX_STREAMS;i++) if (!streams[i].used) return i;
    return -1;
}
Sdk::Stream* Sdk::streamGet(int handle){
    if (handle<1 || handle>=MAX_STREAMS) return 0;
    return streams[handle].used ? &streams[handle] : 0;
}

/* vStreamOpen(fileName, mode). tipo = mode&0xFF (7=Resource, 0=File); resNum = mode>>16. */
int Sdk::streamOpen(int namePtr,int mode){
    int type = mode & 0xFF;
    if (type == 7){                           /* Resource (dados do .mpn, so leitura) */
        int resNum = (int)(((u32)mode) >> 16);
        if (resNum >= vm->exe->resourceCount()) return -1;
        int fd = streamAlloc(); if (fd < 0) return -1;
        Stream& s = streams[fd];
        s.used=true; s.ownsData=false; s.writable=false;
        /* aponta direto p/ dentro do .mpn: sem copia (economiza RAM no aparelho) */
        s.data = vm->exe->all + vm->exe->resourceOffset(resNum);
        s.len  = vm->exe->resourceLength(resNum);
        s.cap  = s.len; s.pos = 0; s.name[0]=0;
        return fd;
    }
    /* type 0 = File */
    char nm[64];
    readStr(namePtr, false, nm, (int)sizeof(nm));
    return fileOpen(nm, mode);
}

/* Abre um save/arquivo de dados. Gravaveis sao persistidos JA na abertura e a cada
   write (nao dependem do vStreamClose -> save robusto mesmo se o app fechar). */
int Sdk::fileOpen(const char* name,int mode){
    bool write  = (mode & SF_WRITE)!=0;
    bool create = (mode & SF_CREATE)!=0;
    bool trunc  = (mode & SF_TRUNC)!=0;
    u8*  data = 0;
    int  len  = 0;
    bool owns = true;

    if (create || trunc){                     /* comeca vazio (regrava do zero) */
        data = 0; len = 0;
    } else if (fs && (data = fs->ReadSave(name, &len)) != 0){
        /* save existente carregado */
    } else if (!write && fs && (data = fs->ReadCompanion(name, &len)) != 0){
        /* ARQUIVO DE DADOS COMPANHEIRO ao lado da ROM (somente leitura), ex.: "dvc.mpc" */
    } else if (write){                        /* gravavel sem existir -> cria vazio */
        data = 0; len = 0;
    } else if ((mode & SF_BINARY)!=0 && (mode & SF_EXCL)==0){
        /* leitura BINARIA de arquivo inexistente SEM STREAM_EXCL -> arquivo de DADOS
           vazio (handle valido, 0 bytes). O Deep Abyss le 'level' assim e, sem isto,
           recebia -1 -> "Failed to open mappack" -> terminava. Com STREAM_EXCL (Da Vinci
           Code) o jogo espera o arquivo EXISTIR: dar handle vazio o fazia POLLAR p/
           sempre; cai no -1 abaixo e ele faz fallback p/ os recursos embutidos. */
        data = 0; len = 0;
    } else {
        return -1;                            /* leitura de save inexistente -> falha */
    }

    int fd = streamAlloc();
    if (fd < 0){ if (data) delete[] data; return -1; }
    Stream& s = streams[fd];
    s.used=true; s.ownsData=owns; s.writable=write;
    s.data=data; s.len=len; s.cap=len; s.pos=0;
    int i=0; for (; name[i] && i<(int)sizeof(s.name)-1; i++) s.name[i]=name[i];
    s.name[i]=0;
    if (write && fs) fs->WriteSave(s.name, s.data, s.len);   /* persiste JA */
    return fd;
}

int Sdk::streamRead(int handle,int buf,int count){
    Stream* s = streamGet(handle); if (!s) return -1;
    int n = s->len - s->pos;
    if (n > count) n = count;
    if (n < 0) n = 0;
    for (int i=0;i<n;i++) vm->write8(buf+i, s->data[s->pos+i]);
    s->pos += n;
    return n;
}

/* grava na memoria do stream; cresce o buffer se necessario (save game) */
int Sdk::streamWrite(int handle,int buf,int count){
    Stream* s = streamGet(handle); if (!s || count<0) return -1;
    if (s->pos + count > s->cap){
        int ncap = s->pos + count;
        if (ncap < s->cap*2) ncap = s->cap*2;
        u8* nd = new u8[ncap];
        for (int i=0;i<s->len;i++) nd[i]=s->data[i];
        for (int i=s->len;i<ncap;i++) nd[i]=0;
        if (s->ownsData && s->data) delete[] s->data;
        s->data = nd; s->cap = ncap; s->ownsData = true;
    }
    for (int i=0;i<count;i++) s->data[s->pos+i] = (u8)vm->read8(buf+i);
    s->pos += count;
    if (s->pos > s->len) s->len = s->pos;
    if (s->writable && fs) fs->WriteSave(s->name, s->data, s->len);   /* persiste JA */
    return count;
}

int Sdk::streamSeek(int handle,int where,int whence){   /* 0=Set 1=Cur 2=End */
    Stream* s = streamGet(handle); if (!s) return -1;
    int pos = s->pos;
    switch (whence){
        case 0: pos = where; break;
        case 1: pos += where; break;
        case 2: pos = s->len + where; break;
    }
    if (pos < 0) pos = 0;
    if (pos > s->len) pos = s->len;
    s->pos = pos;
    return pos;
}

void Sdk::streamClose(int handle){
    Stream* s = streamGet(handle); if (!s) return;
    if (s->writable && fs) fs->WriteSave(s->name, s->data, s->len);
    if (s->ownsData && s->data) delete[] s->data;
    s->used=false; s->data=0; s->len=s->cap=s->pos=0;
    s->ownsData=false; s->writable=false; s->name[0]=0;
}

/* flush de TODOS os saves gravaveis ainda abertos -> chamado ao sair (garante
   persistencia mesmo se o jogo nao chamou vStreamClose). */
void Sdk::flushSaves(){
    if (!fs) return;
    for (int i=1;i<MAX_STREAMS;i++)
        if (streams[i].used && streams[i].writable)
            fs->WriteSave(streams[i].name, streams[i].data, streams[i].len);
}

/* vPlayResource(data, length, flags): tipo=flags&0xF, loop=0x100, stream=0x200, stop=0x400 */
int Sdk::playResource(int data,int length,int flags){
    /* vPlayResource entrega um recurso de MUSICA ja pronto (SMF/MIDI na pratica).
       Nao e PCM: nao passa pelo mixer do VSound, vai inteiro para o tocador do sistema. */
    if ((flags & 0x400)!=0){ if (audio) audio->StopMusic(); return 1; }   /* Stop */
    if (!soundEnabled || !audio) return 1;

    const u8* bytes = 0;
    int n = 0;
    u8* tmp = 0;
    if ((flags & 0x200)!=0){                   /* Stream: data = handle */
        Stream* s = streamGet(data); if (!s) return 0;
        n = s->len - s->pos; if (n > length) n = length; if (n < 0) n = 0;
        bytes = s->data + s->pos;
        s->pos += n;
    } else {                                   /* memoria da VM */
        if (length<=0 || length > (1<<20)) return 0;
        tmp = new u8[length];
        if (!tmp) return 0;
        for (int i=0;i<length;i++) tmp[i] = (u8)vm->read8(data+i);
        bytes = tmp; n = length;
    }
    const int r = audio->PlayMusic(bytes, n, (flags & 0x100)!=0);
    if (tmp) delete[] tmp;
    return r;
}

/* ================= descompressao LZ (mophun) ================= */
/* leitor de bits MSB-first sobre a memoria da VM */
namespace {
struct VmBits {
    Pip2* vm; int base, len, p;
    VmBits(Pip2* v,int b,int l): vm(v), base(b), len(l), p(0) {}
    bool valid() const { return p < len*8; }
    int read(int count){
        int result=0, bitLeft=count, pp=p;
        if (pp%8 != 0){
            int toE = 8-(pp%8); if (toE > bitLeft) toE = bitLeft;
            result = (vm->read8(base+(pp>>3)) >> (8-((pp%8)+toE))) & (0xFF >> (8-toE));
            bitLeft -= toE; pp += toE;
        }
        if (bitLeft==0){ p=pp; return result; }
        for (int i=0;i<(bitLeft>>3);i++){ result=(result<<8)|vm->read8(base+(pp>>3)); bitLeft-=8; pp+=8; }
        if (bitLeft==0){ p=pp; return result; }
        result = (result<<bitLeft) | (vm->read8(base+(pp>>3)) >> (8-bitLeft));
        pp += bitLeft; p = pp;
        return result;
    }
};
/* mesma semantica, mas a fonte e um buffer de bytes (stream) */
struct ByteBits {
    const u8* a; int alen, base, len, p;
    ByteBits(const u8* d,int dlen,int b,int l): a(d), alen(dlen), base(b), len(l), p(0) {}
    int g(int i) const { int idx=base+i; return (idx>=0 && idx<alen) ? a[idx] : 0; }
    bool valid() const { return p < len*8; }
    int read(int count){
        int result=0, bitLeft=count, pp=p;
        if (pp%8 != 0){
            int toE = 8-(pp%8); if (toE > bitLeft) toE = bitLeft;
            result = (g(pp>>3) >> (8-((pp%8)+toE))) & (0xFF >> (8-toE));
            bitLeft -= toE; pp += toE;
        }
        if (bitLeft==0){ p=pp; return result; }
        for (int i=0;i<(bitLeft>>3);i++){ result=(result<<8)|g(pp>>3); bitLeft-=8; pp+=8; }
        if (bitLeft==0){ p=pp; return result; }
        result = (result<<bitLeft) | (g(pp>>3) >> (8-bitLeft));
        pp += bitLeft; p = pp;
        return result;
    }
};
} /* namespace */

int Sdk::decompress(int source,int dest){
    if (source==0) return -1;
    if (vm->read8(source)!='L' || vm->read8(source+1)!='Z') return -1;
    int maxB = vm->read8(source+2), extB = vm->read8(source+3);
    int usize = vm->read32(source+4);
    int csize = vm->read32(source+8);
    VmBits bs(vm, source+22, csize);
    int dp = 0;
    while (dp < usize && bs.valid()){
        if (bs.read(1)==1){
            int v2=0; while (v2 < maxB && bs.read(1)==1) v2++;
            int cl=2; if (v2!=0) cl = (bs.read(v2) | (1<<v2)) + 1;
            int bo = (cl==2) ? (bs.read(8)+2) : (bs.read(extB)+cl);
            if (cl > usize-dp) cl = usize-dp;
            for (int i=0;i<cl;i++) vm->write8(dest+dp+i, vm->read8(dest+dp-bo+i));
            dp += cl;
        } else {
            vm->write8(dest+dp, bs.read(8)); dp++;
        }
    }
    return dp;
}

/* Descomprime lendo o fluxo de um stream (source==0 no vDecompress). */
int Sdk::decompressStream(int handle,int dest){
    Stream* s = streamGet(handle); if (!s) return -1;
    int off = s->pos;
    if (s->len - off < 22 || s->data[off]!='L' || s->data[off+1]!='Z') return -1;
    int maxB = s->data[off+2], extB = s->data[off+3];
    int usize = (int)rdU32(s->data, off+4);
    int csize = (int)rdU32(s->data, off+8);
    ByteBits bs(s->data, s->len, off+22, csize);
    int dp = 0;
    while (dp < usize && bs.valid()){
        if (bs.read(1)==1){
            int v2=0; while (v2 < maxB && bs.read(1)==1) v2++;
            int cl=2; if (v2!=0) cl = (bs.read(v2) | (1<<v2)) + 1;
            int bo = (cl==2) ? (bs.read(8)+2) : (bs.read(extB)+cl);
            if (cl > usize-dp) cl = usize-dp;
            for (int i=0;i<cl;i++) vm->write8(dest+dp+i, vm->read8(dest+dp-bo+i));
            dp += cl;
        } else {
            vm->write8(dest+dp, bs.read(8)); dp++;
        }
    }
    s->pos = off + 22 + csize;
    return dp;
}

/* vDecompHdr: le header LZ, preenche NativeCompressedFileInfo (20B), retorna usize */
int Sdk::decompHdr(int infoPtr,int source){
    if (source==0 || vm->read8(source)!='L' || vm->read8(source+1)!='Z') return -1;
    int usize = vm->read32(source+4), csize = vm->read32(source+8);
    if (infoPtr != 0){
        vm->write8 (infoPtr+0, 0);          /* cnt */
        vm->write8 (infoPtr+1, 0);          /* offset */
        vm->write16(infoPtr+2, 0x1234);     /* crc16 */
        vm->write16(infoPtr+4, 0);          /* option */
        vm->write32(infoPtr+8, csize);      /* srcSize */
        vm->write32(infoPtr+12, usize);     /* destSize */
        vm->write32(infoPtr+16, 0);         /* literalSize */
    }
    return usize;
}
