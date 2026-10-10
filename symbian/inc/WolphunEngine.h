/* WolphunEngine.h - the bridge between the portable VM core and Symbian.
 *
 * It owns Vmgp + Pip2 + Screen + Sdk (plus Renderer3D when the game is 3D) and
 * implements the interfaces the core asks for: ScreenHost (time, presentation,
 * dialogs) and SdkFileSystem (saves and data files, through RFs). Sound lives in
 * CWolphunAudio.
 *
 * The VM runs in SLICES driven by a CPeriodic: each tick executes instructions until
 * the game calls vFlipScreen (a frame is ready) or until the budget runs out. That is
 * what keeps the Symbian interface responsive -- a tight loop would lock the phone up.
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

/* the shell tells the View when a new frame is ready */
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

    /* ---- the game's life cycle ---- */
    void LoadRomL(const TDesC& aFullPath);
    void CloseRom();
    void RestartL();                      /* reloads the current ROM, e.g. after a resolution change */
    TBool HasGame() const { return iVm != 0; }
    const TDesC& RomName() const { return iRomName; }

    /* ---- configuration ---- */
    TWolphunConfig& Config() { return iConfig; }
    /* the app's private directory: saves, per-game config and the folder list */
    const TDesC& PrivatePath() const { return iStore->PrivatePath(); }
    void SaveConfigL();
    void ApplyLiveSettings();             /* sound and filter take effect without a restart */

    /* ---- execution ---- */
    void StartRunning();
    void StopRunning();
    TBool IsRunning() const { return iTimer && iTimer->IsActive(); }

    /* ---- output ---- */
    CFbsBitmap* Bitmap() const { return iBitmap; }
    TSize GameSize() const;
    /* Diagnostics shown on screen while the game has not produced a frame yet: whether
       the VM is making progress, how many instructions it has run, and whether it has
       stopped. Without this, a game that draws nothing is indistinguishable from a
       broken emulator. */
    void DiagnosticsL(TDes& aOut) const;
    TInt FrameCount() const { return iFrames; }

    /* ---- input ---- */
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

    /* ---- sound ----
       The audio output lives in CWolphunAudio: CMdaAudioOutputStream for VSound's PCM,
       plus CMdaAudioPlayerUtility for the MIDI resources of vPlayResource. */
    void AudioDiagnostics(TDes& aOut) const;

private:
    CWolphunEngine(RFs& aFs, MWolphunObserver& aObserver);
    void ConstructL();
    void DestroyGame();
    void BuildGameL(const TDesC& aFullPath);
    void AllocBitmapL(const TSize& aSize);
    void CopyFrameToBitmap();
    /* turns one of the game's logical file names (char*) into a path in the private dir */
    void SavePathL(const char* aName, TDes& aOut);

    static TInt TimerTick(TAny* aSelf);
    void RunSlice();

    RFs&               iFs;
    MWolphunObserver&  iObserver;
    CPeriodic*         iTimer;
    CWolphunConfigStore* iStore;

    /* the portable core */
    Vmgp*       iExe;
    Pip2*       iVm;
    Screen*     iScreen;
    Sdk*        iSdk;
    Renderer3D* iR3d;
    CWolphunAudio* iAudio;      /* sound output; lives as long as the engine does */
    /* Vibration. It exists only when the setting is on AND the phone has a motor; 0 is
       the normal state in either other case. ApplyLiveSettings creates and destroys it,
       so the setting takes effect immediately, without restarting the game. */
    CWolphunVibra* iVibra;

    CFbsBitmap* iBitmap;
    TBool       iFlipPending;
    TInt        iFrames;      /* frames the game has produced (diagnostic) */
    TUint       iLastFrameTick;  /* NTickCount of the last frame, for the frame cap */
    TInt        iFramePeriodMs;  /* 0 = no cap; derived from iConfig.iPowerSave */
    TInt        iSliceBudgetMs;  /* ms of VM per 20 ms tick = the CPU duty cycle */

    TWolphunConfig iConfig;
    TFileName      iRomPath;
    TFileName      iRomName;

    TTime iStartTime;
};

#endif /* WOLPHUNENGINE_H */
