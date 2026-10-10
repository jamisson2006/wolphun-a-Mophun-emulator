package com.manedsoftware.wolphun;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import java.io.File;

/**
 * Configurações do emulador (persistidas em SharedPreferences) + tabelas de
 * modelo de aparelho (portadas do Config desktop). Equivalente Android do Config.
 */
public final class PrefsConfig {
    public int scale = 3;
    public boolean sound = true;
    public boolean reverb = true;    // false = mata o "eco"/reverb do MIDI
    public boolean vibrate = true;   // vSysCtl(SYSCTL_SETVIBRATE) chega ao motor do aparelho
    public String soundFont = null;  // caminho .sf2 p/ o synth de SoundFont (null = synth padrao Sonivox)
    public int deviceModel = 2;      // default SE T610 (128x160)
    public int width = 128, height = 160;
    public int targetFps = 60;
    public double gamma = 1.0;
    public double fontScale = 1.0;   // escala do texto in-game (1.0=nativo; >1.0 aumenta legibilidade)
    public int renderScale = 1;      // supersampling interno (HD): 1=nativo; 2..4 = rende o 3D em N x (nitido)
    // ---- filtro de textura 3D (VMGP3D_FILTERMODE, estado 13) ----
    // 0 = segue o jogo; 1 = forca bilinear (suaviza); 2 = forca nearest (nitido, como o hardware).
    public int texFilter = 0;
    public float screenTop = 0.66f;      // fração da altura (retrato) p/ a tela do jogo; resto = controles
    public String language = "en";       // idioma padrao da interface (Lang)
    // ---- aparencia (tema visual: cores + fonte) ----
    public String theme = "system";      // id: system/wolphun/dark/light/amber/crt/ocean/purple/custom
    public int uiBg = 0x232228;          // cores do modo "custom" (RGB)
    public int uiPanel = 0x2E2C34;
    public int uiFg = 0xF0EDE6;
    public int uiAccent = 0xE8451F;
    public String uiFont = "SansSerif";  // SansSerif | Serif | Monospaced
    public float uiFontScale = 1.0f;     // escala do texto da interface (0.8..1.6)

    // ---- tabelas de modelo (nofun SystemDeviceModel) ----
    public static final String[] MODEL_NAMES = {
        "SE T300 (101x80)","SE T310 (101x80)","SE T610 (128x160)","Nokia 7650 (176x208)",
        "SE P800 (208x320)","SE T226 (101x80)","Motorola A920 (208x320)","Nokia N-Gage (176x208)",
        "Nokia 3650 (176x208)","Tiger Gametrac (176x220)","SE P900 (208x320)","Nokia 6600 (176x208)",
        "Motorola A925 (208x320)","Siemens SX1 (176x220)","Archos AV500 (176x220)","Sendo X (176x220)"
    };
    static final int[] MODEL_VENDOR = {3,3,3,4, 3,3,5,4, 4,7,3,4, 5,8,0,10};
    static final int[][] MODEL_RES = {
        {101,80},{101,80},{128,160},{176,208},{208,320},{101,80},{208,320},{176,208},
        {176,208},{176,220},{208,320},{176,208},{208,320},{176,220},{176,220},{176,220}
    };
    public static int vendorForModel(int m){ return (m>=0&&m<MODEL_VENDOR.length)? MODEL_VENDOR[m] : 0; }
    public static int[] resForModel(int m){ return (m>=0&&m<MODEL_RES.length)? MODEL_RES[m] : new int[]{128,160}; }
    /** indice do 1o modelo cuja resolucao nativa e (w,h), ou -1. */
    public static int modelForRes(int w,int h){
        for(int i=0;i<MODEL_RES.length;i++) if(MODEL_RES[i][0]==w && MODEL_RES[i][1]==h) return i;
        return -1;
    }
    /** resolucao pela tag [LxA] no nome do arquivo (ex.: "..._[176x208]_..."), ou null. */
    public static int[] detectRes(String fileName){
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\[(\\d{2,4})x(\\d{2,4})\\]", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(fileName);
        if(m.find()){
            int w=Integer.parseInt(m.group(1)), h=Integer.parseInt(m.group(2));
            if(w>=48 && h>=48 && w<=1024 && h<=1024) return new int[]{w,h};
        }
        return null;
    }

    // detecta o modelo pelo NOME do arquivo (.mpn). -1 = multiscreen/desconhecido (usa default).
    public static int detectModel(String fileName){
        String n = fileName.toLowerCase();
        if(n.contains("[t300]")) return 0;
        if(n.contains("[t310]")) return 1;
        if(n.contains("[t610]")||n.contains("[t6x0]")||n.contains("[t630]")) return 2;
        if(n.contains("[t226]")||n.contains("[t230]")) return 5;
        if(n.contains("[p800]")) return 4;
        if(n.contains("[p900]")) return 10;
        if(n.contains("7650")) return 3;
        if(n.contains("ngage")||n.contains("n-gage")) return 7;
        if(n.contains("3650")) return 8;
        if(n.contains("6600")) return 11;
        return -1;
    }

    // ---- persistência ----
    private static final String PREFS = "wolphun_config";
    public static PrefsConfig load(Context ctx){
        PrefsConfig c = new PrefsConfig();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        c.scale = p.getInt("scale", c.scale);
        c.sound = p.getBoolean("sound", c.sound);
        c.reverb = p.getBoolean("reverb", c.reverb);
        c.vibrate = p.getBoolean("vibrate", c.vibrate);
        { String sf = p.getString("soundFont", ""); c.soundFont = (sf==null||sf.isEmpty())?null:sf; }
        c.deviceModel = p.getInt("deviceModel", c.deviceModel);
        c.width = p.getInt("width", c.width);
        c.height = p.getInt("height", c.height);
        c.targetFps = p.getInt("targetFps", c.targetFps);
        c.renderScale = p.getInt("renderScale", c.renderScale);
        c.texFilter = p.getInt("texFilter", c.texFilter);
        c.gamma = (double) p.getFloat("gamma", (float)c.gamma);
        c.fontScale = (double) p.getFloat("fontScale", (float)c.fontScale);
        c.screenTop = p.getFloat("screenTop", c.screenTop);
        c.language = p.getString("language", c.language);
        c.theme = p.getString("theme", c.theme);
        c.uiBg = p.getInt("uiBg", c.uiBg);
        c.uiPanel = p.getInt("uiPanel", c.uiPanel);
        c.uiFg = p.getInt("uiFg", c.uiFg);
        c.uiAccent = p.getInt("uiAccent", c.uiAccent);
        c.uiFont = p.getString("uiFont", c.uiFont);
        c.uiFontScale = p.getFloat("uiFontScale", c.uiFontScale);
        return c;
    }
    public void save(Context ctx){
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt("scale", scale).putBoolean("sound", sound).putBoolean("reverb", reverb).putBoolean("vibrate", vibrate).putInt("deviceModel", deviceModel)
         .putInt("width", width).putInt("height", height).putInt("targetFps", targetFps).putInt("renderScale", renderScale).putInt("texFilter", texFilter)
         .putFloat("gamma", (float)gamma).putFloat("fontScale", (float)fontScale).putFloat("screenTop", screenTop).putString("language", language)
         .putString("theme", theme).putInt("uiBg", uiBg).putInt("uiPanel", uiPanel).putInt("uiFg", uiFg).putInt("uiAccent", uiAccent)
         .putString("uiFont", uiFont).putFloat("uiFontScale", uiFontScale)
         .putString("soundFont", soundFont==null?"":soundFont).apply();
    }

    // pasta de saves/estados por jogo — em Android/data/<pkg>/files/saves/<jogo> (acessível, sem permissão)
    public static File savesDir(Context ctx, String gameName){
        String safe = gameName.replaceAll("[^A-Za-z0-9_.-]", "_");
        File base = ctx.getExternalFilesDir(null);
        if(base==null) base = ctx.getFilesDir();
        File d = new File(new File(base, "saves"), safe);
        if(!d.exists()) d.mkdirs();
        return d;
    }
    // pasta de ROMs padrão do app: Android/data/<pkg>/files/roms (apagada ao reinstalar)
    public static File romsDir(Context ctx){
        File base = ctx.getExternalFilesDir(null);
        if(base==null) base = ctx.getFilesDir();
        File d = new File(base, "roms");
        if(!d.exists()) d.mkdirs();
        return d;
    }
    // pasta COMPARTILHADA e PERSISTENTE (sobrevive a reinstalações): /sdcard/mophun
    // Requer READ_EXTERNAL_STORAGE. É onde o usuário coloca ROMs pelo gerenciador de arquivos.
    public static File sharedRomsDir(){
        return new File(Environment.getExternalStorageDirectory(), "mophun");
    }
    // miniatura (icone) por jogo: capturada do gameplay. Android/data/<pkg>/files/thumbs/<jogo>.png
    public static File thumbFile(Context ctx, String gameName){
        File base = ctx.getExternalFilesDir(null); if(base==null) base = ctx.getFilesDir();
        File d = new File(base, "thumbs"); if(!d.exists()) d.mkdirs();
        String safe = gameName.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(d, safe+".png");
    }
    // pasta de SoundFonts (.sf2): /sdcard/mophun/soundbanks — o usuario coloca os .sf2 aqui (estilo JL-Mod).
    public static File soundbanksDir(){
        File d = new File(sharedRomsDir(), "soundbanks");
        if(!d.exists()) d.mkdirs();
        return d;
    }

    // ---- layout do teclado virtual POR JOGO (mover/redimensionar botões) ----
    private static String safeKey(String gameName){ return gameName.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    // resolucao POR JOGO (indice de modelo). -1 = automatico (detecta pelo nome do arquivo).
    public static int getGameModel(Context ctx, String gameName){
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("model_"+safeKey(gameName), -1);
    }
    public static void setGameModel(Context ctx, String gameName, int model){
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if(model<0) e.remove("model_"+safeKey(gameName)); else e.putInt("model_"+safeKey(gameName), model);
        e.apply();
    }
    public static String getKeypad(Context ctx, String gameName){
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("keypad_"+safeKey(gameName), null);
    }
    public static void setKeypad(Context ctx, String gameName, String layout){
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if(layout==null) e.remove("keypad_"+safeKey(gameName)); else e.putString("keypad_"+safeKey(gameName), layout);
        e.apply();
    }

    // botões de tecla numérica 2/3 no teclado virtual (por jogo). Default: LIGADO p/ jogos cujo nome
    // sugere Bomb Jack (andam com '2'/'3'); DESLIGADO nos demais. Usuário pode alternar no menu do jogo.
    // Jogos que nascem com o teclado numerico LIGADO. Criterio: medicao de entrada, nao palpite --
    // o jogo tem de ser injogavel sem digitos. Bomb Jack anda com '2'/'3'; o Number Drop faz
    // 7.323.007 chamadas a vScanKeys contra 2 de vGetButtonData (BUG-095, "needs the whole number
    // keypad"). Outros leem digitos mas jogam bem no d-pad (SAAF, Brain Vita, Revs!, Ace, 4-in-1):
    // esses ficam por conta do usuario, no toggle, p/ nao poluir a tela sem necessidade.
    /**
     * Jogos que precisam do TECLADO NUMERICO COMPLETO (12 teclas) ligado por padrao. O Bomb Jack saiu
     * daqui: ele so usa '2'/'3' p/ andar, e esses dois botoes vem do walkKeys() -- a grade inteira
     * tomava quase toda a tela e sobrava pouco espaco p/ o jogo.
     */
    public static boolean defaultNumberKeys(String gameName){
        String g = gameName==null ? "" : gameName.toLowerCase();
        return g.contains("number") && g.contains("drop");   // 7,3 milhoes de vScanKeys: injogavel sem
    }
    /**
     * Jogos em que as teclas '2'/'3' sao ANDAR esquerda/direita, e nao digito. Nesses o botao mostra
     * o bonequinho andando; nos demais o teclado e numerico de verdade e o rotulo e o proprio digito.
     */
    public static boolean walkKeys(String gameName){
        String g = gameName==null ? "" : gameName.toLowerCase();
        return g.contains("bomb") && g.contains("jack");
    }
    /** preset de layout do teclado virtual, por jogo (VirtualKeypad.LAY_*). 0 = automatico. */
    public static int getLayoutPreset(Context ctx, String gameName){
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("layout_"+safeKey(gameName), 0);
    }
    public static void setLayoutPreset(Context ctx, String gameName, int preset){
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("layout_"+safeKey(gameName), preset).apply();
    }

    public static boolean getNumberKeys(Context ctx, String gameName){
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("numkeys_"+safeKey(gameName), defaultNumberKeys(gameName));
    }
    /**
     * Os botões de tecla do aparelho (203 voltar / 204 apagar) sao ligados por DETECCAO na ROM
     * (wolphun.vm.KeyScan, no carregamento), nao por lista de nomes: aparecem so no jogo que
     * realmente consulta aquele codigo. Esta preferencia e apenas o override manual do usuario.
     */
    public static boolean getPhoneKeys(Context ctx, String gameName, boolean detectado){
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("phonekeys_"+safeKey(gameName), detectado);
    }
    public static void setPhoneKeys(Context ctx, String gameName, boolean on){
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("phonekeys_"+safeKey(gameName), on).apply();
    }

    public static void setNumberKeys(Context ctx, String gameName, boolean on){
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("numkeys_"+safeKey(gameName), on).apply();
    }
}
