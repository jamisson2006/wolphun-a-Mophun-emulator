/* WolphunAudio.h - sound output on Symbian.
 *
 *  1) MUSIC / SFX (vPlayResource) -> ONE PLAYER PER SOUND, opened ONCE and never
 *     closed. This is the J2ME model: the game creates its Players at startup and from
 *     then on only calls start(). The sound comes out of THE PHONE'S OWN synthesizer
 *     and General MIDI bank -- nothing is synthesised by us.
 *
 *     THE VOICES ARE OPENED WHEN THE ROM LOADS, not on the first shot. We scan the
 *     game's image for "MThd" blocks and open them all at once while the loading screen
 *     is still up. This exists because opening a MIDI resource spins up an entire MMF
 *     controller PROCESS: measured in an EKA2L1 trace, the first five effects of a
 *     session came out ALL AT ONCE, long after the shot that asked for them.
 *
 *     THE PLAYER IS THE GENERIC ONE (CMdaAudioPlayerUtility), not the dedicated MIDI
 *     API. That came from reverse engineering Asphalt 4 Elite Racing HD (Gameloft,
 *     E71): it embeds real SMF files and does NOT import midiclient.dll, only
 *     mediaclientaudio.dll. The technical reason it wins: CMdaAudioPlayerUtility's
 *     Stop() is SYNCHRONOUS and the end of playback arrives through MapcPlayComplete.
 *     With CMidiClientUtility, Stop is asynchronous and the end only shows up as a
 *     state transition -- when the state did not come back, the voice stayed stuck in
 *     EOpenPlaying and never played again (the "fires only once" symptom).
 *
 *     A DISCARDED ATTEMPT (do not repeat): engaging a single CMidiClientUtility in
 *     EClosedEngaged and sending NoteOnL/NoteOffL live, the way the PC emulator's
 *     Sequencer + Synthesizer do. The engine DOES engage -- the EKA2L1 log confirmed it
 *     across 5 consecutive sessions -- but no note becomes sound: the BAE only plays an
 *     opened resource. It stayed silent.
 *
 * THE GOLDEN RULE (it cost 4 defects in this port's history): the MMF utilities are
 * asynchronous and run in another process. NEVER call Play/Stop/Open from inside one of
 * that utility's own callbacks. Here MapcInitComplete/MapcPlayComplete ONLY SET FLAGS;
 * a CPeriodic issues Play/Stop, always from normal context.
 *
 *  2) PCM (vBeep and the VSound module) -> CMdaAudioOutputStream, 16-bit stereo at
 *     VSound::KOutRate. The mixing is done by the portable VSound; here we only drain it.
 *
 * All of it is failure-tolerant: if the phone refuses audio, the game keeps running
 * silently instead of dying.
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

/* One loaded sound: a single CMdaAudioPlayerUtility, one file, opened once. */
class CMidiVoice : public CBase, public MMdaAudioPlayerCallback
{
public:
    static CMidiVoice* NewL(const TDesC& aFile);
    ~CMidiVoice();

    /** Requests playback. Safe from any context: it does not touch the player. */
    void Request(TBool aLoop){ iWant = ETrue; iLoop = aLoop; iStopWanted = EFalse; }
    /** Requests a stop. Likewise. */
    void RequestStop(){ iWant = EFalse; iStopWanted = ETrue; }

    /** ONLY the timer calls this: here, and only here, Play/Stop are issued.
        Returns ETrue if it issued a Play, for the diagnostic counter. */
    TBool Pump();

    TBool Ready()   const { return iReady; }
    TBool Playing() const { return iPlaying; }
    TInt  Error()   const { return iErr; }

private:
    CMidiVoice();
    void ConstructL(const TDesC& aFile);

    /* MMdaAudioPlayerCallback -- NONE of these may touch iPlayer. */
    void MapcInitComplete(TInt aError, const TTimeIntervalMicroSeconds& aDuration);
    void MapcPlayComplete(TInt aError);

    CMdaAudioPlayerUtility* iPlayer;
    TBool iReady;        /* opening has finished */
    TBool iPlaying;      /* Play was issued and MapcPlayComplete has not arrived yet */
    TBool iWant;         /* there is a pending request to play */
    TBool iLoop;         /* the pending request loops (vPlayResource's 0x100 flag) */
    TBool iStopWanted;   /* there is a pending request to stop */
    TInt  iSince;        /* ticks since the last Play, for debouncing */
    TInt  iErr;
};

class CWolphunAudio : public CBase,
                      public SdkAudio,
                      public MMdaAudioOutputStreamCallback
{
public:
    static CWolphunAudio* NewL(RFs& aFs);
    ~CWolphunAudio();

    void SetMixer(VSound* aMixer);          /* swapped for every game loaded */
    void SetCacheDir(const TDesC& aPath);   /* where the extracted SMF files are written */
    void SetEnabled(TBool aOn);
    void CloseAll();                        /* called when the ROM is closed */

    /** Scans the ROM image for SMF blocks ("MThd") and opens one voice per block.
        Called when the game LOADS -- this is where the MMF cost has to be paid. */
    void Preload(const u8* aImage, TInt aLen);

    /* ---- SdkAudio ---- */
    void Start();
    void Stop();
    int  PlayMusic(const u8* aData, int aLen, bool aLoop);
    void StopMusic();

    /* diagnostics (Options > About) */
    TInt StreamError() const { return iStreamErr; }
    TInt MusicError()  const { return iMusicErr; }
    TInt MusicCount()  const { return iMusicCount; }
    TInt VoiceCount()  const { return iVoices; }
    /** Voices that have already received MapcInitComplete, i.e. the ones Pump will
        play. Opening (VoiceCount) is synchronous; becoming READY depends on the active
        scheduler getting a turn. */
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

    TInt  SlotFor(const u8* aData, TInt aLen);     /* the index, or KErrNotFound */
    TInt  AddVoice(const u8* aData, TInt aLen);    /* writes then opens; index or KErrNotFound */
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
    /* Honey Cave 2 has 8 effects. Beyond that a new sound is refused rather than
       evicting an open voice -- evicting would mean closing an MMF controller in the
       middle of the game, which is exactly what must never happen. */
    enum { KMaxVoices = 8 };
    enum { KPumpUs = 20000 };          /* 20 ms: it only looks at 8 pointers */

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
