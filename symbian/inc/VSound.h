/* VSound.h - modulo de som do mophun (vSoundInit/GetHandle/Ctrl/Dispose + vBeep).
 *
 * Portado de src/wolphun/vm/VSound.java. Mesmo desenho: um MIXER DE SOFTWARE proprio,
 * nao um "som por vez". Cada voz e reamostrada pela sua frequencia atual, o que permite
 * PITCH em tempo real (motor de carro), LOOP e varios sons ao mesmo tempo.
 *
 * Diferencas obrigatorias do Java:
 *   - sem HashMap: vetores de tamanho fixo com busca por handle;
 *   - sem thread: quem puxa as amostras e a plataforma (no Symbian, o callback do
 *     CMdaAudioOutputStream chama RenderFrames);
 *   - sem double: a posicao de leitura e ponto fixo 16.16 em i64 (muitos aparelhos
 *     S60 3rd nao tem unidade de ponto flutuante; isto aqui roda a cada amostra).
 *
 * Todos os formatos viram PCM16 mono na carga:
 *   - formato 2 = IMA ADPCM (nibble alto primeiro, variante mophun)
 *   - 8 bits    = PCM sem sinal (silencio = 128) -> ((v-128) << 8)
 *   - 16 bits   = PCM com sinal, little-endian
 * Layout NativeSoundHeader (SHDR, 36 B LE): formato@8, loopStart@12, loopEnd@16 (em
 * AMOSTRAS), freq@20, bits@24, canais@25, corpo@36 com tamanho em @32.
 */
#ifndef VSOUND_H
#define VSOUND_H

#include "vmcommon.h"

class Pip2;

class VSound
{
public:
    enum { KOutRate = 22050 };      /* taxa do mixer (igual a do emulador de PC) */
    enum { KMaxSounds = 48, KMaxVoices = 24 };

    VSound(Pip2* aVm);
    ~VSound();

    bool enabled;

    /* contadores de diagnostico: dizem se o jogo REALMENTE pediu som, e quanto.
       Sem isso, "nao sai som" fica indistinguivel de "o jogo nao toca nada". */
    long sndLoaded;    /* vSoundGetHandle bem sucedidos */
    long playCount;    /* vSoundCtrl(PLAY) */
    long beepCount;    /* vBeep */

    /* ---- API chamada pelo Sdk ---- */
    int  init();                                  /* 0 = ok, -1 = som desligado */
    int  getHandle(int aDataPtr);                 /* decodifica p/ PCM16; <0 = erro */
    int  ctrl(int aHandle, int aControl, int aParam);
    int  dispose();
    int  disposeHandle(int aHandle);
    void beep(int aFreq, int aDurMs);             /* vBeep: onda quadrada pelo mixer */

    /* ---- chamada pela plataforma (callback do stream de audio) ----
       Escreve aFrames quadros ESTEREO intercalados (L,R) em PCM16. Sempre preenche o
       buffer inteiro: silencio e zero. */
    void RenderFrames(i16* aDst, int aFrames);

    /* ha alguma voz tocando? a casca usa isto p/ nao segurar o audio aberto a toa */
    bool busy() const;

private:
    struct Snd {
        i16* pcm;
        int  len;          /* amostras */
        int  freq;
        int  loopStart, loopEnd;
        int  handle;       /* 0 = livre */
    };
    struct Voice {
        Snd* snd;
        i64  pos;          /* ponto fixo 16.16, em amostras */
        i64  step;         /* ponto fixo 16.16: avanco por quadro de saida */
        int  loop;         /* -1 = infinito, 0 = acabou, >0 = repeticoes restantes */
        int  vol;          /* 0..127 */
        int  pan;          /* -127..127 */
        bool playing;
        int  handle;       /* 0 = livre */
    };

    Snd*   findSound(int aHandle);
    Voice* findVoice(int aHandle);
    Voice* takeVoice(int aHandle);     /* existente ou uma livre */
    void   freeSound(Snd* aSnd);
    i16*   decodeImaAdpcm(int aBodyOff, int aBodySize, int* aOutLen);

    Pip2* vm;
    Snd   sounds[KMaxSounds];
    Voice voices[KMaxVoices];
    int   nextHandle;
    int   masterVol;        /* 0..127 */
    bool  started;

    /* vBeep: UM handle reservado. Jogos como o Mooktown Tennis chamam vBeep centenas de
       vezes por segundo; criar uma voz por chamada estouraria o mixer. */
    enum { KBeepHandle = 0x7000 };
    Snd   beepSnd;
    int   beepFreq, beepDur;   /* o que esta no beepSnd agora (evita re-sintetizar) */
};

#endif /* VSOUND_H */
