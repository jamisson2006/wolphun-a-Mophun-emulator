package com.manedsoftware.wolphun;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import wolphun.vm.Sdk;

/**
 * Vibracao do aparelho para o vSysCtl(SYSCTL_SETVIBRATE) do mophun.
 *
 * O macro do SDK e vSetVibrate(on,time) = vSysCtl(1, on | (time&lt;&lt;16)); quem decide a duracao
 * e o JOGO (o Honey Cave 2 pede 300 ms ao tomar dano). Aqui so repassamos.
 *
 * Dois caminhos de API porque o minSdk e 24:
 *   - API 26+: VibrationEffect.createOneShot;
 *   - API 24/25: o vibrate(long) antigo, deprecated mas e o unico que existe la.
 * Usamos VIBRATOR_SERVICE e nao o VibratorManager da API 31: o projeto compila contra a
 * compileSdk 29, onde a classe nova nem existe -- e o servico antigo continua DEPRECIADO,
 * nao removido, entao funciona tambem nos aparelhos novos.
 *
 * Precisa de android.permission.VIBRATE no manifesto -- e permissao "normal", concedida na
 * instalacao, sem dialogo em runtime.
 */
final class Vibra implements Sdk.Vibrador {

    private final Vibrator vib;
    private final boolean ligado;
    /** Teto de seguranca: um jogo com bug pediria vibracao eterna; 2 s e bem acima de qualquer uso real.
        Medido no acervo: os pulsos reais sao 100, 300 e 1000 ms. */
    private static final int MAX_MS = 2000;

    /* ANTI-REPIQUE. Medido numa amostra de 38 jogos que importam vSysCtl: 492 chamadas de
       SYSCTL_SETVIBRATE em 5 jogos -- varios pedem vibracao A CADA QUADRO enquanto o estado dura
       (o Honey Cave 2 e a excecao bem-comportada: 8 pedidos pontuais). Repassar isso cru reinicia
       o vibrate() dezenas de vezes por segundo: o motor gagueja em vez de vibrar e a bateria vai
       embora. Entao, enquanto um pulso DA MESMA DURACAO ainda esta no ar, o pedido novo e ignorado.
       Nao e censura ao jogo: quando o pulso acaba, o proximo pedido passa, de modo que quem chama
       todo quadro continua tendo vibracao CONTINUA -- que e o efeito pretendido. Uma duracao
       diferente passa na hora, porque ai o jogo mudou de intencao. */
    private long fimDoPulso = 0;     // SystemClock.uptimeMillis() em que o pulso atual termina
    private int  msDoPulso  = -1;

    Vibra(Context ctx, boolean ligado) {
        this.ligado = ligado;
        Vibrator v;
        try { v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE); }
        catch (Throwable t) { v = null; }
        this.vib = v;
    }

    /** true se da para vibrar de verdade: tablets e emuladores costumam nao ter motor. */
    boolean disponivel() {
        try { return vib != null && vib.hasVibrator(); }
        catch (Throwable t) { return false; }
    }

    @Override public void vibra(int ms) {
        if (!ligado || vib == null || ms <= 0) return;
        if (ms > MAX_MS) ms = MAX_MS;
        final long agora = SystemClock.uptimeMillis();
        if (ms == msDoPulso && agora < fimDoPulso) return;   // mesmo pulso ainda rolando
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vib.vibrate(ms);
            }
            msDoPulso = ms;
            fimDoPulso = agora + ms;
        } catch (Throwable t) { /* sem motor ou permissao negada: nao derruba o jogo */ }
    }

    @Override public void para() {
        if (vib == null) return;
        msDoPulso = -1; fimDoPulso = 0;
        try { vib.cancel(); } catch (Throwable t) { }
    }
}
