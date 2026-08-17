package mophun;

import java.awt.event.KeyEvent;
import java.io.*;
import java.util.*;

/**
 * Configuracoes persistentes do emulador (video, som, pasta de ROMs, mapeamento
 * de teclas). Salvas em ~/.mophunemu/config.properties. Tambem define a pasta de
 * saves e o diretorio-base da app.
 */
public final class Config {
    // ---- acoes de input (botao mophun) que o usuario pode mapear ----
    // BITS REAIS do mophun (nofun KeyCode.cs): Up=0x01 Down=0x02 Left=0x04 Right=0x08
    // Fire=0x10 Select=0x20 Fire2=0x100. NAO existe 0x200/0x400 (antes as softkeys usavam
    // esses bits FANTASMA -> o jogo nunca as via -> menu/pausa nao funcionavam).
    // Select(0x20) e a softkey de menu/OK; Fire2(0x100) e a 2a softkey/acao.
    // Cada acao aceita VARIAS teclas (setas + WASD + numpad), estilo KEmulator.
    public static final int KEYMAP_VERSION = 3;   // v3 = bits reais + multi-tecla + softkeys ajustadas (reseta defaults)
    public static final String[] ACTIONS    = {"Up","Down","Left","Right","Fire / OK","Select (softkey/menu)","Fire2 (softkey 2)"};
    public static final int[]    ACTION_BITS = {0x01,0x02,0x04,0x08,0x10,0x20,0x100};
    // defaults com MULTIPLAS teclas: setas/WASD + numpad posicional (num8=cima, num2=baixo, etc.,
    // igual ao teclado do celular) -> "todos os teclados funcionando".
    public static final int[][]  ACTION_DEF_VK = {
        {KeyEvent.VK_UP,    KeyEvent.VK_W, KeyEvent.VK_NUMPAD8},
        {KeyEvent.VK_DOWN,  KeyEvent.VK_S, KeyEvent.VK_NUMPAD2},
        {KeyEvent.VK_LEFT,  KeyEvent.VK_A, KeyEvent.VK_NUMPAD4},
        {KeyEvent.VK_RIGHT, KeyEvent.VK_D, KeyEvent.VK_NUMPAD6},
        {KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_NUMPAD5, KeyEvent.VK_CONTROL},
        {KeyEvent.VK_Q,     KeyEvent.VK_NUMPAD0},                           // Select = softkey esq. / menu / OK / sair (celular: tecla 0). Sem Shift (evita sair sem querer)
        {KeyEvent.VK_E,     KeyEvent.VK_BACK_SPACE, KeyEvent.VK_X}          // Fire2  = softkey dir. / 2a acao (Backspace = "voltar")
    };

    // ---- valores ----
    public String  gamesRoot = null;     // pasta com as ROMs (uma subpasta por jogo)
    public int     scale     = 3;        // fator de zoom da tela
    public int     width     = 128;      // resolucao reportada ao jogo (vGetCaps)
    public int     height    = 160;
    public boolean sound     = true;
    public int     targetFps = 60;       // cap de frame (vFlipScreen bloqueia ate 1/targetFps)
    public int     deviceModel = 2;      // SystemDeviceModel (default SonyEricssonT610); define terminal/vendor
    public String  language = "pt";      // idioma da interface (en/es/de/pt/uk/ru)
    public double  gamma = 1.0;          // brilho de display: 1.0=cores exatas (SDK, lineares); <1.0 clareia (curva
                                         // ~1/2.2 do LCD/oficial), preservando matiz. Ajustavel no slider "Brilho".

    // modelos de aparelho (SystemDeviceModel do nofun) -> nome + vendor + resolucao sugerida
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
    // acao -> lista de VKs (varias teclas por botao)
    public final LinkedHashMap<String,int[]> binds = new LinkedHashMap<String,int[]>();

    public Config(){
        for(int i=0;i<ACTIONS.length;i++) binds.put(ACTIONS[i], ACTION_DEF_VK[i].clone());
    }

    // ---- diretorios da app ----
    public static File appDir(){
        File d = new File(System.getProperty("user.home"), ".mophunemu");
        if(!d.exists()) d.mkdirs();
        return d;
    }
    public static File configFile(){ return new File(appDir(), "config.properties"); }
    /** pasta de saves para um jogo especifico (nao polui a pasta da ROM). */
    public File savesDir(String gameName){
        String safe = gameName.replaceAll("[^A-Za-z0-9_.-]", "_");
        File d = new File(new File(appDir(),"saves"), safe);
        if(!d.exists()) d.mkdirs();
        return d;
    }

    // ---- monta o mapa VK->bit p/ o Screen (cada tecla da lista aponta pro bit da acao) ----
    public Map<Integer,Integer> screenKeyMap(){
        Map<Integer,Integer> m = new HashMap<Integer,Integer>();
        for(int i=0;i<ACTIONS.length;i++){
            int[] vks = binds.get(ACTIONS[i]);
            if(vks==null) continue;
            for(int vk: vks) if(vk>=0) m.put(vk, m.containsKey(vk) ? (m.get(vk)|ACTION_BITS[i]) : ACTION_BITS[i]);
        }
        return m;
    }
    // "38,87,104" -> int[]; vazio -> array vazio
    static int[] parseVks(String s){
        if(s==null || s.trim().isEmpty()) return new int[0];
        String[] parts = s.split(",");
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        for(String p: parts){ try { out.add(Integer.parseInt(p.trim())); } catch(Exception e){} }
        int[] a = new int[out.size()]; for(int i=0;i<a.length;i++) a[i]=out.get(i); return a;
    }
    static String joinVks(int[] a){
        if(a==null||a.length==0) return "";
        StringBuilder b = new StringBuilder();
        for(int i=0;i<a.length;i++){ if(i>0) b.append(','); b.append(a[i]); }
        return b.toString();
    }

    // ---- carregar/salvar ----
    public static Config load(){
        Config c = new Config();
        File f = configFile();
        if(!f.isFile()) return c;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)){ p.load(in); }
        catch(Exception e){ System.err.println("[config] leitura falhou: "+e); return c; }
        c.gamesRoot = p.getProperty("gamesRoot", c.gamesRoot);
        c.scale  = getInt(p,"scale",  c.scale);
        c.width  = getInt(p,"width",  c.width);
        c.height = getInt(p,"height", c.height);
        c.sound  = Boolean.parseBoolean(p.getProperty("sound", String.valueOf(c.sound)));
        c.targetFps   = getInt(p,"targetFps",   c.targetFps);
        c.deviceModel = getInt(p,"deviceModel", c.deviceModel);
        try { c.gamma = Double.parseDouble(p.getProperty("gamma", String.valueOf(c.gamma))); } catch(Exception e){}
        c.language = p.getProperty("language", c.language); Lang.setByCode(c.language);
        // keymap: so carrega os binds salvos se a VERSAO bate; senao mantem os novos defaults
        // (migra automaticamente do modelo antigo de 1-tecla/bits-fantasma p/ o novo multi-tecla).
        if(getInt(p,"keymapVersion",0) >= KEYMAP_VERSION){
            for(String a: ACTIONS){
                String v = p.getProperty("key."+a);
                if(v!=null) c.binds.put(a, parseVks(v));   // pode ser vazio (usuario limpou)
            }
        }
        return c;
    }
    public void save(){
        Properties p = new Properties();
        if(gamesRoot!=null) p.setProperty("gamesRoot", gamesRoot);
        p.setProperty("scale",  String.valueOf(scale));
        p.setProperty("width",  String.valueOf(width));
        p.setProperty("height", String.valueOf(height));
        p.setProperty("sound",  String.valueOf(sound));
        p.setProperty("targetFps",   String.valueOf(targetFps));
        p.setProperty("deviceModel", String.valueOf(deviceModel));
        p.setProperty("gamma",       String.valueOf(gamma));
        p.setProperty("language",    language);
        p.setProperty("keymapVersion", String.valueOf(KEYMAP_VERSION));
        for(String a: ACTIONS) p.setProperty("key."+a, joinVks(binds.get(a)));
        try (FileOutputStream out = new FileOutputStream(configFile())){
            p.store(out, "Mophun Emulator config");
        } catch(Exception e){ System.err.println("[config] gravacao falhou: "+e); }
    }
    static int getInt(Properties p,String k,int def){
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def))); }
        catch(Exception e){ return def; }
    }
}
