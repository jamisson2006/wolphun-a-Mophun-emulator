package com.manedsoftware.wolphun;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.HashMap;

/**
 * Configurações: som, modelo do aparelho (resolução/terminal), brilho (gamma) e
 * limite de FPS. Persistidas em SharedPreferences (PrefsConfig) ao sair.
 */
public final class SettingsActivity extends AppCompatActivity {
    private PrefsConfig cfg;
    private Switch soundSw, reverbSw;
    private Button soundfontBtn;
    private Spinner deviceSp;
    private SeekBar gammaSb, fpsSb, screenSb, fontSb, hdSb;
    private TextView gammaLbl, fpsLbl, screenLbl, fontLbl, hdLbl;
    // ---- aparencia (tema) ----
    private Spinner themeSpin, fontSpin;
    private SeekBar fontSizeBar;
    private TextView sizeLbl;
    private Button swBg, swPanel, swText, swAccent;
    private String[] themeIds;
    private static final String[] FONT_IDS = {"SansSerif","Serif","Monospaced"};
    private final HashMap<Button,Integer> swColor = new HashMap<Button,Integer>();

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        if(getSupportActionBar()!=null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        cfg = PrefsConfig.load(this);
        Lang.setByCode(cfg.language);
        setTitle(Lang.t("menu.settings"));

        soundSw  = findViewById(R.id.sound);
        reverbSw = findViewById(R.id.reverb);
        deviceSp = findViewById(R.id.device);
        gammaSb  = findViewById(R.id.gamma);
        fpsSb    = findViewById(R.id.fps);
        screenSb = findViewById(R.id.screenTop);
        fontSb   = findViewById(R.id.fontScale);
        hdSb     = findViewById(R.id.hdScale);
        gammaLbl = findViewById(R.id.gammaLabel);
        fpsLbl   = findViewById(R.id.fpsLabel);
        screenLbl= findViewById(R.id.screenLabel);
        fontLbl  = findViewById(R.id.fontLabel);
        hdLbl    = findViewById(R.id.hdLabel);

        soundSw.setText(Lang.t("lbl.sound"));
        soundSw.setChecked(cfg.sound);
        reverbSw.setText(Lang.t("m.reverb"));
        reverbSw.setChecked(cfg.reverb);

        soundfontBtn = findViewById(R.id.soundfont);
        updateSoundfontBtn();
        soundfontBtn.setOnClickListener(v -> chooseSoundfont());

        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item, PrefsConfig.MODEL_NAMES);
        deviceSp.setAdapter(ad);
        if(cfg.deviceModel>=0 && cfg.deviceModel<PrefsConfig.MODEL_NAMES.length)
            deviceSp.setSelection(cfg.deviceModel);

        gammaSb.setMax(60);                          // 0.40..1.00 -> 0..60
        gammaSb.setProgress((int)Math.round((cfg.gamma-0.40)*100));
        gammaSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateGamma(p); }});
        updateGamma(gammaSb.getProgress());

        fpsSb.setMax(119);                           // 1..120 -> 0..119
        fpsSb.setProgress(Math.max(0, Math.min(119, cfg.targetFps-1)));
        fpsSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateFps(p); }});
        updateFps(fpsSb.getProgress());

        screenSb.setMax(50);                         // 0.50..1.00 -> 0..50 (altura da tela do jogo em retrato)
        screenSb.setProgress(Math.round((cfg.screenTop-0.50f)*100));
        screenSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateScreen(p); }});
        updateScreen(screenSb.getProgress());

        fontSb.setMax(150);                          // 1.00..2.50 -> 0..150 (tamanho do texto in-game)
        fontSb.setProgress((int)Math.round((cfg.fontScale-1.0)*100));
        fontSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateFont(p); }});
        updateFont(fontSb.getProgress());

        hdSb.setMax(3);                              // 1..4 (supersampling interno: nitidez do 3D)
        hdSb.setProgress(Math.max(0, Math.min(3, cfg.renderScale-1)));
        hdSb.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int p,boolean u){ updateHd(p); }});
        updateHd(hdSb.getProgress());

        setupAppearance();
        Theme.apply(this, cfg);                          // aplica o tema salvo nesta tela
    }

    // ---------------- aparencia (tema: cores + fonte) ----------------
    private void setupAppearance(){
        ((TextView)findViewById(R.id.apTitle)).setText(Lang.t("m.appearance"));
        ((TextView)findViewById(R.id.themeLabel)).setText(Lang.t("ap.theme"));
        ((TextView)findViewById(R.id.fontLabel2)).setText(Lang.t("ap.font"));
        themeSpin   = findViewById(R.id.themeSpin);
        fontSpin    = findViewById(R.id.fontSpin);
        fontSizeBar = findViewById(R.id.fontSizeBar);
        sizeLbl     = findViewById(R.id.sizeLabel);
        swBg=findViewById(R.id.swBg); swPanel=findViewById(R.id.swPanel);
        swText=findViewById(R.id.swText); swAccent=findViewById(R.id.swAccent);
        for(Button b : new Button[]{swBg,swPanel,swText,swAccent}) b.setTag("themeSwatch");

        themeIds = Theme.ids();
        String[] themeNames = new String[themeIds.length];
        for(int i=0;i<themeIds.length;i++) themeNames[i]=Lang.t("theme."+themeIds[i]);
        themeSpin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, themeNames));
        int ti=0; for(int i=0;i<themeIds.length;i++) if(themeIds[i].equals(cfg.theme)) ti=i;
        themeSpin.setSelection(ti);

        String[] fontNames = { Lang.t("font.sans"), Lang.t("font.serif"), Lang.t("font.mono") };
        fontSpin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, fontNames));
        int fi=0; for(int i=0;i<FONT_IDS.length;i++) if(FONT_IDS[i].equals(cfg.uiFont)) fi=i;
        fontSpin.setSelection(fi);

        fontSizeBar.setMax(80);                          // 0.80..1.60 -> 0..80
        fontSizeBar.setProgress(Math.max(0, Math.min(80, Math.round((cfg.uiFontScale-0.80f)*100))));
        updateSizeLbl();
        refreshSwatches();

        themeSpin.setOnItemSelectedListener(new SimpleSel(){ public void onItemSelected(AdapterView<?> p,View v,int pos,long id){ onThemePicked(pos); }});
        fontSpin.setOnItemSelectedListener(new SimpleSel(){ public void onItemSelected(AdapterView<?> p,View v,int pos,long id){ cfg.uiFont=FONT_IDS[pos]; liveApply(); }});
        fontSizeBar.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar s,int pr,boolean u){ cfg.uiFontScale=0.80f+pr/100f; updateSizeLbl(); liveApply(); }});
        View.OnClickListener sw = new View.OnClickListener(){ public void onClick(View v){ pickSwatch((Button)v); }};
        swBg.setOnClickListener(sw); swPanel.setOnClickListener(sw); swText.setOnClickListener(sw); swAccent.setOnClickListener(sw);
    }

    private void updateSizeLbl(){ sizeLbl.setText(Lang.t("ap.size")+"  "+String.format("%.2f", cfg.uiFontScale)+"x"); }

    // ---- Soundfont (.sf2): escolhe da pasta /sdcard/mophun/soundbanks ----
    private void updateSoundfontBtn(){
        String cur = (cfg.soundFont!=null) ? new java.io.File(cfg.soundFont).getName() : Lang.t("sf.default");
        soundfontBtn.setText(Lang.t("sf.current")+" "+cur);
    }
    private void chooseSoundfont(){
        java.io.File dir = PrefsConfig.soundbanksDir();
        java.io.File[] sf2 = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".sf2"));
        final java.util.List<String> paths = new java.util.ArrayList<String>();  // null-first p/ "Default"
        final java.util.List<String> names = new java.util.ArrayList<String>();
        paths.add(null); names.add(Lang.t("sf.default"));
        if(sf2!=null){
            java.util.Arrays.sort(sf2);
            for(java.io.File f : sf2){ paths.add(f.getAbsolutePath()); names.add(f.getName()); }
        }
        int cur = 0;
        for(int i=1;i<paths.size();i++) if(paths.get(i).equals(cfg.soundFont)) cur=i;
        new AlertDialog.Builder(this)
            .setTitle(Lang.t("m.soundfont"))
            .setSingleChoiceItems(names.toArray(new String[0]), cur, (d, which) -> {
                cfg.soundFont = paths.get(which);          // null = padrao
                cfg.save(this);
                updateSoundfontBtn();
                d.dismiss();
                Toast.makeText(this, Lang.t("sf.applied"), Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(Lang.t("sf.folderhint"), null)
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }

    private void onThemePicked(int pos){
        cfg.theme = themeIds[pos];
        if(!"custom".equals(cfg.theme)){
            Theme t = Theme.byId(cfg.theme, cfg);        // reflete a fonte do preset no spinner
            cfg.uiFont = t.font;
            int fi=0; for(int i=0;i<FONT_IDS.length;i++) if(FONT_IDS[i].equals(cfg.uiFont)) fi=i;
            if(fontSpin.getSelectedItemPosition()!=fi) fontSpin.setSelection(fi);
        }
        liveApply();
        refreshSwatches();
    }

    private void liveApply(){ Theme.apply(this, cfg); }  // swatches sao 'themeSwatch' -> nao repintados

    private void refreshSwatches(){
        Theme t = Theme.byId(cfg.theme, cfg);            // custom -> usa cfg.ui*
        setSwatch(swBg, t.bg); setSwatch(swPanel, t.panel); setSwatch(swText, t.fg); setSwatch(swAccent, t.accent);
    }
    private void setSwatch(Button b, int argb){
        argb = 0xFF000000 | argb;
        swColor.put(b, argb);
        b.setBackgroundTintList(ColorStateList.valueOf(argb));
        b.setTextColor(Theme.contrast(argb));
    }
    private int colorOf(Button b){ Integer c = swColor.get(b); return c!=null ? (c & 0xFFFFFF) : 0; }

    private void pickSwatch(final Button b){
        Integer cur = swColor.get(b);
        showColorPicker(cur!=null ? cur : 0xFF808080, new ColorCb(){ public void picked(int argb){
            setSwatch(b, argb);
            cfg.uiBg=colorOf(swBg); cfg.uiPanel=colorOf(swPanel); cfg.uiFg=colorOf(swText); cfg.uiAccent=colorOf(swAccent);
            cfg.theme = "custom";
            int ci = themeIds.length-1;                  // "custom" e o ultimo
            if(themeSpin.getSelectedItemPosition()!=ci) themeSpin.setSelection(ci);  // dispara onThemePicked
            else { liveApply(); refreshSwatches(); }
        }});
    }

    private interface ColorCb { void picked(int argb); }
    // seletor de cor simples (R/G/B) — sem libs externas
    private void showColorPicker(int init, final ColorCb cb){
        final int[] rgb = { Color.red(init), Color.green(init), Color.blue(init) };
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad,pad,pad,pad);
        final View preview = new View(this);
        LinearLayout.LayoutParams pv = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        pv.bottomMargin = dp(12);
        preview.setLayoutParams(pv);
        preview.setBackgroundColor(0xFF000000 | (rgb[0]<<16)|(rgb[1]<<8)|rgb[2]);
        box.addView(preview);
        final String[] names = {"R","G","B"};
        final SeekBar[] bars = new SeekBar[3];
        for(int i=0;i<3;i++){
            final int idx=i;
            TextView t = new TextView(this); t.setText(names[i]); box.addView(t);
            SeekBar s = new SeekBar(this); s.setMax(255); s.setProgress(rgb[i]); box.addView(s);
            bars[i]=s;
            s.setOnSeekBarChangeListener(new Simple(){ public void onProgressChanged(SeekBar sb,int p,boolean u){
                rgb[idx]=p; preview.setBackgroundColor(0xFF000000 | (rgb[0]<<16)|(rgb[1]<<8)|rgb[2]);
            }});
        }
        new AlertDialog.Builder(this)
            .setTitle(Lang.t("ap.accent").equals("")?"":Lang.t("m.appearance"))
            .setView(box)
            .setPositiveButton("OK", (d,w) -> cb.picked(0xFF000000 | (rgb[0]<<16)|(rgb[1]<<8)|rgb[2]))
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }
    private int dp(int v){ return Math.round(v * getResources().getDisplayMetrics().density); }

    private void updateGamma(int p){ double g=0.40+p/100.0; gammaLbl.setText(Lang.t("lbl.gamma")+" "+String.format("%.2f", g)); }
    private void updateFps(int p){ int f=1+p; fpsLbl.setText(Lang.t("lbl.fps")+" "+f); }
    private void updateScreen(int p){ int pct=50+p; screenLbl.setText(Lang.t("lbl.screen")+" "+pct+"%"); }
    private void updateFont(int p){ double s=1.0+p/100.0; fontLbl.setText(Lang.t("lbl.fontsize")+" "+String.format("%.2f", s)+"x"); }
    private void updateHd(int p){ int s=1+p; hdLbl.setText(Lang.t("lbl.hd")+" "+s+"x"+(s==1?" ("+Lang.t("hd.off")+")":"")); }

    @Override public boolean onSupportNavigateUp(){ finish(); return true; }

    @Override protected void onPause(){
        super.onPause();
        cfg.sound = soundSw.isChecked();
        cfg.reverb = reverbSw.isChecked();
        cfg.deviceModel = deviceSp.getSelectedItemPosition();
        int[] r = PrefsConfig.resForModel(cfg.deviceModel);
        cfg.width = r[0]; cfg.height = r[1];
        cfg.gamma = 0.40 + gammaSb.getProgress()/100.0;
        cfg.targetFps = 1 + fpsSb.getProgress();
        cfg.screenTop = 0.50f + screenSb.getProgress()/100f;
        cfg.fontScale = 1.0 + fontSb.getProgress()/100.0;
        cfg.renderScale = 1 + hdSb.getProgress();
        cfg.save(this);
    }

    // listener-base pra não repetir os métodos vazios
    private static abstract class Simple implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar s){}
        public void onStopTrackingTouch(SeekBar s){}
    }
    private static abstract class SimpleSel implements AdapterView.OnItemSelectedListener {
        public void onNothingSelected(AdapterView<?> parent){}
    }
}
