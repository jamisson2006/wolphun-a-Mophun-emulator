/* WolphunEngine.cpp - ver WolphunEngine.h */
#include "WolphunEngine.h"
#include "Vmgp.h"
#include "Pip2.h"
#include "Renderer3D.h"
#include "MophunModel.h"
#include "WolphunAudio.h"

#include <aknnotewrappers.h>
#include <bautils.h>
#include "WolphunLang.h"
#include "WolphunVibra.h"

/* Orcamento por fatia. A VM roda ate o jogo chamar vFlipScreen (quadro pronto) ou ate
   gastar este numero de instrucoes. O teto evita travar a interface quando o jogo entra
   num laco de espera (varios jogos ficam em while(vGetTickCount()<alvo)). */
/* Teto DURO de instrucoes por fatia. Nao e mais o que termina a fatia no caso comum --
   quem termina e o relogio (KSliceBudgetMs). Um teto fixo em instrucoes e um orcamento de
   tempo DISFARCADO: 400.000 instrucoes custam poucos ms num PC e dezenas de ms num ARM11
   de 369 MHz (Nokia E63). Como active objects nao sao preemptivos, o que esse numero de
   fato define e por quanto tempo NADA MAIS roda -- nem o Pump do audio, nem os callbacks
   MapcInitComplete das vozes, nem a tecla. Ficou so como limite de seguranca. */
const TInt KInstructionsPerSlice = 400000;
/* Orcamento REAL da fatia, em milissegundos de relogio. Limita a latencia que a engine
   impoe ao resto do sistema, independente da velocidade do aparelho. */
/* padrao; sobrescrito por iSliceBudgetMs conforme a economia de bateria escolhida */
const TInt KSliceBudgetMs        = 10;
/* De quantas em quantas instrucoes olhar o relogio.
   ERA 4096, e esse numero NAO FUNCIONAVA: o orcamento de 10 ms era consultado a cada
   4096 instrucoes da VM, mas UMA instrucao pode ser um vRenderPrimitive que desenha
   centenas de triangulos. Entre duas leituras do relogio cabia um quadro inteiro, de
   modo que a fatia de "10 ms" virava 100 ms ou mais e o CPeriodic de 20 ms reentrava
   imediatamente: CPU em 100% CONTINUO.
   Isso so apareceu quando o rasterizador ficou rapido: um Nokia E63 com bateria gasta
   apagava os LEDs do teclado, baixava o brilho e desligava com a logo -- queda de
   tensao sob carga sustentada, nao travamento.
   256 custa uma leitura barata de NTickCount a cada 256 instrucoes (desprezivel perto de
   interpreta-las) e faz o orcamento valer de verdade. */
const TInt KClockCheckMask       = 255;
const TInt KTimerIntervalUs      = 20000;   /* ~50 Hz */

CWolphunEngine* CWolphunEngine::NewL(RFs& aFs, MWolphunObserver& aObserver)
{
    CWolphunEngine* self = new (ELeave) CWolphunEngine(aFs, aObserver);
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
}

CWolphunEngine::CWolphunEngine(RFs& aFs, MWolphunObserver& aObserver)
: iFs(aFs), iObserver(aObserver),
  iTimer(0), iStore(0), iLastFrameTick(0), iFramePeriodMs(40), iSliceBudgetMs(KSliceBudgetMs),
  iExe(0), iVm(0), iScreen(0), iSdk(0), iR3d(0), iAudio(0),
  iBitmap(0), iVibra(0), iFlipPending(EFalse), iFrames(0)
{
}

void CWolphunEngine::ConstructL()
{
    /* ABAIXO do Pump do audio (EPriorityHigh) e da UI: sempre que a engine e outro active
       object estiverem prontos ao mesmo tempo, o outro roda primeiro. Com a engine em
       EPriorityStandard e o temporizador cronicamente atrasado (a fatia demora mais que o
       intervalo), ela disputava de igual para igual com o resto. */
    iTimer = CPeriodic::NewL(CActive::EPriorityLow);
    iAudio = CWolphunAudio::NewL(iFs);
    iStore = CWolphunConfigStore::NewL(iFs);
    /* os SMF do vPlayResource sao gravados aqui e abertos POR ARQUIVO: nenhum
       buffer nosso fica exposto a thread do controlador MIDI. */
    iAudio->SetCacheDir(iStore->PrivatePath());
    iStartTime.HomeTime();
}

CWolphunEngine::~CWolphunEngine()
{
    if (iTimer){ iTimer->Cancel(); delete iTimer; }
    DestroyGame();
    delete iAudio;
    delete iBitmap;
    delete iVibra;
    delete iStore;
}

/* ---------------- ciclo de vida do jogo ---------------- */
void CWolphunEngine::DestroyGame()
{
    if (iSdk) iSdk->flushSaves();     /* garante o save nativo do jogo */
    /* fecha a saida ANTES de destruir o Sdk: o callback do stream le o mixer
       que vive dentro dele. */
    if (iAudio){ iAudio->CloseAll(); iAudio->SetMixer(0); }
    delete iR3d;    iR3d = 0;
    delete iSdk;    iSdk = 0;
    delete iVm;     iVm = 0;
    delete iScreen; iScreen = 0;
    delete iExe;    iExe = 0;
}

void CWolphunEngine::LoadRomL(const TDesC& aFullPath)
{
    StopRunning();
    DestroyGame();

    iRomPath = aFullPath;
    TParsePtrC parse(aFullPath);
    iRomName = parse.NameAndExt();

    iStore->LoadL(iRomName, iConfig);     /* resolucao e demais opcoes SAO POR JOGO */
    BuildGameL(aFullPath);
    StartRunning();
}

void CWolphunEngine::RestartL()
{
    if (iRomPath.Length() == 0) return;
    TFileName path = iRomPath;
    StopRunning();
    DestroyGame();
    BuildGameL(path);
    StartRunning();
}

void CWolphunEngine::BuildGameL(const TDesC& aFullPath)
{
    /* le a ROM inteira */
    RFile file;
    User::LeaveIfError(file.Open(iFs, aFullPath, EFileRead | EFileShareReadersOnly));
    CleanupClosePushL(file);
    TInt size = 0;
    User::LeaveIfError(file.Size(size));
    if (size <= 0) User::Leave(KErrCorrupt);
    HBufC8* data = HBufC8::NewLC(size);
    TPtr8 ptr = data->Des();
    User::LeaveIfError(file.Read(ptr));

    const char* err = 0;
    iExe = Vmgp::Create(ptr.Ptr(), size, &err);
    if (!iExe)
    {
        /* mensagem clara do nucleo (ex.: ROM "packed" S60/PPC) */
        TBuf<160> msg;
        TPtrC8 e8((const TUint8*)err);
        msg.Copy(e8.Left(msg.MaxLength()));
        CAknErrorNote* note = new (ELeave) CAknErrorNote(ETrue);
        note->ExecuteLD(msg);
        CleanupStack::PopAndDestroy(2);   /* data, file */
        User::Leave(KErrNotSupported);
    }
    CleanupStack::PopAndDestroy(2);       /* data, file (o Vmgp copiou os bytes) */

    /* O NOME da ROM diz para que aparelho o jogo foi feito. Isso decide duas coisas:
       a resolucao no modo automatico e, principalmente, o modelo que o vGetCaps vai
       reportar -- jogos que nao sao "multiscreen" abortam com "Terminal not supported!"
       se a resposta nao for uma que eles conhecem. */
    TBuf8<160> name8;
    name8.Copy(iRomName.Left(iRomName.Length() < 159 ? iRomName.Length() : 159));
    const char* romAscii = (const char*)name8.PtrZ();

    TSize res;
    if (iConfig.iResolution == EWolphunResAuto)
    {
        int aw = 176, ah = 208;
        MophunModel::AutoRes(romAscii, &aw, &ah);
        res = TSize(aw, ah);
    }
    else
    {
        res = TWolphunConfig::ResolutionSize(iConfig.iResolution);
    }
    iScreen = new (ELeave) Screen(res.iWidth, res.iHeight, this);

    TInt heap = (TInt)iExe->heapBytesRequested();
    if (heap < 512*1024) heap = 512*1024;
    TInt stack = (TInt)iExe->stackSize;
    if (stack < 64*1024) stack = 64*1024;
    iVm = new (ELeave) Pip2(iExe, heap, stack);

    iSdk = new (ELeave) Sdk(iVm, iScreen);
    {
        const int model = MophunModel::FromName(romAscii);
        if (model >= 0)
        {
            iSdk->deviceModel  = model;
            iSdk->deviceVendor = MophunModel::VendorFor(model);
        }
        /* -1 = multiscreen/desconhecido: mantem o padrao T610 do Sdk, que e o que o
           emulador de PC usa e o que a maioria dos jogos adaptativos espera. */
    }
    iSdk->fs    = this;
    /* o mixer vive dentro do Sdk e e recriado a cada jogo: a saida precisa
       apontar para o novo antes de qualquer som tocar. */
    if (iAudio)
    {
        iAudio->SetMixer(&iSdk->vsound);
        iSdk->audio = iAudio;
        /* ABRE AGORA todos os SMF da ROM. Cada abertura sobe um controlador MMF; pagar
           isso aqui, na pausa de carregamento, e o que impede o som de sair minutos
           depois do tiro (medido no rastro do EKA2L1). */
        iAudio->Preload(iExe->all, iExe->allLen);
    }
    iVm->sdk    = iSdk;

    /* o renderer 3D so existe se o jogo for 3D: num jogo 2D seriam centenas de KB de
       z-buffer parados. */
    if (iExe->importsSymbol("vInit3D"))
    {
        iR3d = new (ELeave) Renderer3D(iVm, iScreen);
        iSdk->r3d = iR3d;
    }
    ApplyLiveSettings();

    AllocBitmapL(res);
    iVm->runConstructors();
    iFlipPending = EFalse;
    iFrames = 0;
}

void CWolphunEngine::AllocBitmapL(const TSize& aSize)
{
    delete iBitmap; iBitmap = 0;
    iBitmap = new (ELeave) CFbsBitmap();
    /* EColor16MU = 32 bits por pixel (xRGB): mesmo layout do framebuffer da VM, entao a
       copia por quadro e um memcpy direto, sem conversao por pixel. */
    User::LeaveIfError(iBitmap->Create(aSize, EColor16MU));
    /* O Create NAO zera os pixels. Sem isto, enquanto o jogo nao produz o primeiro
       quadro a tela mostrava o LIXO da memoria -- que no E63 apareceu como um retangulo
       branco solido, dando a impressao de que nada funcionava. */
    iBitmap->LockHeap();
    TUint32* px = iBitmap->DataAddress();
    const TInt words = (iBitmap->ScanLineLength(aSize.iWidth, EColor16MU) / 4) * aSize.iHeight;
    for (TInt i = 0; i < words; i++) px[i] = 0xFF000000;
    iBitmap->UnlockHeap();
}

void CWolphunEngine::CloseRom()
{
    StopRunning();
    DestroyGame();
    iRomPath.Zero();
    iRomName.Zero();
}

TSize CWolphunEngine::GameSize() const
{
    if (iScreen) return TSize(iScreen->w, iScreen->h);
    return TWolphunConfig::ResolutionSize(iConfig.iResolution);
}

void CWolphunEngine::SaveConfigL()
{
    if (iRomName.Length()) iStore->SaveL(iRomName, iConfig);
}

void CWolphunEngine::ApplyLiveSettings()
{
    WolphunLang::SetLanguage(iConfig.iLanguage);

    /* ECONOMIA DE BATERIA. Cada nivel mexe em DUAS coisas, e a segunda e a que importa
       no caso pesado:
         - iFramePeriodMs: teto de quadros. So age quando a cena e LEVE; numa cena pesada
           o jogo ja esta abaixo do teto e esse limite e inerte -- foi por isso que
           limitar a 20 fps nao impediu o E63 de desligar.
         - iSliceBudgetMs: quanto tempo a VM roda dentro de cada tique de 20 ms. ESTE
           limita o ciclo de trabalho da CPU independentemente do peso da cena, que e o
           que a tensao da bateria realmente sente. */
    switch (iConfig.iPowerSave){
        case 0:  iFramePeriodMs = 50; iSliceBudgetMs =  5; break;  /* maxima: ~25% de CPU */
        case 2:  iFramePeriodMs = 33; iSliceBudgetMs = 10; break;  /* media:  ~50% */
        case 3:  iFramePeriodMs =  0; iSliceBudgetMs = 18; break;  /* desligada: ~90% */
        default: iFramePeriodMs = 40; iSliceBudgetMs =  8; break;  /* alta:   ~40% */
    }
    /* som e filtro de textura valem imediatamente; resolucao exige reiniciar o jogo */
    const TBool sound = (iConfig.iSound != 0);
    if (iSdk){ iSdk->soundEnabled = sound; iSdk->vsound.enabled = sound ? true : false; }
    if (iAudio) iAudio->SetEnabled(sound);     /* desligar fecha o canal, nao so silencia */
    if (iR3d) iR3d->texFilterForce = iConfig.iTexFilter;

    /* VIBRACAO. Criada so quando pedida: abrir o cliente custa uma sessao de servidor, e
       nao faz sentido pagar por ela num aparelho sem motor. CWolphunVibra::New() devolve 0
       justamente nesse caso, e 0 em iSdk->vibra faz o vSysCtl voltar a ser no-op -- sem
       nenhum teste a mais no caminho quente do interpretador. */
    if (iConfig.iVibrate && !iVibra) iVibra = CWolphunVibra::New();
    else if (!iConfig.iVibrate && iVibra){ iVibra->StopVibra(); delete iVibra; iVibra = 0; }
    if (iSdk) iSdk->vibra = iVibra;
}

/* ---------------- execucao em fatias ---------------- */
void CWolphunEngine::StartRunning()
{
    if (!iVm || !iTimer || iTimer->IsActive()) return;
    iTimer->Start(KTimerIntervalUs, KTimerIntervalUs, TCallBack(TimerTick, this));
}

void CWolphunEngine::StopRunning()
{
    if (iTimer) iTimer->Cancel();
}

TInt CWolphunEngine::TimerTick(TAny* aSelf)
{
    static_cast<CWolphunEngine*>(aSelf)->RunSlice();
    return 1;
}

void CWolphunEngine::RunSlice()
{
    if (!iVm) return;
    if (iVm->stopped)
    {
        StopRunning();
        iObserver.GameStopped();
        return;
    }
    /* RITMO DE QUADROS -- e uma correcao de BATERIA, nao de velocidade.
       O CPeriodic e de 20 ms, mas uma chamada pesada da SDK (um vFlipScreen com a cena
       cheia) nao e interrompivel: ela estoura a fatia, o tique seguinte ja esta vencido
       e o timer reentra na hora. Com o rasterizador lento isso rendia 2 fps e ninguem
       reparava; rapido, a CPU passou a ficar 100% ocupada SEM FOLGA, e um Nokia E63 com
       bateria gasta afunda a tensao, acusa "bateria descarregada" e desliga sozinho no
       meio do Rally Pro Contest.
       A correcao e simplesmente NAO rodar quando o quadro esta adiantado: sair daqui
       deixa o aparelho de fato ocioso ate o proximo tique. */
    if (iFramePeriodMs > 0 && iFrames > 0
        && (TInt)(User::NTickCount() - iLastFrameTick) < iFramePeriodMs)
        return;

    iFlipPending = EFalse;
    const i64 budgetEnd = iVm->instr + KInstructionsPerSlice;
    const TUint startTicks = User::NTickCount();
    TInt sinceClock = 0;
    while (!iVm->stopped && !iFlipPending && iVm->instr < budgetEnd)
    {
        iVm->step();
        /* devolve o controlo ao active scheduler assim que o orcamento de TEMPO acabar */
        if ((++sinceClock & KClockCheckMask) == 0
            && (TInt)(User::NTickCount() - startTicks) >= iSliceBudgetMs)
            break;
    }

    if (iFlipPending)
    {
        iLastFrameTick = User::NTickCount();
        iFrames++;
        CopyFrameToBitmap();
        iObserver.FrameReady();
    }
    else if (iFrames == 0)
    {
        /* ainda sem o 1o quadro: redesenha assim mesmo para o diagnostico atualizar */
        iObserver.FrameReady();
    }
}

/* Append que NUNCA estoura o buffer do chamador. Virou necessario quando estes textos
   passaram a ser traduzidos: as versoes em russo e ucraniano sao bem mais longas que as
   em portugues, e um TDes::Append que passa do MaxLength faz PANIC, nao trunca. */
static void AppFit(TDes& aOut, const TDesC& aText)
{
    const TInt livre = aOut.MaxLength() - aOut.Length();
    if (livre <= 0) return;
    aOut.Append(aText.Left(Min(aText.Length(), livre)));
}

/* Texto curto de diagnostico (ver declaracao no .h). */
void CWolphunEngine::DiagnosticsL(TDes& aOut) const
{
    aOut.Zero();
    if (!iVm){ aOut.Copy(WolphunLang::T(EStrNoGameNow)); return; }
    aOut.Copy(_L("instr "));
    aOut.AppendNum(iVm->instr / 1000);
    AppFit(aOut, WolphunLang::T(EStrDiagFrames));
    aOut.AppendNum(iFrames);
    if (iVm->stopped) AppFit(aOut, WolphunLang::T(EStrDiagStop));
    else if (iFrames == 0) AppFit(aOut, WolphunLang::T(EStrDiagNoDraw));
    if (iSdk && iSdk->stubCalls > 0)
    {
        aOut.Append(_L("  API? "));
        aOut.AppendNum((TInt)iSdk->stubCalls);
    }
    if (iExe && iExe->wasEncrypted) aOut.Append(_L("  [DRM ok]"));
}

void CWolphunEngine::CopyFrameToBitmap()
{
    if (!iBitmap || !iScreen) return;
    const TSize sz = iBitmap->SizeInPixels();
    if (sz.iWidth != iScreen->w || sz.iHeight != iScreen->h) return;
    iBitmap->LockHeap();
    TUint32* dst = iBitmap->DataAddress();
    const TInt stride = iBitmap->ScanLineLength(sz.iWidth, EColor16MU) / 4;
    for (TInt y = 0; y < sz.iHeight; y++)
        Mem::Copy(dst + y*stride, iScreen->fb + y*iScreen->w, sz.iWidth * 4);
    iBitmap->UnlockHeap();
}

/* ---------------- entrada ---------------- */
void CWolphunEngine::SetButton(TInt aBit, TBool aDown)
{
    if (iScreen) iScreen->setButtonBit(aBit, aDown ? true : false);
}
void CWolphunEngine::SetKeypad(TInt aAscii, TBool aDown)
{
    if (iScreen) iScreen->setKeypad(aAscii, aDown ? true : false);
}

/* ---------------- ScreenHost ---------------- */
void CWolphunEngine::Present()
{
    /* nao desenha aqui: so marca o quadro. O RunSlice devolve o controle ao Symbian e
       e ele quem copia e pede o redesenho - desenhar de dentro da VM manteria o laco
       preso e a interface travada. */
    iFlipPending = ETrue;
}

u32 CWolphunEngine::TickMs()
{
    TTime now; now.HomeTime();
    TTimeIntervalMicroSeconds delta = now.MicroSecondsFrom(iStartTime);
    return (u32)(delta.Int64() / 1000);
}

u32 CWolphunEngine::EpochSeconds()
{
    TTime now; now.UniversalTime();
    TTime epoch(TDateTime(1970, EJanuary, 0, 0, 0, 0, 0));
    return (u32)(now.MicroSecondsFrom(epoch).Int64() / 1000000);
}

void CWolphunEngine::DateTime(int* aY,int* aMo,int* aD,int* aH,int* aMi,int* aS)
{
    TTime now; now.HomeTime();
    TDateTime dt = now.DateTime();
    if (aY)  *aY  = dt.Year();
    if (aMo) *aMo = dt.Month() + 1;      /* TMonth e 0-based */
    if (aD)  *aD  = dt.Day() + 1;        /* dia do mes tambem e 0-based */
    if (aH)  *aH  = dt.Hour();
    if (aMi) *aMi = dt.Minute();
    if (aS)  *aS  = dt.Second();
}

int CWolphunEngine::MessageBox(const char* aTitle, const char* aMsg)
{
    /* vMsgBox do jogo -> nota nativa do sistema */
    /* o jogo manda texto de 8 bits; TDes16::Copy converte, mas Append nao aceita 8 bits
       -- por isso convertemos num buffer temporario antes de concatenar. */
    TBuf<256> text;
    TBuf<256> part;
    if (aTitle)
    {
        TPtrC8 t8((const TUint8*)aTitle);
        part.Copy(t8.Left(part.MaxLength()));
        text.Append(part.Left(text.MaxLength() - text.Length()));
        if (text.Length() + 2 <= text.MaxLength()) text.Append(_L(": "));
    }
    if (aMsg)
    {
        TPtrC8 m8((const TUint8*)aMsg);
        part.Copy(m8.Left(part.MaxLength()));
        const TInt room = text.MaxLength() - text.Length();
        if (room > 0) text.Append(part.Left(Min(room, part.Length())));
    }
    TRAP_IGNORE(
        CAknInformationNote* note = new (ELeave) CAknInformationNote(ETrue);
        note->ExecuteLD(text);
    );
    return 1;   /* OK */
}

/* ---------------- SdkFileSystem ---------------- */
void CWolphunEngine::SavePathL(const char* aName, TDes& aOut)
{
    aOut.Zero();
    aOut.Append(iStore->PrivatePath());
    /* prefixo com o nome da ROM: cada jogo tem os seus saves */
    TInt dot = iRomName.LocateReverse('.');
    TPtrC base = (dot > 0) ? iRomName.Left(dot) : TPtrC(iRomName);
    for (TInt i = 0; i < base.Length() && aOut.Length() < aOut.MaxLength()-24; i++)
    {
        TChar c = base[i];
        aOut.Append(c.IsAlphaDigit() ? c : TChar('_'));
    }
    aOut.Append('-');
    for (const char* p = aName; *p && aOut.Length() < aOut.MaxLength()-5; p++)
    {
        TChar c = (TUint)(TUint8)*p;
        aOut.Append(c.IsAlphaDigit() ? c : TChar('_'));
    }
    aOut.Append(_L(".sav"));
}

u8* CWolphunEngine::ReadSave(const char* aName, int* aOutLen)
{
    if (aOutLen) *aOutLen = 0;
    if (!aName) return 0;
    TFileName path;
    TRAPD(err, SavePathL(aName, path));
    if (err != KErrNone) return 0;
    RFile f;
    if (f.Open(iFs, path, EFileRead | EFileShareReadersOnly) != KErrNone) return 0;
    TInt size = 0;
    if (f.Size(size) != KErrNone || size <= 0){ f.Close(); return 0; }
    u8* buf = new u8[size];
    if (!buf){ f.Close(); return 0; }
    TPtr8 p(buf, size);
    TInt r = f.Read(p);
    f.Close();
    if (r != KErrNone || p.Length() != size){ delete[] buf; return 0; }
    if (aOutLen) *aOutLen = size;
    return buf;
}

void CWolphunEngine::WriteSave(const char* aName, const u8* aData, int aLen)
{
    if (!aName) return;
    TFileName path;
    TRAPD(err, SavePathL(aName, path));
    if (err != KErrNone) return;
    RFile f;
    if (f.Replace(iFs, path, EFileWrite) != KErrNone) return;
    if (aData && aLen > 0)
    {
        TPtrC8 p(aData, aLen);
        f.Write(p);
    }
    f.Close();
}

u8* CWolphunEngine::ReadCompanion(const char* aName, int* aOutLen)
{
    /* arquivo de dados ao lado da ROM (ex.: "dvc.mpc"): somente leitura */
    if (aOutLen) *aOutLen = 0;
    if (!aName || iRomPath.Length() == 0) return 0;
    TParsePtrC parse(iRomPath);
    TFileName path;
    path.Copy(parse.DriveAndPath());
    for (const char* p = aName; *p; p++)
    {
        if (*p == '/' || *p == '\\' || *p == ':') return 0;     /* so nome simples */
        if (path.Length() >= path.MaxLength()-1) return 0;
        path.Append((TChar)(TUint)(TUint8)*p);
    }
    RFile f;
    if (f.Open(iFs, path, EFileRead | EFileShareReadersOnly) != KErrNone) return 0;
    TInt size = 0;
    if (f.Size(size) != KErrNone || size <= 0){ f.Close(); return 0; }
    u8* buf = new u8[size];
    if (!buf){ f.Close(); return 0; }
    TPtr8 p(buf, size);
    TInt r = f.Read(p);
    f.Close();
    if (r != KErrNone){ delete[] buf; return 0; }
    if (aOutLen) *aOutLen = p.Length();
    return buf;
}

/* ---------------- som ----------------
   O que o aparelho respondeu ao abrir cada caminho de audio. Serve para distinguir
   "o jogo nao toca nada" de "o aparelho recusou o audio". */
void CWolphunEngine::AudioDiagnostics(TDes& aOut) const
{
    aOut.Zero();
    if (!iAudio){ aOut.Copy(WolphunLang::T(EStrSndNoOut)); return; }
    aOut.Copy(WolphunLang::T(EStrSndPcm));
    aOut.AppendNum(iAudio->StreamError());
    aOut.Append(_L("  midi err "));
    aOut.AppendNum(iAudio->MusicError());
    AppFit(aOut, WolphunLang::T(EStrSndVoices));
    aOut.AppendNum(iAudio->VoiceCount());     /* SMF abertos na carga da ROM */
    /* PRONTAS = vozes que receberam MapcInitComplete. "vozes" so conta as que foram
       ABERTAS (sincrono); a abertura so vira tocavel quando o callback assincrono chega.
       Se prontas < vozes durante o jogo, o scheduler nao esta recebendo tempo e os sons
       ficam presos ate algo ceder o controlo (ex.: abrir o menu). */
    AppFit(aOut, WolphunLang::T(EStrSndReady));
    aOut.AppendNum(iAudio->ReadyCount());
    AppFit(aOut, WolphunLang::T(EStrSndReq));
    aOut.AppendNum(iAudio->MusicCount());     /* vPlayResource com SMF valido */
    AppFit(aOut, WolphunLang::T(EStrSndPlayed));
    aOut.AppendNum(iAudio->PlayCount());      /* Play() realmente emitidos ao aparelho */
    if (iSdk)
    {
        AppFit(aOut, WolphunLang::T(EStrSndReq));
        aOut.AppendNum((TInt)(iSdk->vsound.beepCount + iSdk->vsound.playCount));
    }
}
