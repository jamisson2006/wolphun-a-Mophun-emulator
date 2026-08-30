package com.manedsoftware.wolphun;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.File;
import wolphun.vm.MidiBridge;

/**
 * Tela que roda uma partida: monta a GameSession + EmulatorView e liga tudo.
 * O botão ≡ do teclado virtual abre o menu (Salvar/Carregar estado, Reiniciar, Fechar).
 */
public final class EmulatorActivity extends Activity implements EmulatorView.MenuListener {
    public static final String EXTRA_PATH = "rom_path";
    public static final String EXTRA_NAME = "rom_name";

    private PrefsConfig cfg;
    private GameSession session;
    private EmulatorView view;
    private SoundFontSynth sfSynth;   // synth de SoundFont (NDK), se um .sf2 estiver configurado
    private File saveDir;
    private String gameName;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        gameName = getIntent().getStringExtra(EXTRA_NAME);
        if(path==null){ finish(); return; }
        File mpn = new File(path);
        if(gameName==null) gameName = mpn.getName();

        cfg = PrefsConfig.load(this);
        Lang.setByCode(cfg.language);
        saveDir = PrefsConfig.savesDir(this, gameName);

        view = new EmulatorView(this);
        view.setMenuListener(this);
        view.setScreenTopFraction(cfg.screenTop);   // sobe a tela do jogo (não fica atrás dos botões)
        view.importKeypad(PrefsConfig.getKeypad(this, gameName));   // layout de teclado customizado deste jogo
        view.setShowNumbers(PrefsConfig.getNumberKeys(this, gameName));   // botões 2/3 (default ON no Bomb Jack)
        view.setCanvasMenuVisible(false);           // usamos o BOTAO REAL abaixo (View garantida), nao o ≡ do canvas

        // botao de opcoes = View REAL sobreposta (garantido de renderizar em qualquer celular, diferente do canvas)
        FrameLayout container = new FrameLayout(this);
        container.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        Button menuBtn = new Button(this);
        menuBtn.setText("≡");                  // ≡
        menuBtn.setTextColor(Color.WHITE);
        menuBtn.setTypeface(Typeface.DEFAULT_BOLD);
        menuBtn.setTextSize(22);
        menuBtn.setPadding(0,0,0,0);
        menuBtn.setMinWidth(0); menuBtn.setMinHeight(0);
        menuBtn.setMinimumWidth(0); menuBtn.setMinimumHeight(0);
        menuBtn.setElevation(dp(8));                // acima da SurfaceView em qualquer aparelho
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC000000);                    // circulo escuro semi-opaco
        bg.setStroke(dp(2), 0xFFE8451F);            // borda accent (laranja)
        menuBtn.setBackground(bg);
        menuBtn.setOnClickListener(v -> showMenu());
        int sz = dp(52);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sz, sz);
        lp.gravity = Gravity.TOP | Gravity.END;     // canto superior direito
        lp.topMargin = dp(48);                      // um pouco para baixo (fora da barra de status)
        lp.rightMargin = dp(10);
        container.addView(menuBtn, lp);
        menuBtn.bringToFront();
        setContentView(container);

        // backend de MIDI: com .sf2 configurado -> synth de SoundFont (NDK); senao -> Sonivox/MediaPlayer.
        sfSynth = SoundFontSynth.tryCreate(cfg.soundFont);
        MidiBridge.setBackend(sfSynth != null ? sfSynth : new AndroidAudio(this));

        // descriptografia embutida: se a ROM estiver cifrada (DRM SE), decodifica p/ cache antes de rodar
        File toRun = mpn;
        try {
            byte[] d = MophunCrypt.readAll(mpn);
            if(MophunCrypt.looksEncrypted(d)){
                byte[] dec = MophunCrypt.decrypt(d);
                if(dec!=null){
                    File cache = new File(getCacheDir(), "dec_"+mpn.getName());
                    MophunCrypt.writeAll(cache, dec);
                    toRun = cache;
                }
            }
        } catch(Throwable t){ /* segue com o arquivo original */ }

        try {
            session = new GameSession(toRun, gameName, cfg, saveDir);
            session.forceModel = PrefsConfig.getGameModel(this, gameName);   // resolucao por-jogo (menu da biblioteca)
            session.init();
            view.attach(session.getScreen());
            // jogo saiu sozinho (softkey 'sair'/Quit ou fim) -> oferece Reiniciar / Voltar a biblioteca
            session.setEndListener(new GameSession.EndListener(){ public void onGameEnded(){
                runOnUiThread(new Runnable(){ public void run(){ showGameEnded(); } });
            }});
            session.start();
        } catch(Throwable t){
            // erro de container invalido/criptografado -> msg amigavel localizada (antes vazava
            // "Falha ao iniciar: ...Nao e um VMGP valido" em PT mesmo com outro idioma escolhido).
            Throwable root = t; while(root.getCause()!=null) root = root.getCause();
            boolean notVmgp = (root instanceof IllegalArgumentException)
                && root.getMessage()!=null && root.getMessage().toLowerCase().contains("vmgp");
            String detail = notVmgp ? Lang.t("err.notvmgp") : String.valueOf(t);
            Toast.makeText(this, Lang.t("err.cantstart", gameName)+"\n"+detail, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onMenu(){
        runOnUiThread(new Runnable(){ public void run(){ showMenu(); } });
    }

    private boolean endShown = false;
    // tela quando o jogo termina por conta propria (mesma ideia do desktop onGameEnded)
    private void showGameEnded(){
        if(isFinishing() || endShown) return;
        endShown = true;
        new AlertDialog.Builder(this)
            .setTitle(gameName + " — " + Lang.t("st.ended"))
            .setMessage(Lang.t("gw.gameended"))
            .setCancelable(false)
            .setPositiveButton(Lang.t("gw.restartgame"), (d,w) -> { endShown=false; doRestart(); })
            .setNegativeButton(Lang.t("gw.backlib"), (d,w) -> finish())
            .show();
    }

    private void showMenu(){
        final boolean numOn = PrefsConfig.getNumberKeys(this, gameName);
        final String[] items = { Lang.t("gw.savestate"), Lang.t("gw.loadstate"), Lang.t("gw.restart"),
            Lang.t("gw.editkeys"), Lang.t("gw.numkeys")+(numOn?"  ✓":""), Lang.t("gw.close") };
        new AlertDialog.Builder(this)
            .setTitle(gameName)
            .setItems(items, (d, which) -> {
                switch(which){
                    case 0: doSave(); break;
                    case 1: doLoad(); break;
                    case 2: doRestart(); break;
                    case 3: enterEditMode(); break;
                    case 4: toggleNumberKeys(); break;
                    case 5: finish(); break;
                }
            })
            .setNegativeButton(Lang.t("gw.resume"), null)   // listener null = fecha o menu e RETOMA o jogo (por isso "Resume", nao "Back to library")
            .show();
    }

    // liga/desliga os botões de tecla numérica 2/3 (por jogo) e persiste
    private void toggleNumberKeys(){
        boolean on = !PrefsConfig.getNumberKeys(this, gameName);
        PrefsConfig.setNumberKeys(this, gameName, on);
        if(view!=null) view.setShowNumbers(on);
        toast(Lang.t("gw.numkeys")+": "+(on?"ON":"OFF"));
    }

    // ---- modo de edição do teclado (mover/redimensionar botões, salvo por jogo) ----
    private void enterEditMode(){
        view.setEditMode(true);
        toast(Lang.t("gw.edithint"));
    }
    @Override public void onBackPressed(){
        if(view!=null && view.isEditMode()){
            new AlertDialog.Builder(this)
                .setTitle(Lang.t("gw.editkeys"))
                .setPositiveButton(Lang.t("gw.save"), (d,w) -> {
                    PrefsConfig.setKeypad(this, gameName, view.exportKeypad()); view.setEditMode(false); toast(Lang.t("gw.save")); })
                .setNeutralButton(Lang.t("gw.reset"), (d,w) -> {
                    view.resetKeypad(); PrefsConfig.setKeypad(this, gameName, null); view.setEditMode(false); toast(Lang.t("gw.reset")); })
                .setNegativeButton(Lang.t("btn.cancel"), (d,w) -> {
                    view.importKeypad(PrefsConfig.getKeypad(this, gameName)); view.setEditMode(false); })
                .show();
            return;
        }
        super.onBackPressed();
    }

    private File stateFile(){ return new File(saveDir, "savestate.bin"); }
    private void doSave(){
        try { session.saveState(stateFile()); toast(Lang.t("st.saved")); }
        catch(Throwable t){ toast(Lang.t("err.savestate")+" "+t); }
    }
    private void doLoad(){
        File f = stateFile();
        if(!f.isFile()){ toast(Lang.t("dlg.nostate")); return; }
        try { session.loadState(f); toast(Lang.t("st.loaded")); }
        catch(Throwable t){ toast(Lang.t("err.loadstate")+" "+t); }
    }
    private void doRestart(){
        Intent i = getIntent();
        finish();
        startActivity(i);
    }
    private void toast(String s){ Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v){ return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onPause(){ super.onPause(); saveThumbnail(); if(session!=null) session.pause(); }

    // captura um frame do gameplay como miniatura/icone do jogo na biblioteca
    private void saveThumbnail(){
        try {
            if(view==null || session==null || session.getScreen()==null) return;
            if(session.getScreen().flips < 10) return;              // ainda carregando -> nao salva tela preta
            android.graphics.Bitmap bmp = view.snapshot(dp(96));
            if(bmp==null) return;
            File f = PrefsConfig.thumbFile(this, gameName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch(Throwable t){ /* miniatura e opcional */ }
    }
    @Override protected void onResume(){ super.onResume(); if(session!=null) session.resume(); }
    @Override protected void onDestroy(){
        super.onDestroy();
        if(session!=null) session.stop();
        MidiBridge.setBackend(null);
        if(sfSynth!=null){ sfSynth.release(); sfSynth=null; }
    }
}
