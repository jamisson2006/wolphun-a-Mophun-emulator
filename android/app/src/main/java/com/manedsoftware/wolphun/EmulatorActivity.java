package com.manedsoftware.wolphun;

import android.Manifest;
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
import androidx.core.app.ActivityCompat;
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
        view.setWalkKeys(PrefsConfig.walkKeys(gameName));   // 2/3 = andar esq/dir (Bomb Jack)
        view.setLayoutPreset(PrefsConfig.getLayoutPreset(this, gameName));
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
            session.setContext(this);      // servicos do aparelho (vibracao)
            session.forceModel = PrefsConfig.getGameModel(this, gameName);   // resolucao por-jogo (menu da biblioteca)
            session.init();
            view.attach(session.getScreen());
            // jogo saiu sozinho (softkey 'sair'/Quit ou fim) -> oferece Reiniciar / Voltar a biblioteca
            session.setEndListener(new GameSession.EndListener(){ public void onGameEnded(){
                runOnUiThread(new Runnable(){ public void run(){ showGameEnded(); } });
            }});
            applyPhoneKeys();   // botoes SAIR/APAGAR: so os codigos que esta ROM consulta
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
        final boolean temPh = session!=null && !session.phoneKeysUsed().isEmpty();
        final boolean phOn   = temPh && PrefsConfig.getPhoneKeys(this, gameName, true);
        final String[] items = { Lang.t("gw.savestate"), Lang.t("gw.loadstate"), Lang.t("gw.restart"),
            Lang.t("gw.editkeys"), Lang.t("gw.layout")+"  –  "+layoutNames()[layoutAtual()],
            Lang.t("gw.numkeys")+(numOn?"  ✓":""),
            Lang.t("gw.phonekeys")+(temPh ? (phOn?"  ✓":"") : "  —"),
            Lang.t("gw.screenshot"), Lang.t("gw.close") };
        new AlertDialog.Builder(this)
            .setTitle(gameName)
            .setItems(items, (d, which) -> {
                switch(which){
                    case 0: doSave(); break;
                    case 1: doLoad(); break;
                    case 2: doRestart(); break;
                    case 3: enterEditMode(); break;
                    case 4: chooseLayout(); break;
                    case 5: toggleNumberKeys(); break;
                    case 6: togglePhoneKeys(); break;
                    case 7: chooseScreenshot(); break;
                    case 8: finish(); break;
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

    // ---- presets de layout do teclado virtual (estilo J2ME Loader) ----
    private String[] layoutNames(){
        return new String[]{ Lang.t("lay.auto"), Lang.t("lay.gamepad"), Lang.t("lay.phone"),
            Lang.t("lay.numctrl"), Lang.t("lay.ctrlnum"), Lang.t("lay.numbers"),
            Lang.t("lay.arrows"), Lang.t("lay.all") };
    }
    private int layoutAtual(){
        int p = PrefsConfig.getLayoutPreset(this, gameName);
        return (p>=0 && p<VirtualKeypad.LAY_COUNT) ? p : 0;
    }
    private void chooseLayout(){
        new AlertDialog.Builder(this)
            .setTitle(Lang.t("gw.layout"))
            .setSingleChoiceItems(layoutNames(), layoutAtual(), (d, which) -> {
                PrefsConfig.setLayoutPreset(this, gameName, which);
                // trocar de preset só tem efeito visível se as posições fixadas na edição saírem do
                // caminho -- senão o botão movido ignora o arranjo novo e o usuário acha que travou.
                if(view!=null){ view.resetKeypad(); view.setLayoutPreset(which); }
                PrefsConfig.setKeypad(this, gameName, null);
                d.dismiss();
                toast(layoutNames()[which]);
            })
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }

    // liga/desliga os botões das teclas do aparelho C(204) / SAIR(203) -- por jogo e persistido
    private void togglePhoneKeys(){
        if(session==null || session.phoneKeysUsed().isEmpty()){
            toast(Lang.t("gw.nophonekeys"));   // esta ROM nao le nenhuma tecla de aparelho
            return;
        }
        boolean on = !PrefsConfig.getPhoneKeys(this, gameName, true);
        PrefsConfig.setPhoneKeys(this, gameName, on);
        applyPhoneKeys();
        toast(Lang.t("gw.phonekeys")+": "+(on?"ON":"OFF"));
    }

    /** aplica a visibilidade dos botoes de tecla do aparelho a partir da DETECCAO + preferencia. */
    private void applyPhoneKeys(){
        if(view==null || session==null) return;
        java.util.Set<Integer> usados = session.phoneKeysUsed();
        if(usados.isEmpty() || !PrefsConfig.getPhoneKeys(this, gameName, true)) view.hidePhoneKeys();
        else view.setPhoneKeys(usados);
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

    // ---- captura de tela (sempre na RESOLUCAO DO APARELHO, nunca na do jogo) ----
    private static final int REQ_WRITE = 77;
    private Integer shotPendente;        // modo escolhido, esperando a permissao de escrita

    private void chooseScreenshot(){
        new AlertDialog.Builder(this)
            .setTitle(Lang.t("gw.screenshot"))
            .setItems(new String[]{ Lang.t("shot.game"), Lang.t("shot.full") },
                      (d, which) -> takeScreenshot(which == 1))
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }

    private void takeScreenshot(final boolean comControles){
        if(view == null) return;
        // Android <= 9: sem a permissao a foto iria para a pasta privada (fora da Galeria).
        // Pede e refaz a captura sozinho quando o usuario responde.
        if(ScreenCapture.needsPermission(this)){
            shotPendente = comControles ? 1 : 0;
            ActivityCompat.requestPermissions(this,
                new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_WRITE);
            return;
        }
        view.requestCapture(comControles, new EmulatorView.CaptureCallback(){
            public void onCaptured(final android.graphics.Bitmap bmp){
                if(bmp == null){ runOnUiThread(() -> toast(Lang.t("shot.notready"))); return; }
                // comprimir PNG trava a thread de render: faz num worker.
                new Thread(new Runnable(){ public void run(){
                    final int w = bmp.getWidth(), h = bmp.getHeight();
                    final String onde = ScreenCapture.save(EmulatorActivity.this, bmp, gameName);
                    bmp.recycle();
                    runOnUiThread(new Runnable(){ public void run(){
                        if(onde == null) toast(Lang.t("shot.fail"));
                        else Toast.makeText(EmulatorActivity.this,
                                 Lang.t("shot.saved", w, h, onde), Toast.LENGTH_LONG).show();
                    }});
                }}, "wolphun-shot").start();
            }
        });
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res){
        super.onRequestPermissionsResult(req, perms, res);
        if(req != REQ_WRITE || shotPendente == null) return;
        final int modo = shotPendente;
        shotPendente = null;
        // negada tambem segue: o ScreenCapture grava na pasta privada do app.
        takeScreenshot(modo == 1);
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
