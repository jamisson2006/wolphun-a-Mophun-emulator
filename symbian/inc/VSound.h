/* VSound.h - Mophun's sound module (vSoundInit/GetHandle/Ctrl/Dispose + vBeep).
 *
 * Ported from src/wolphun/vm/VSound.java. Same design: a SOFTWARE MIXER of our own, not
 * a "one sound at a time" scheme. Each voice is resampled at its current frequency,
 * which is what allows real-time PITCH changes (a car engine), LOOPING, and several
 * sounds at once.
 *
 * Differences from the Java version that were not optional:
 *   - no HashMap: fixed-size arrays searched by handle;
 *   - no thread: the platform pulls the samples (on Symbian, the
 *     CMdaAudioOutputStream callback calls RenderFrames);
 *   - no double: the read position is 16.16 fixed point in an i64. Many S60 3rd devices
 *     have no floating-point unit, and this runs once per sample.
 *
 * Every format is decoded to mono PCM16 at load time:
 *   - format 2 = IMA ADPCM (high nibble first, Mophun's variant)
 *   - 8-bit    = unsigned PCM (silence = 128) -> ((v-128) << 8)
 *   - 16-bit   = signed PCM, little-endian
 * NativeSoundHeader layout (SHDR, 36 bytes LE): format@8, loopStart@12, loopEnd@16 (in
 * SAMPLES), freq@20, bits@24, channels@25, body@36 with its size at @32.
 */
#ifndef VSOUND_H
#define VSOUND_H

#include "vmcommon.h"

class Pip2;

class VSound
{
public:
    enum { KOutRate = 22050 };      /* mixer rate, the same as the PC emulator's */
    enum { KMaxSounds = 48, KMaxVoices = 24 };

    VSound(Pip2* aVm);
    ~VSound();

    bool enabled;

    /* Diagnostic counters: they say whether the game REALLY asked for sound, and how
       much. Without them, "no sound comes out" is indistinguishable from "the game
       plays nothing". */
    long sndLoaded;    /* successful vSoundGetHandle calls */
    long playCount;    /* vSoundCtrl(PLAY) */
    long beepCount;    /* vBeep */

    /* ---- the API the Sdk calls ---- */
    int  init();                                  /* 0 = ok, -1 = sound disabled */
    int  getHandle(int aDataPtr);                 /* decodes to PCM16; <0 = error */
    int  ctrl(int aHandle, int aControl, int aParam);
    int  dispose();
    int  disposeHandle(int aHandle);
    void beep(int aFreq, int aDurMs);             /* vBeep: a square wave through the mixer */

    /* ---- called by the platform (the audio stream callback) ----
       Writes aFrames interleaved STEREO frames (L,R) as PCM16. It always fills the whole
       buffer: silence is zero. */
    void RenderFrames(i16* aDst, int aFrames);

    /* is any voice playing? the shell uses this to avoid holding the audio open for nothing */
    bool busy() const;

private:
    struct Snd {
        i16* pcm;
        int  len;          /* samples */
        int  freq;
        int  loopStart, loopEnd;
        int  handle;       /* 0 = free */
    };
    struct Voice {
        Snd* snd;
        i64  pos;          /* 16.16 fixed point, in samples */
        i64  step;         /* 16.16 fixed point: advance per output frame */
        int  loop;         /* -1 = forever, 0 = finished, >0 = repeats left */
        int  vol;          /* 0..127 */
        int  pan;          /* -127..127 */
        bool playing;
        int  handle;       /* 0 = free */
    };

    Snd*   findSound(int aHandle);
    Voice* findVoice(int aHandle);
    Voice* takeVoice(int aHandle);     /* the existing one, or a free slot */
    void   freeSound(Snd* aSnd);
    i16*   decodeImaAdpcm(int aBodyOff, int aBodySize, int* aOutLen);

    Pip2* vm;
    Snd   sounds[KMaxSounds];
    Voice voices[KMaxVoices];
    int   nextHandle;
    int   masterVol;        /* 0..127 */
    bool  started;

    /* vBeep: ONE reserved handle. Games like Mooktown Tennis call vBeep hundreds of
       times per second; allocating a voice per call would blow the mixer apart. */
    enum { KBeepHandle = 0x7000 };
    Snd   beepSnd;
    int   beepFreq, beepDur;   /* what beepSnd currently holds, to avoid re-synthesising */
};

#endif /* VSOUND_H */
