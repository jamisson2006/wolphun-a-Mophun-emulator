/* WolphunVibra.h - device vibration for Mophun's vSysCtl(SYSCTL_SETVIBRATE).
 *
 * Implements SdkVibra from the portable core (inc/Sdk.h) with the SAME logic as the
 * Android version (Vibra.java): a duration ceiling, debouncing, and tolerance for a
 * phone with no motor.
 *
 * Two Symbian APIs, picked by the same __SYMBIAN_9__ that already separates the targets:
 *   9.x   CHWRMVibra  (hwrmvibraclient.lib) -- the recommended API from 3rd Edition on.
 *   8.1   CVibraControl (vibractrl.lib)     -- the only one that exists on 2nd Edition.
 *         It does still exist on 9.x, but has been DEPRECATED since 3.0, and a binary
 *         linked against a DLL that some newer phone might not ship does not even LOAD
 *         -- which is why we do not use it there.
 *
 * Neither requires a capability: the headers in both SDKs carry no @capability
 * annotation. That matters because the package is SELF-SIGNED, and a capability outside
 * the freely grantable set would block installation.
 */
#ifndef WOLPHUNVIBRA_H
#define WOLPHUNVIBRA_H

#include <e32base.h>
#include "Sdk.h"

#ifdef __SYMBIAN_9__
class CHWRMVibra;
#else
class CVibraControl;
#endif

class CWolphunVibra : public CBase, public SdkVibra
{
public:
    /** 0 if the phone has no vibration motor or the service refused to open. That is
        not an error: tablets, emulators and plenty of phones simply do not vibrate. */
    static CWolphunVibra* New();
    ~CWolphunVibra();

    /* SdkVibra */
    void Vibrate(int aDurationMs);
    void StopVibra();

private:
    CWolphunVibra();
    void ConstructL();

#ifdef __SYMBIAN_9__
    CHWRMVibra*    iVibra;
#else
    CVibraControl* iVibra;
#endif

    /* DEBOUNCE -- from the same measurement as the Android version: across a sample of
       38 games that import vSysCtl there were 492 SETVIBRATE calls in 5 games, several
       of them asking for vibration ON EVERY FRAME for as long as the state lasts (Honey
       Cave 2 is the well-behaved exception: 8 discrete requests). Passing that through
       raw restarts the motor dozens of times per second: it stutters instead of
       vibrating and drains the battery -- which on an E63 with an aged battery is
       exactly what makes the phone switch itself off.
       So while a pulse OF THE SAME DURATION is still running, a new request is ignored.
       This is not censoring the game: once the pulse ends the next request goes through,
       so a caller that asks every frame still gets CONTINUOUS vibration, which is the
       intended effect. A different duration passes immediately, because at that point
       the game has changed its mind. */
    TUint iFimDoPulso;      /* User::TickCount() at which the current pulse ends */
    TInt  iMsDoPulso;       /* -1 = no pulse */
};

#endif
