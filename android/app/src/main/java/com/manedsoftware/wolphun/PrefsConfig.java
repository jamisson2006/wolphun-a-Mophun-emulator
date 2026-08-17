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
    public int deviceModel = 2;      // default SE T610 (128x160)
    public int width = 128, height = 160;
    public int targetFps = 60;
    public double gamma = 1.0;
    public float screenTop = 0.66f;      // fração da altura (retrato) p/ a tela do jogo; resto = controles
    public String language = "pt";       // idioma da interface (Lang)

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
        c.deviceModel = p.getInt("deviceModel", c.deviceModel);
        c.width = p.getInt("width", c.width);
        c.height = p.getInt("height", c.height);
        c.targetFps = p.getInt("targetFps", c.targetFps);
        c.gamma = (double) p.getFloat("gamma", (float)c.gamma);
        c.screenTop = p.getFloat("screenTop", c.screenTop);
        c.language = p.getString("language", c.language);
        return c;
    }
    public void save(Context ctx){
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt("scale", scale).putBoolean("sound", sound).putInt("deviceModel", deviceModel)
         .putInt("width", width).putInt("height", height).putInt("targetFps", targetFps)
         .putFloat("gamma", (float)gamma).putFloat("screenTop", screenTop).putString("language", language).apply();
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

    // ---- layout do teclado virtual POR JOGO (mover/redimensionar botões) ----
    private static String safeKey(String gameName){ return gameName.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    public static String getKeypad(Context ctx, String gameName){
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("keypad_"+safeKey(gameName), null);
    }
    public static void setKeypad(Context ctx, String gameName, String layout){
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if(layout==null) e.remove("keypad_"+safeKey(gameName)); else e.putString("keypad_"+safeKey(gameName), layout);
        e.apply();
    }
}
