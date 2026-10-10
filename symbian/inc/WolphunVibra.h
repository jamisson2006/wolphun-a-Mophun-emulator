/* WolphunVibra.h - vibracao do aparelho para o vSysCtl(SYSCTL_SETVIBRATE) do mophun.
 *
 * Implementa o SdkVibra do nucleo portavel (inc/Sdk.h) com a MESMA logica da versao
 * Android (Vibra.java): teto de duracao, anti-repique e tolerancia a aparelho sem motor.
 *
 * Duas APIs do Symbian, escolhidas pelo mesmo __SYMBIAN_9__ que ja separa os alvos:
 *   9.x   CHWRMVibra  (hwrmvibraclient.lib) -- a API recomendada do 3rd Edition em diante.
 *   8.1   CVibraControl (vibractrl.lib)     -- a unica que existe no 2nd Edition. No 9.x
 *         ela ate existe, mas esta DEPRECIADA desde o 3.0, e um binario ligado a uma DLL
 *         que algum aparelho novo nao traga nem CARREGA -- por isso nao a usamos la.
 *
 * Nenhuma das duas exige capability: os headers dos dois SDKs nao tem anotacao @capability.
 * Isso importa porque o pacote e AUTO-ASSINADO, e uma capability fora do conjunto livre
 * impediria a instalacao.
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
    /** 0 se o aparelho nao tem motor de vibracao ou o servico recusou abrir. Nao e erro:
        tablet, emulador e varios aparelhos simplesmente nao vibram. */
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

    /* ANTI-REPIQUE -- a mesma medicao da versao Android: numa amostra de 38 jogos que
       importam vSysCtl houve 492 chamadas de SETVIBRATE em 5 jogos, varios pedindo
       vibracao A CADA QUADRO enquanto o estado dura (o Honey Cave 2 e a excecao
       bem-comportada: 8 pedidos pontuais). Repassar isso cru reinicia o motor dezenas de
       vezes por segundo: ele gagueja em vez de vibrar e a bateria vai embora -- o que num
       E63 de bateria velha e exatamente o que faz o aparelho desligar sozinho.
       Entao, enquanto um pulso DA MESMA DURACAO ainda esta no ar, o pedido novo e
       ignorado. Nao e censura ao jogo: quando o pulso acaba o proximo pedido passa, de
       modo que quem chama todo quadro continua tendo vibracao CONTINUA, que e o efeito
       pretendido. Uma duracao diferente passa na hora, porque ai o jogo mudou de intencao. */
    TUint iFimDoPulso;      /* User::TickCount() em que o pulso atual termina */
    TInt  iMsDoPulso;       /* -1 = nenhum pulso */
};

#endif
