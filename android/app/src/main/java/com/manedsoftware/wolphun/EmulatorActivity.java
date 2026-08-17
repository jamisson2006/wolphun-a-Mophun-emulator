package com.manedsoftware.wolphun;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
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
        setContentView(view);

        MidiBridge.setBackend(new AndroidAudio(this));

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
            session.init();
            view.attach(session.getScreen());
            session.start();
        } catch(Throwable t){
            Toast.makeText(this, "Falha ao iniciar: "+t, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onMenu(){
        runOnUiThread(new Runnable(){ public void run(){ showMenu(); } });
    }

    private void showMenu(){
        final String[] items = { Lang.t("gw.savestate"), Lang.t("gw.loadstate"), Lang.t("gw.restart"), Lang.t("gw.editkeys"), Lang.t("gw.close") };
        new AlertDialog.Builder(this)
            .setTitle(gameName)
            .setItems(items, (d, which) -> {
                switch(which){
                    case 0: doSave(); break;
                    case 1: doLoad(); break;
                    case 2: doRestart(); break;
                    case 3: enterEditMode(); break;
                    case 4: finish(); break;
                }
            })
            .setNegativeButton(Lang.t("gw.backlib"), null)
            .show();
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
        try { session.saveState(stateFile()); toast("Estado salvo"); }
        catch(Throwable t){ toast("Falha ao salvar: "+t); }
    }
    private void doLoad(){
        File f = stateFile();
        if(!f.isFile()){ toast("Nenhum estado salvo"); return; }
        try { session.loadState(f); toast("Estado carregado"); }
        catch(Throwable t){ toast("Falha ao carregar: "+t); }
    }
    private void doRestart(){
        Intent i = getIntent();
        finish();
        startActivity(i);
    }
    private void toast(String s){ Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onPause(){ super.onPause(); if(session!=null) session.pause(); }
    @Override protected void onResume(){ super.onResume(); if(session!=null) session.resume(); }
    @Override protected void onDestroy(){ super.onDestroy(); if(session!=null) session.stop(); MidiBridge.setBackend(null); }
}
