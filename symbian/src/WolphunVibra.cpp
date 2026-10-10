/* WolphunVibra.cpp - see WolphunVibra.h */
#include "WolphunVibra.h"
#include <hal.h>

#ifdef __SYMBIAN_9__
#include <hwrmvibra.h>
#else
#include <vibractrl.h>
#endif

/* Safety ceiling: a buggy game would ask for endless vibration. Two seconds is well
   above any real use -- measured across the archive, the pulses are 100, 300 and
   1000 ms. */
static const TInt KMaxMs = 2000;

CWolphunVibra::CWolphunVibra() : iVibra(0), iFimDoPulso(0), iMsDoPulso(-1) {}

CWolphunVibra* CWolphunVibra::New()
{
    CWolphunVibra* self = new CWolphunVibra;
    if (!self) return 0;
    TRAPD(err, self->ConstructL());
    if (err != KErrNone){ delete self; return 0; }
    return self;
}

void CWolphunVibra::ConstructL()
{
#ifdef __SYMBIAN_9__
    iVibra = CHWRMVibra::NewL();
#else
    iVibra = CVibraControl::NewL();
#endif
}

CWolphunVibra::~CWolphunVibra()
{
    if (iVibra)
    {
        /* stop the motor before releasing the client: without this a long pulse would
           carry on after the game is closed. */
        TRAP_IGNORE(iVibra->StopVibraL());
        delete iVibra;
    }
}

void CWolphunVibra::Vibrate(int aDurationMs)
{
    if (!iVibra || aDurationMs <= 0) return;
    TInt ms = aDurationMs;
    if (ms > KMaxMs) ms = KMaxMs;

    /* User::TickCount() counts system ticks, not milliseconds: on S60 the usual period
       is 1/64 s. Converted here once, through HAL, rather than assuming 15 ms a tick. */
    TInt nanoPorTick = 15625;                     /* fallback: 64 Hz */
    HAL::Get(HALData::ESystemTickPeriod, nanoPorTick);
    if (nanoPorTick <= 0) nanoPorTick = 15625;
    const TUint agora = User::TickCount();
    if (ms == iMsDoPulso && agora < iFimDoPulso) return;   /* the same pulse is still running */

    /* 9.x takes the duration as a TInt; 8.1 only as a TUint16, and KMaxMs fits both. */
#ifdef __SYMBIAN_9__
    TRAPD(err, iVibra->StartVibraL(ms));
#else
    TRAPD(err, iVibra->StartVibraL((TUint16)ms));
#endif
    if (err != KErrNone)
    {
        /* The common case is NOT a defect: the user switched vibration off in the
           phone's profile and the service refuses (KErrAccessDenied /
           EVibraStatusNotAllowed). That choice is theirs and outranks the game's, so we
           simply do not insist. */
        iMsDoPulso = -1;
        return;
    }
    iMsDoPulso  = ms;
    iFimDoPulso = agora + (TUint)((ms * 1000 + nanoPorTick / 2) / nanoPorTick);
}

void CWolphunVibra::StopVibra()
{
    if (!iVibra) return;
    iMsDoPulso = -1;
    iFimDoPulso = 0;
    TRAP_IGNORE(iVibra->StopVibraL());
}
