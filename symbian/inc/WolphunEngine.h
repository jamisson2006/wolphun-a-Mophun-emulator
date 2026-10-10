/* WolphunEngine.h - liga o nucleo portavel da VM ao Symbian.
 *
 * Possui Vmgp + Pip2 + Screen + Sdk (+ Renderer3D se o jogo for 3D) e implementa as
 * tres interfaces que o nucleo pede: ScreenHost (tempo/apresentacao/dialogos),
 * SdkFileSystem (saves e arquivos de dados, via RFs). O som fica em CWolphunAudio.
 *
 * A VM roda em FATIAS a partir de um CPeriodic: cada disparo executa instrucoes ate o
 * jogo chamar vFlipScreen (um quadro pronto) ou ate gastar o orcamento. Assim a
 * interface do Symbian continua respondendo - um laco fechado travaria o aparelho.
 */
#ifndef WOLPHUNENGINE_H
#define WOLPHUNENGINE_H

#include <e32base.h>
#include <f32file.h>
#include <fbs.h>

#include "vmcommon.h"
#include "Screen.h"
#include "Sdk.h"
#include "WolphunConfig.h"

class Vmgp;
class Pip2;
class Renderer3D;
class CWolphunAudio;
class CWolphunVibra;

/* a casca avisa a View quando ha um quadro novo */
class MWolphunObserver
{
public:
    virtual void FrameReady() = 0;
    virtual void GameStopped() = 0;
};

class CWolphunEngine : public CBase,
                       public ScreenHost,
                       public SdkFileSystem
{
public:
    static CWolphunEngine* NewL(RFs& aFs, MWolphunObserver& aObserver);
    ~CWolphunEngine();

    /* ---- ciclo de vida do jogo ---- */
    void LoadRomL(const TDesC& aFullPath);
    void CloseRom();
    void RestartL();                      /* recarrega a ROM atual (ex.: trocou a resolucao) */
    TBool HasGame() const { return iVm != 0; }
    const TDesC& RomName() const { return iRomName; }

    /* ---- configuracao ---- */
    TWolphunConfig& Config() { return iConfig; }
    /* diretorio privado do app (onde ficam saves, config por jogo e a lista de pastas) */
    const TDesC& PrivatePath() const { return iStore->PrivatePath(); }
    void SaveConfigL();
    void ApplyLiveSettings();             /* som / filtro: valem sem reiniciar */

    /* ---- execucao ---- */
    void StartRunning();
    void StopRunning();
    TBool IsRunning() const { return iTimer && iTimer->IsActive(); }

    /* ---- saida ---- */
    CFbsBitmap* Bitmap() const { return iBitmap; }
    TSize GameSize() const;
    /* Diagnostico mostrado na tela enquanto o jogo ainda nao produziu nenhum quadro:
       diz se a VM esta avancando, quantas instrucoes ja rodou e se parou. Sem isso, um
       jogo que nao desenha fica indistinguivel de um emulador quebrado. */
    void DiagnosticsL(TDes& aOut) const;
    TInt FrameCount() const { return iFrames; }

    /* ---- entrada ---- */
    void SetButton(TInt aBit, TBool aDown);
    void SetKeypad(TInt aAscii, TBool aDown);

    /* ---- ScreenHost ---- */
    void Present();
    u32  TickMs();
    u32  EpochSeconds();
    void DateTime(int* aY,int* aMo,int* aD,int* aH,int* aMi,int* aS);
    int  MessageBox(const char* aTitle, const char* aMsg);

    /* ---- SdkFileSystem ---- */
    u8*  ReadSave(const char* aName, int* aOutLen);
    void WriteSave(const char* aName, const u8* aData, int aLen);
    u8*  ReadCompanion(const char* aName, int* aOutLen);

    /* ---- som ----
       O escoamento de audio vive em CWolphunAudio (CMdaAudioOutputStream para o PCM do
       VSound + CMdaAudioPlayerUtility para os recursos MIDI do vPlayResource). */
    void AudioDiagnostics(TDes& aOut) const;

private:
    CWolphunEngine(RFs& aFs, MWolphunObserver& aObserver);
    void ConstructL();
    void DestroyGame();
    void BuildGameL(const TDesC& aFullPath);
    void AllocBitmapL(const TSize& aSize);
    void CopyFrameToBitmap();
    /* converte um nome de arquivo logico do jogo (char*) p/ um caminho no dir privado */
    void SavePathL(const char* aName, TDes& aOut);

    static TInt TimerTick(TAny* aSelf);
    void RunSlice();

    RFs&               iFs;
    MWolphunObserver&  iObserver;
    CPeriodic*         iTimer;
    CWolphunConfigStore* iStore;

    /* nucleo portavel */
    Vmgp*       iExe;
    Pip2*       iVm;
    Screen*     iScreen;
    Sdk*        iSdk;
    Renderer3D* iR3d;
    CWolphunAudio* iAudio;      /* saida de som (vive enquanto o motor viver) */
    /* Vibracao. So existe quando ligada nas opcoes E o aparelho tem motor; 0 e o
       estado normal nos dois casos contrarios. Criada/destruida pelo
       ApplyLiveSettings, entao a opcao vale na hora, sem reiniciar o jogo. */
    CWolphunVibra* iVibra;

    CFbsBitmap* iBitmap;
    TBool       iFlipPending;
    TInt        iFrames;      /* quadros produzidos pelo jogo (diagnostico) */
    TUint       iLastFrameTick;  /* NTickCount do ultimo quadro, p/ o limite de quadros */
    TInt        iFramePeriodMs;  /* 0 = sem limite; derivado de iConfig.iPowerSave */
    TInt        iSliceBudgetMs;  /* ms de VM por tique de 20 ms = ciclo de trabalho da CPU */

    TWolphunConfig iConfig;
    TFileName      iRomPath;
    TFileName      iRomName;

    TTime iStartTime;
};

#endif /* WOLPHUNENGINE_H */
