/* WolphunVibra.cpp - ver WolphunVibra.h */
#include "WolphunVibra.h"
#include <hal.h>

#ifdef __SYMBIAN_9__
#include <hwrmvibra.h>
#else
#include <vibractrl.h>
#endif

/* Teto de seguranca: um jogo com bug pediria vibracao eterna. 2 s e bem acima de qualquer
   uso real -- medido no acervo, os pulsos sao de 100, 300 e 1000 ms. */
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
        /* para o motor antes de soltar o cliente: sem isto um pulso longo continuaria
           depois de fechar o jogo. */
        TRAP_IGNORE(iVibra->StopVibraL());
        delete iVibra;
    }
}

void CWolphunVibra::Vibrate(int aDurationMs)
{
    if (!iVibra || aDurationMs <= 0) return;
    TInt ms = aDurationMs;
    if (ms > KMaxMs) ms = KMaxMs;

    /* User::TickCount() conta em ticks do sistema, nao em ms: no S60 o periodo tipico e
       1/64 s. Convertido aqui uma vez, com HAL, em vez de supor 15 ms por tick. */
    TInt nanoPorTick = 15625;                     /* reserva: 64 Hz */
    HAL::Get(HALData::ESystemTickPeriod, nanoPorTick);
    if (nanoPorTick <= 0) nanoPorTick = 15625;
    const TUint agora = User::TickCount();
    if (ms == iMsDoPulso && agora < iFimDoPulso) return;   /* mesmo pulso ainda rolando */

    /* O 9.x aceita a duracao em TInt; o 8.1 so em TUint16, e KMaxMs cabe nos dois. */
#ifdef __SYMBIAN_9__
    TRAPD(err, iVibra->StartVibraL(ms));
#else
    TRAPD(err, iVibra->StartVibraL((TUint16)ms));
#endif
    if (err != KErrNone)
    {
        /* O caso comum NAO e defeito: o usuario desligou a vibracao no perfil do aparelho,
           e o servico recusa (KErrAccessDenied / EVibraStatusNotAllowed). Essa escolha e
           dele e vale mais que a do jogo -- so nao insistimos. */
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
