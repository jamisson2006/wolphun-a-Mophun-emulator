/* WolphunAudio.h - saida de som no Symbian.
 *
 *  1) MUSICA / SFX (vPlayResource) -> UM TOCADOR POR SOM, aberto UMA VEZ,
 *     nunca fechado. E o modelo do J2ME: o jogo cria os Players na inicializacao e dali
 *     em diante so chama start(). O som sai pelo sintetizador e pelo banco General MIDI
 *     DO PROPRIO APARELHO -- nada e sintetizado por nos.
 *
 *     AS VOZES SAO ABERTAS NA CARGA DA ROM, nao no primeiro tiro. Varremos a imagem do
 *     jogo atras dos blocos "MThd" e abrimos todos de uma vez enquanto a tela de
 *     carregamento esta no ar. Isto existe porque abrir um recurso MIDI sobe um PROCESSO
 *     controlador MMF inteiro: medido no rastro do EKA2L1, os cinco primeiros efeitos de
 *     uma partida sairam TODOS JUNTOS, muito depois do tiro que os pediu.
 *
 *     O TOCADOR E O GENERICO (CMdaAudioPlayerUtility), nao a API MIDI dedicada. Isto
 *     veio da engenharia reversa do Asphalt 4 Elite Racing HD (Gameloft, E71): ele
 *     embarca SMF de verdade e NAO importa midiclient.dll -- so mediaclientaudio.dll.
 *     Tecnicamente o que decide: o Stop() do CMdaAudioPlayerUtility e SINCRONO e o fim
 *     da reproducao chega por MapcPlayComplete. Com o CMidiClientUtility o Stop e
 *     assincrono e o fim so aparece como transicao de estado -- se o estado nao voltava,
 *     a voz ficava presa em EOpenPlaying e nunca mais tocava (o "tiro so uma vez").
 *
 *     TENTATIVA DESCARTADA (nao repetir): engatar um unico CMidiClientUtility em
 *     EClosedEngaged e mandar NoteOnL/NoteOffL ao vivo, como faz o Sequencer+Synthesizer
 *     do emulador de PC. O motor ENGATA (o log do EKA2L1 confirma, 5 sessoes seguidas),
 *     mas nenhuma nota vira som: o BAE so toca um recurso aberto. Ficou mudo.
 *
 * REGRA DE OURO (custou 4 defeitos no historico deste port): os utilitarios MMF sao
 * assincronos e rodam em outro processo. NUNCA chamar Play/Stop/Open de dentro de um
 * callback do proprio utilitario. Aqui MapcInitComplete/MapcPlayComplete SO ANOTAM
 * flags; quem emite Play/Stop e um CPeriodic, sempre em contexto normal.
 *
 *  2) PCM (vBeep e o modulo VSound) -> CMdaAudioOutputStream, 16 bits estereo a
 *     VSound::KOutRate. A mistura e feita pelo VSound portavel; aqui so escoamos.
 *
 * Tudo e tolerante a falha: se o aparelho recusar o audio, o jogo continua rodando mudo
 * em vez de cair.
 */
#ifndef WOLPHUNAUDIO_H
#define WOLPHUNAUDIO_H

#include <e32base.h>
#include <f32file.h>
#include <mdaaudiooutputstream.h>
#include <mdaaudiosampleplayer.h>
#include <mda/common/audio.h>

#include "vmcommon.h"
#include "Sdk.h"
#include "VSound.h"

/* Um som carregado: 1 CMdaAudioPlayerUtility, um arquivo, aberto uma vez. */
class CMidiVoice : public CBase, public MMdaAudioPlayerCallback
{
public:
    static CMidiVoice* NewL(const TDesC& aFile);
    ~CMidiVoice();

    /** Pede para tocar. Pode ser chamado de qualquer contexto: nao mexe no tocador. */
    void Request(TBool aLoop){ iWant = ETrue; iLoop = aLoop; iStopWanted = EFalse; }
    /** Pede para parar. Idem. */
    void RequestStop(){ iWant = EFalse; iStopWanted = ETrue; }

    /** SO o temporizador chama: aqui, e so aqui, Play/Stop sao emitidos.
        Devolve ETrue se emitiu um Play (para o contador de diagnostico). */
    TBool Pump();

    TBool Ready()   const { return iReady; }
    TBool Playing() const { return iPlaying; }
    TInt  Error()   const { return iErr; }

private:
    CMidiVoice();
    void ConstructL(const TDesC& aFile);

    /* MMdaAudioPlayerCallback -- NENHUM destes pode tocar no iPlayer. */
    void MapcInitComplete(TInt aError, const TTimeIntervalMicroSeconds& aDuration);
    void MapcPlayComplete(TInt aError);

    CMdaAudioPlayerUtility* iPlayer;
    TBool iReady;        /* a abertura terminou */
    TBool iPlaying;      /* Play emitido e ainda nao chegou MapcPlayComplete */
    TBool iWant;         /* ha um pedido de tocar pendente */
    TBool iLoop;         /* o pedido pendente e em laco (flag 0x100 do vPlayResource) */
    TBool iStopWanted;   /* ha um pedido de parar pendente */
    TInt  iSince;        /* tiques desde o ultimo Play (anti-repique) */
    TInt  iErr;
};

class CWolphunAudio : public CBase,
                      public SdkAudio,
                      public MMdaAudioOutputStreamCallback
{
public:
    static CWolphunAudio* NewL(RFs& aFs);
    ~CWolphunAudio();

    void SetMixer(VSound* aMixer);          /* trocado a cada jogo carregado */
    void SetCacheDir(const TDesC& aPath);   /* onde gravar os SMF extraidos */
    void SetEnabled(TBool aOn);
    void CloseAll();                        /* chamado ao fechar a ROM */

    /** Varre a imagem da ROM atras de SMF ("MThd") e abre uma voz para cada um.
        Chamado na CARGA do jogo -- e aqui que o custo do MMF tem de ser pago. */
    void Preload(const u8* aImage, TInt aLen);

    /* ---- SdkAudio ---- */
    void Start();
    void Stop();
    int  PlayMusic(const u8* aData, int aLen, bool aLoop);
    void StopMusic();

    /* diagnostico (Opcoes > Sobre) */
    TInt StreamError() const { return iStreamErr; }
    TInt MusicError()  const { return iMusicErr; }
    TInt MusicCount()  const { return iMusicCount; }
    TInt VoiceCount()  const { return iVoices; }
    /** Vozes que ja receberam MapcInitComplete, ou seja, que o Pump aceita tocar.
        Abrir (VoiceCount) e sincrono; ficar PRONTA depende do active scheduler rodar. */
    TInt ReadyCount() const
    {
        TInt n = 0;
        for (TInt i = 0; i < KMaxVoices; i++)
            if (iSlot[i].iVoice && iSlot[i].iVoice->Ready()) n++;
        return n;
    }
    TInt PlayCount()   const { return iPlays; }

private:
    CWolphunAudio(RFs& aFs);
    void ConstructL();

    TInt  SlotFor(const u8* aData, TInt aLen);     /* indice, ou KErrNotFound */
    TInt  AddVoice(const u8* aData, TInt aLen);    /* grava + abre; indice ou KErrNotFound */
    void  MidiPath(TInt aIndex, TDes& aOut) const;
    void  StartPump();
    static TInt PumpCb(TAny* aSelf);
    void  PumpAll();

    void FillAndWrite(TInt aIndex);
    void MaoscOpenComplete(TInt aError);
    void MaoscBufferCopied(TInt aError, const TDesC8& aBuffer);
    void MaoscPlayComplete(TInt aError);

    enum { KFramesPerBuf = 1024 };
    enum { KBufBytes = KFramesPerBuf * 4 };
    /* O Honey Cave 2 tem 8 efeitos. Passando disso o som novo e recusado em vez de
       derrubar uma voz aberta -- derrubar significaria fechar um controlador MMF no
       meio do jogo, que e exatamente o que nunca pode acontecer. */
    enum { KMaxVoices = 8 };
    enum { KPumpUs = 20000 };          /* 20 ms: so olha 8 ponteiros */

    struct TSlot { CMidiVoice* iVoice; TUint32 iKey; TInt iLen; };

    RFs&    iFs;
    VSound* iMixer;
    TBool   iEnabled;

    CMdaAudioOutputStream* iStream;
    TBuf8<KBufBytes>       iBuf[2];
    TBool                  iStreamOpen;
    TBool                  iStreamOpening;
    TInt                   iStreamErr;

    TFileName  iCacheDir;
    TSlot      iSlot[KMaxVoices];
    TInt       iVoices;
    CPeriodic* iPump;
    TInt       iMusicErr;
    TInt       iMusicCount;
    TInt       iPlays;
};

#endif /* WOLPHUNAUDIO_H */
