package com.manedsoftware.wolphun;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Configurações: som, modelo do aparelho (resolução/terminal), brilho (gamma) e
 * limite de FPS. Persistidas em SharedPreferences (PrefsConfig) ao sair.
 */
public final class SettingsActivity extends AppCompatActivity {
    private PrefsConfig cfg;
    private Switch soundSw;
    private Spinner deviceSp;
    private SeekBar gammaSb, fpsSb, screenSb;
    private TextView gammaLbl, fpsLbl, screenLbl;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        if(getSupportActionBar()!=null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        cfg = PrefsConfig.load(this);
        Lang.setByCode(cfg.language);
        setTitle(Lang.t("menu.settings"));

        soundSw  = findViewById(R.id.sound);
        deviceSp = findViewById(R.id.device);
        gammaSb  = findViewById(R.id.gamma);
        fpsSb    = findViewById(R.id.fps);
        screenSb = findViewById(R.id.screenTop);
        gammaLbl = findViewById(R.id.gammaLabel);
        fpsLbl   = findViewById(R.id.fpsLabel);
        screenLbl= findViewById(R.id.screenLabel);

        soundSw.setText(Lang.t("lbl.sound"));
        soundSw.setChecked(cfg.sound);

        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item, PrefsConfig.MODEL_NAMES);
        deviceSp.setAdapter(ad);
        if(cfg.deviceModel>=0 && cfg.deviceModel<PrefsConfig.MODEL_NAMES.length)
            deviceSp.setSelection(cfg.deviceModel);

        gammaSb.setMax(60);                          // 0.40..1.00 -> 0..60
        gammaSb.setProgress((int)Math.round((cfg.gamma-0.40)*100));
        gammaSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateGamma(p); }});
        updateGamma(gammaSb.getProgress());

        fpsSb.setMax(90);                            // 30..120 -> 0..90
        fpsSb.setProgress(cfg.targetFps-30);
        fpsSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateFps(p); }});
        updateFps(fpsSb.getProgress());

        screenSb.setMax(50);                         // 0.50..1.00 -> 0..50 (altura da tela do jogo em retrato)
        screenSb.setProgress(Math.round((cfg.screenTop-0.50f)*100));
        screenSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateScreen(p); }});
        updateScreen(screenSb.getProgress());
    }

    private void updateGamma(int p){ double g=0.40+p/100.0; gammaLbl.setText(Lang.t("lbl.gamma")+" "+String.format("%.2f", g)); }
    private void updateFps(int p){ int f=30+p; fpsLbl.setText(Lang.t("lbl.fps")+" "+f); }
    private void updateScreen(int p){ int pct=50+p; screenLbl.setText(Lang.t("lbl.screen")+" "+pct+"%"); }

    @Override public boolean onSupportNavigateUp(){ finish(); return true; }

    @Override protected void onPause(){
        super.onPause();
        cfg.sound = soundSw.isChecked();
        cfg.deviceModel = deviceSp.getSelectedItemPosition();
        int[] r = PrefsConfig.resForModel(cfg.deviceModel);
        cfg.width = r[0]; cfg.height = r[1];
        cfg.gamma = 0.40 + gammaSb.getProgress()/100.0;
        cfg.targetFps = 30 + fpsSb.getProgress();
        cfg.screenTop = 0.50f + screenSb.getProgress()/100f;
        cfg.save(this);
    }

    // listener-base pra não repetir os métodos vazios
    private static abstract class Simple implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar s){}
        public void onStopTrackingTouch(SeekBar s){}
    }
}
