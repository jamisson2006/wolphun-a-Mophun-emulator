/* WolphunAudio.cpp - ver WolphunAudio.h */
#include "WolphunAudio.h"
/* RDebug mora em e32debug.h no EKA2 (9.x) e em e32svr.h no EKA1 (8.1, a linha
   2nd Edition dos N70/N90). Era o UNICO header nosso que nao existe no SDK 8.1 --
   os outros 30 da casca Avkon existem todos la, inclusive skins e AknCommonDialogs. */
#ifdef __SYMBIAN_9__
#include <e32debug.h>
#else
#include <e32svr.h>
#endif

/* Rastro do caminho de som. No EKA2L1 sai no EKA2L1.log pelo canal Emulated.Stdout
   (vem DESLIGADO no config.yml); num aparelho vai para o log de depuracao. */
#define WPLOG(x)        RDebug::Print(_L("[wolphun-audio] " L##x))
#define WPLOG1(x, a)    RDebug::Print(_L("[wolphun-audio] " L##x), (a))
#define WPLOG2(x, a, b) RDebug::Print(_L("[wolphun-audio] " L##x), (a), (b))

_LIT(KMidiName, "mid%d.mid");

/* ===================== CMidiVoice ===================== */

CMidiVoice* CMidiVoice::NewL(const TDesC& aFile)
{
    CMidiVoice* self = new (ELeave) CMidiVoice();
    CleanupStack::PushL(self);
    self->ConstructL(aFile);
    CleanupStack::Pop(self);
    return self;
}

CMidiVoice::CMidiVoice()
: iPlayer(0), iReady(EFalse), iPlaying(EFalse), iWant(EFalse), iLoop(EFalse),
  iStopWanted(EFalse), iSince(0), iErr(KErrNone)
{
}

void CMidiVoice::ConstructL(const TDesC& aFile)
{
    /* Abre UMA vez e fica aberto pelo resto da partida. O controlador MMF e criado aqui,
       na carga da ROM -- nunca no meio do jogo. */
    iPlayer = CMdaAudioPlayerUtility::NewFilePlayerL(aFile, *this);
}

CMidiVoice::~CMidiVoice()
{
    /* O destrutor ja encerra tudo; Stop() e sincrono e seguro mesmo sem nada tocando. */
    if (iPlayer) iPlayer->Stop();
    delete iPlayer;
}

TBool CMidiVoice::Pump()
{
    if (!iPlayer || !iReady) return EFalse;
    if (iSince < 1000) iSince++;

    if (iStopWanted)
    {
        iStopWanted = EFalse;
        if (iPlaying){ iPlayer->Stop(); iPlaying = EFalse; }   /* Stop e SINCRONO */
        return EFalse;
    }

    if (!iWant) return EFalse;

    /* Anti-repique: varios jogos chamam vPlayResource a cada quadro enquanto a tela de
       "game over" esta no ar. Sem isto, reiniciariamos o som a cada 20 ms -- era o
       "toca eternamente em loop". 4 tiques = 80 ms, bem abaixo do intervalo entre dois
       tiros de verdade. */
    if (iPlaying && iSince < 4) return EFalse;

    iWant = EFalse;
    if (iPlaying){ iPlayer->Stop(); iPlaying = EFalse; }   /* redispara: corta e recomeca */

    iPlayer->SetVolume(iPlayer->MaxVolume());
    /* A flag 0x100 do vPlayResource e laco. Antes eu ignorava isso e punha 0 sempre --
       a musica de fundo nunca repetia e o efeito era redisparado na marra pelo jogo. */
    iPlayer->SetRepeats(iLoop ? KMdaRepeatForever : 0, TTimeIntervalMicroSeconds(0));
    iPlayer->Play();
    iPlaying = ETrue;
    iSince = 0;
    return ETrue;
}

/* ---- MMdaAudioPlayerCallback: SO anotam. Nada aqui toca no iPlayer. ---- */

void CMidiVoice::MapcInitComplete(TInt aError, const TTimeIntervalMicroSeconds& /*aDur*/)
{
    if (aError != KErrNone){ if (iErr == KErrNone) iErr = aError; return; }
    iReady = ETrue;
}

void CMidiVoice::MapcPlayComplete(TInt aError)
{
    iPlaying = EFalse;
    /* KErrCancel e o nosso proprio Stop; nao e falha para o usuario. */
    if (aError != KErrNone && aError != KErrCancel && iErr == KErrNone) iErr = aError;
}

/* ===================== CWolphunAudio ===================== */

CWolphunAudio* CWolphunAudio::NewL(RFs& aFs)
{
    CWolphunAudio* self = new (ELeave) CWolphunAudio(aFs);
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
}

CWolphunAudio::CWolphunAudio(RFs& aFs)
: iFs(aFs), iMixer(0), iEnabled(ETrue),
  iStream(0), iStreamOpen(EFalse), iStreamOpening(EFalse), iStreamErr(KErrNone),
  iVoices(0), iPump(0), iMusicErr(KErrNone), iMusicCount(0), iPlays(0)
{
    for (TInt i = 0; i < KMaxVoices; i++){ iSlot[i].iVoice = 0; iSlot[i].iKey = 0; iSlot[i].iLen = 0; }
}

void CWolphunAudio::ConstructL()
{
    /* Nada de audio e aberto aqui: so quando uma ROM for carregada (Preload). */
}

CWolphunAudio::~CWolphunAudio()
{
    CloseAll();
}

void CWolphunAudio::SetMixer(VSound* aMixer) { iMixer = aMixer; }

void CWolphunAudio::SetCacheDir(const TDesC& aPath) { iCacheDir = aPath; }

void CWolphunAudio::SetEnabled(TBool aOn)
{
    iEnabled = aOn;
    if (!aOn) CloseAll();
}

void CWolphunAudio::CloseAll()
{
    if (iPump){ iPump->Cancel(); delete iPump; iPump = 0; }

    for (TInt i = 0; i < KMaxVoices; i++)
    {
        delete iSlot[i].iVoice; iSlot[i].iVoice = 0;
        iSlot[i].iKey = 0; iSlot[i].iLen = 0;
    }
    iVoices = 0;

    if (iStream)
    {
        iStream->Stop();            /* sincrono e seguro mesmo se nunca abriu */
        delete iStream; iStream = 0;
    }
    iStreamOpen = EFalse;
    iStreamOpening = EFalse;
}

/* ---------------- pre-carga: abrir tudo na carga da ROM ---------------- */

static inline TUint32 rdBE32(const u8* d){ return ((TUint32)d[0]<<24)|((TUint32)d[1]<<16)|((TUint32)d[2]<<8)|d[3]; }

/* Tamanho total de um SMF que comeca em `p`, ou 0 se nao for um SMF coerente.
   Formato: "MThd" len(4) corpo | "MTrk" len(4) corpo | ... */
static TInt SmfLength(const u8* p, TInt avail)
{
    if (avail < 14) return 0;
    if (p[0]!='M'||p[1]!='T'||p[2]!='h'||p[3]!='d') return 0;
    const TUint32 hdr = rdBE32(p+4);
    if (hdr < 6 || hdr > 64) return 0;
    const TInt ntrks = ((TInt)p[10] << 8) | p[11];
    if (ntrks <= 0 || ntrks > 64) return 0;

    TInt off = 8 + (TInt)hdr;
    for (TInt t = 0; t < ntrks; t++)
    {
        if (off + 8 > avail) return 0;
        if (p[off]!='M'||p[off+1]!='T'||p[off+2]!='r'||p[off+3]!='k') return 0;
        const TUint32 tl = rdBE32(p + off + 4);
        if (tl > (TUint32)(avail - off - 8)) return 0;
        off += 8 + (TInt)tl;
    }
    return off;
}

void CWolphunAudio::Preload(const u8* aImage, TInt aLen)
{
    if (!iEnabled || !aImage || aLen <= 0) return;

    TInt found = 0;
    for (TInt i = 0; i + 14 <= aLen && iVoices < KMaxVoices; )
    {
        if (aImage[i]=='M' && aImage[i+1]=='T' && aImage[i+2]=='h' && aImage[i+3]=='d')
        {
            const TInt n = SmfLength(aImage + i, aLen - i);
            if (n > 0)
            {
                if (SlotFor(aImage + i, n) == KErrNotFound) { AddVoice(aImage + i, n); found++; }
                i += n;
                continue;
            }
        }
        i++;
    }
    WPLOG2("pre-carga: %d SMF na ROM, %d vozes abertas", found, iVoices);
    if (iVoices > 0) StartPump();
}

/* ---------------- vozes ---------------- */

void CWolphunAudio::MidiPath(TInt aIndex, TDes& aOut) const
{
    aOut = iCacheDir;
    aOut.AppendFormat(KMidiName, aIndex);
}

TInt CWolphunAudio::SlotFor(const u8* aData, TInt aLen)
{
    TUint32 sum = 0;
    for (TInt i = 0; i < aLen; i++) sum = sum * 31u + aData[i];
    for (TInt i = 0; i < KMaxVoices; i++)
        if (iSlot[i].iVoice && iSlot[i].iLen == aLen && iSlot[i].iKey == sum) return i;
    return KErrNotFound;
}

TInt CWolphunAudio::AddVoice(const u8* aData, TInt aLen)
{
    if (iCacheDir.Length() == 0) return KErrNotFound;

    TInt idx = KErrNotFound;
    for (TInt i = 0; i < KMaxVoices; i++) if (!iSlot[i].iVoice){ idx = i; break; }
    /* Sem slot livre: RECUSA. Reaproveitar significaria fechar um controlador MMF no
       meio do jogo -- a origem dos travamentos. Melhor um efeito mudo que um crash. */
    if (idx == KErrNotFound) return KErrNotFound;

    TFileName path;
    MidiPath(idx, path);

    RFile f;
    TInt err = f.Replace(iFs, path, EFileWrite);
    if (err != KErrNone){ iMusicErr = err; return KErrNotFound; }
    err = f.Write(TPtrC8(aData, aLen));
    if (err == KErrNone) err = f.Flush();
    f.Close();
    if (err != KErrNone){ iMusicErr = err; return KErrNotFound; }

    CMidiVoice* v = 0;
    TRAP(err, v = CMidiVoice::NewL(path));
    if (err != KErrNone || !v){ iMusicErr = err ? err : KErrNoMemory; return KErrNotFound; }

    TUint32 sum = 0;
    for (TInt i = 0; i < aLen; i++) sum = sum * 31u + aData[i];

    iSlot[idx].iVoice = v;
    iSlot[idx].iKey   = sum;
    iSlot[idx].iLen   = aLen;
    iVoices++;
    return idx;
}

void CWolphunAudio::StartPump()
{
    if (iPump) return;
    TRAPD(err, iPump = CPeriodic::NewL(EPriorityHigh));
    if (err != KErrNone){ iPump = 0; return; }   /* sem relogio: fica mudo, nao cai */
    iPump->Start(KPumpUs, KPumpUs, TCallBack(PumpCb, this));
}

TInt CWolphunAudio::PumpCb(TAny* aSelf)
{
    static_cast<CWolphunAudio*>(aSelf)->PumpAll();
    return 1;
}

void CWolphunAudio::PumpAll()
{
    for (TInt i = 0; i < KMaxVoices; i++)
        if (iSlot[i].iVoice)
        {
            if (iSlot[i].iVoice->Pump()) iPlays++;
            const TInt e = iSlot[i].iVoice->Error();
            if (e != KErrNone && iMusicErr == KErrNone) iMusicErr = e;
        }
}

/* vPlayResource chega aqui. */
int CWolphunAudio::PlayMusic(const u8* aData, int aLen, bool aLoop)
{
    if (!iEnabled || !aData || aLen <= 0) return 1;

    /* SO toca se for mesmo um SMF, igual ao emulador de PC: vPlayResource entrega
       qualquer tipo de recurso, nem todo e musica. */
    if (aLen < 4 || aData[0]!='M' || aData[1]!='T' || aData[2]!='h' || aData[3]!='d')
        return 1;

    iMusicCount++;

    TInt idx = SlotFor(aData, aLen);
    if (idx == KErrNotFound)
    {
        /* nao apareceu na pre-carga (SMF montado em runtime, ou ROM comprimida):
           abre agora. Custa caro, mas e a excecao, nao a regra. */
        idx = AddVoice(aData, aLen);
        if (idx == KErrNotFound) return 0;
        StartPump();
    }

    /* Registra o pedido E JA TENTA EMITIR o Play aqui mesmo. Estamos em contexto normal
       (dentro do vPlayResource da VM), que e exatamente a condicao que o Pump garantia --
       a regra que importa e "nunca emitir Play/Stop de dentro de um callback do MMF", e
       esta nao e um. Esperar o temporizador custava a fatia atual da VM MAIS ate 20 ms, e
       como active objects no Symbian NAO sao preemptivos, o Pump so roda quando o RunSlice
       da engine retorna: no aparelho (Nokia E63) o efeito do inimigo morrendo so saia muito
       depois, ou ficava preso ate o menu ceder o scheduler. O Pump continua como rede de
       seguranca (parada, anti-repique e pedidos feitos antes da voz ficar pronta). */
    iSlot[idx].iVoice->Request(aLoop ? ETrue : EFalse);
    if (iSlot[idx].iVoice->Pump()) iPlays++;
    return 1;
}

void CWolphunAudio::StopMusic()
{
    for (TInt i = 0; i < KMaxVoices; i++)
        if (iSlot[i].iVoice) iSlot[i].iVoice->RequestStop();
}

/* ---------------- PCM (VSound) ---------------- */

void CWolphunAudio::Start()
{
    if (!iEnabled || !iMixer) return;
    if (iStreamOpen || iStreamOpening) return;        /* idempotente */
    /* Depois de um MaoscPlayComplete o stream fica parado mas VIVO. Sem este descarte, a
       atribuicao abaixo passaria por cima do ponteiro e vazaria um objeto a cada vBeep --
       e ha jogos que chamam vBeep centenas de vezes por segundo. */
    if (iStream){ iStream->Stop(); delete iStream; iStream = 0; }

    TRAPD(err, iStream = CMdaAudioOutputStream::NewL(*this));
    if (err != KErrNone || !iStream){ iStreamErr = err ? err : KErrNoMemory; iStream = 0; return; }

    iStreamOpening = ETrue;
    TMdaAudioDataSettings settings;
    settings.Query();
    settings.iCaps    = TMdaAudioDataSettings::ESampleRateFixed;
    settings.iFlags   = 0;
    TRAP(err, iStream->Open(&settings));
    if (err != KErrNone)
    {
        iStreamErr = err;
        iStreamOpening = EFalse;
        delete iStream; iStream = 0;
    }
}

void CWolphunAudio::Stop()
{
    if (iStream){ iStream->Stop(); delete iStream; iStream = 0; }
    iStreamOpen = EFalse;
    iStreamOpening = EFalse;
}

void CWolphunAudio::MaoscOpenComplete(TInt aError)
{
    iStreamOpening = EFalse;
    iStreamErr = aError;
    if (aError != KErrNone){ delete iStream; iStream = 0; return; }

    /* 22050 Hz estereo, 16 bits com sinal -- o formato que o VSound produz. Se o
       aparelho recusar a taxa, cai para 16 kHz, que todo S60 3rd aceita. */
    TRAPD(err, iStream->SetAudioPropertiesL(TMdaAudioDataSettings::ESampleRate22050Hz,
                                            TMdaAudioDataSettings::EChannelsStereo));
    if (err != KErrNone)
    {
        TRAP(err, iStream->SetAudioPropertiesL(TMdaAudioDataSettings::ESampleRate16000Hz,
                                               TMdaAudioDataSettings::EChannelsStereo));
    }
    if (err != KErrNone){ iStreamErr = err; delete iStream; iStream = 0; return; }

    iStream->SetVolume(iStream->MaxVolume());
    iStreamOpen = ETrue;

    FillAndWrite(0);   /* dois buffers na fila: um toca enquanto o outro enche */
    FillAndWrite(1);
}

void CWolphunAudio::FillAndWrite(TInt aIndex)
{
    if (!iStream || !iStreamOpen || !iMixer) return;
    if (aIndex < 0 || aIndex > 1) return;

    TBuf8<KBufBytes>& b = iBuf[aIndex];
    b.SetLength(KBufBytes);
    iMixer->RenderFrames((i16*)const_cast<TUint8*>(b.Ptr()), KFramesPerBuf);

    TRAPD(err, iStream->WriteL(b));
    if (err != KErrNone) iStreamErr = err;
}

void CWolphunAudio::MaoscBufferCopied(TInt aError, const TDesC8& aBuffer)
{
    if (aError != KErrNone){ iStreamErr = aError; return; }
    const TInt idx = (&aBuffer == &iBuf[0]) ? 0 : 1;
    FillAndWrite(idx);
}

void CWolphunAudio::MaoscPlayComplete(TInt aError)
{
    /* KErrUnderflow e normal quando paramos de alimentar; nao e falha para o usuario */
    if (aError != KErrNone && aError != KErrUnderflow) iStreamErr = aError;
    iStreamOpen = EFalse;
}
