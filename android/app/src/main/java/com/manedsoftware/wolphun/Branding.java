package com.manedsoftware.wolphun;

/**
 * Identidade do aplicativo. Espelha mophun.Branding da versao desktop para que os dois
 * "Sobre" digam exatamente a mesma coisa.
 *
 * A VERSAO nao mora aqui: vem do BuildConfig.VERSION_NAME, que sai do build.gradle. Ja
 * houve um "0.1.0" chumbado no codigo que ficou para tras quando o app foi para 0.2.0 e
 * a tela "Sobre" passou a mentir a versao -- uma fonte so, e essa fonte e o gradle.
 */
public final class Branding {
    private Branding(){}

    public static final String APP_NAME = "wolphun emulator";
    public static final String VENDOR   = "Maned Software";  // "estudio" (mascote: lobo-guara/maned wolf)
    public static final String AUTHOR   = "Jamisson Tavares";
    public static final String YEAR     = "2026";
    public static final String URL      = "youtube.com/@jamissontavares7671";
    /** Apoio ao projeto. O QR (res/drawable-nodpi/kofi_qr.png) aponta para esta mesma URL:
        quem estiver com outro celular escaneia, quem estiver neste toca no link. */
    public static final String KOFI     = "https://ko-fi.com/jamissontavares";
}
