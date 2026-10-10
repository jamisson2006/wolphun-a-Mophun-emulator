package com.manedsoftware.wolphun;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Biblioteca de jogos INSTALADOS (estilo J2ME-Loader): lista os .mpn já
 * importados. O botão flutuante "+" abre o seletor de arquivos (SAF); a ROM é
 * copiada para a biblioteca do app e, se estiver CIFRADA (DRM SE), é
 * descriptografada automaticamente na importação.
 */
public final class LibraryActivity extends AppCompatActivity {
    private static final int REQ_PICK = 1001;
    private final List<GameEntry> games = new ArrayList<GameEntry>();
    private RecyclerView list;
    private TextView empty;
    private PrefsConfig cfg;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        cfg = PrefsConfig.load(this);
        Lang.setByCode(cfg.language);
        setContentView(R.layout.activity_library);
        list  = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        empty.setText(Lang.t("lib.empty") + getExternalFilesDir(null) + "/roms/");   // i18n + caminho real

        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new Adapter());

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setContentDescription(Lang.t("lbl.addrom"));
        fab.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ openPicker(); }
        });
        Theme.apply(this, cfg);

        requestStoragePermission();
    }

    @Override protected void onResume(){
        super.onResume();
        cfg = PrefsConfig.load(this);        // recarrega (idioma/tema podem ter mudado nas Settings)
        Lang.setByCode(cfg.language);
        reload();
        Theme.apply(this, cfg);
    }

    // ---- permissão de armazenamento (a caixa "Permitir") p/ ler /sdcard/mophun ----
    private static final int REQ_PERM = 2002;
    private void requestStoragePermission(){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,
                new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_PERM);
        }
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res){
        super.onRequestPermissionsResult(req, perms, res);
        if(req==REQ_PERM) reload();   // com permissão, re-escaneia a pasta compartilhada
    }

    // ---- importar via seletor de arquivos (SAF) — MULTIPLA selecao (bulk installer) ----
    private void openPicker(){
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");                       // .mpn não tem MIME padrão -> qualquer arquivo
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);   // permite selecionar VARIAS ROMs de uma vez
        try { startActivityForResult(i, REQ_PICK); }
        catch(Throwable t){ toast(Lang.t("imp.nofileapp")); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data){
        super.onActivityResult(req, res, data);
        if(req!=REQ_PICK || res!=RESULT_OK || data==null) return;
        final List<Uri> uris = new ArrayList<Uri>();
        if(data.getClipData()!=null){                    // selecao multipla
            android.content.ClipData cd = data.getClipData();
            for(int i=0;i<cd.getItemCount();i++){ Uri u=cd.getItemAt(i).getUri(); if(u!=null) uris.add(u); }
        } else if(data.getData()!=null){                 // selecao unica
            uris.add(data.getData());
        }
        if(!uris.isEmpty()) importRoms(uris);
    }

    private void importRoms(final List<Uri> uris){
        new Thread(new Runnable(){ public void run(){
            int ok=0, fail=0;
            for(Uri uri : uris){
                try {
                    String display = queryName(uri);
                    InputStream in = getContentResolver().openInputStream(uri);
                    byte[] bytes = MophunCrypt.readAll(in);
                    in.close();
                    if(MophunCrypt.looksEncrypted(bytes)){
                        byte[] dec = MophunCrypt.decrypt(bytes);
                        if(dec!=null){ bytes = dec; }
                    }
                    String base = stripExt(display==null ? "game" : display);
                    File out = uniqueFile(PrefsConfig.romsDir(LibraryActivity.this), base);
                    MophunCrypt.writeAll(out, bytes);
                    ok++;
                } catch(Throwable t){ fail++; }
            }
            final int fok=ok, ffail=fail;
            runOnUiThread(new Runnable(){ public void run(){
                toast(ffail==0 ? Lang.t("imp.bulkok", fok) : Lang.t("imp.bulkpart", fok, ffail));
                reload();
            }});
        }}, "import-roms").start();
    }

    private static File uniqueFile(File dir, String base){
        File f = new File(dir, base+".mpn");
        int n=1;
        while(f.exists()){ f = new File(dir, base+"_"+(n++)+".mpn"); }
        return f;
    }

    private String queryName(Uri uri){
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if(c!=null && c.moveToFirst()){
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(idx>=0){ String n=c.getString(idx); if(n!=null) return n; }
            }
        } catch(Throwable t){ /* ignora */ }
        finally { if(c!=null) c.close(); }
        return uri.getLastPathSegment();
    }

    // ---- listagem ----
    private void reload(){
        games.clear();
        scan(PrefsConfig.romsDir(this), games);                 // pasta do app
        File shared = PrefsConfig.sharedRomsDir();               // pasta persistente /sdcard/mophun
        if(shared.isDirectory()) scan(shared, games);
        // remove duplicados (mesmo nome), mantendo o primeiro
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for(java.util.Iterator<GameEntry> it=games.iterator(); it.hasNext();)
            if(!seen.add(it.next().name.toLowerCase())) it.remove();
        Collections.sort(games, new Comparator<GameEntry>(){
            public int compare(GameEntry a, GameEntry b){ return a.name.compareToIgnoreCase(b.name); }
        });
        empty.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(games.isEmpty() ? View.GONE : View.VISIBLE);
        if(list.getAdapter()!=null) list.getAdapter().notifyDataSetChanged();
    }

    private void scan(File dir, List<GameEntry> out){
        File[] fs = dir.listFiles();
        if(fs==null) return;
        for(File f: fs){
            if(f.isDirectory()) scan(f, out);
            else if(f.getName().toLowerCase().endsWith(".mpn"))
                out.add(new GameEntry(stripExt(f.getName()), f));
        }
    }
    static String stripExt(String n){ int i=n.lastIndexOf('.'); return i>0? n.substring(0,i) : n; }

    // toque longo -> menu do jogo (resolucao / apagar dados / desinstalar)
    private void showGameMenu(final GameEntry g){
        final String[] items = { Lang.t("gm.resolution"), Lang.t("gm.cleardata"), Lang.t("uninst.ok") };
        new android.app.AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(items, (d, which) -> {
                switch(which){
                    case 0: chooseResolution(g); break;
                    case 1: confirmClearData(g); break;
                    case 2: confirmUninstall(g); break;
                }
            })
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }

    // muda a resolucao (modelo de aparelho) SO deste jogo; "Auto" = detecta pelo nome do arquivo
    private void chooseResolution(final GameEntry g){
        final String[] items = new String[PrefsConfig.MODEL_NAMES.length + 1];
        items[0] = Lang.t("res.auto");
        for(int i=0;i<PrefsConfig.MODEL_NAMES.length;i++) items[i+1] = PrefsConfig.MODEL_NAMES[i];
        int cur = PrefsConfig.getGameModel(this, g.name) + 1;   // -1(auto)->0 ; modelo m -> m+1
        if(cur<0 || cur>=items.length) cur=0;
        new android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("gm.resolution"))
            .setSingleChoiceItems(items, cur, (d, which) -> {
                PrefsConfig.setGameModel(this, g.name, which-1);   // 0->auto(-1) ; i -> i-1
                d.dismiss();
                toast(Lang.t("res.set"));
            })
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }

    // apaga SO os dados (saves/estados) do jogo, mantendo a ROM
    private void confirmClearData(final GameEntry g){
        new android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("gm.cleardata"))
            .setMessage(Lang.t("cleardata.msg", g.name))
            .setPositiveButton(Lang.t("gm.cleardata"), (d, w) -> {
                try { deleteDir(PrefsConfig.savesDir(this, g.name)); } catch(Throwable t){}
                toast(Lang.t("cleardata.done", g.name));
            })
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }

    // toque longo -> desinstalar (apaga o .mpn) com confirmacao
    private void confirmUninstall(final GameEntry g){
        new android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("uninst.title"))
            .setMessage(Lang.t("uninst.msg", g.name))
            .setPositiveButton(Lang.t("uninst.ok"), (d, w) -> uninstall(g))
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }
    private void uninstall(GameEntry g){
        boolean ok = false;
        try { ok = g.file.delete(); } catch(Throwable t){ ok = false; }
        if(ok){
            // remove tambem a pasta de saves/estados + a miniatura (desinstalacao completa)
            try { deleteDir(PrefsConfig.savesDir(this, g.name)); } catch(Throwable t){}
            try { PrefsConfig.thumbFile(this, g.name).delete(); } catch(Throwable t){}
            toast(Lang.t("uninst.done", g.name));
        } else {
            toast(Lang.t("uninst.fail"));   // ex.: ROM na pasta compartilhada sem permissao de escrita
        }
        reload();
    }
    private static void deleteDir(File d){
        if(d==null || !d.exists()) return;
        File[] fs = d.listFiles();
        if(fs!=null) for(File f: fs){ if(f.isDirectory()) deleteDir(f); else f.delete(); }
        d.delete();
    }

    private void launch(GameEntry g){
        Intent i = new Intent(this, EmulatorActivity.class);
        i.putExtra(EmulatorActivity.EXTRA_PATH, g.file.getAbsolutePath());
        i.putExtra(EmulatorActivity.EXTRA_NAME, g.name);
        startActivity(i);
    }

    private void toast(String s){ Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public boolean onCreateOptionsMenu(Menu m){
        getMenuInflater().inflate(R.menu.library_menu, m);
        m.findItem(R.id.action_settings).setTitle(Lang.t("menu.settings"));
        m.findItem(R.id.action_language).setTitle(Lang.t("menu.language"));
        m.findItem(R.id.action_about).setTitle(Lang.t("m.about"));
        return true;
    }
    @Override public boolean onOptionsItemSelected(MenuItem item){
        int id = item.getItemId();
        if(id==R.id.action_settings){ startActivity(new Intent(this, SettingsActivity.class)); return true; }
        if(id==R.id.action_language){ showLanguagePicker(); return true; }
        if(id==R.id.action_about){ showAbout(); return true; }
        return super.onOptionsItemSelected(item);
    }
    private void showAbout(){
        String msg = Lang.t("about.tagline") + "\n\n"
            // le a versao do build.gradle (BuildConfig). Estava "0.1.0" CHUMBADO aqui e ficou para
            // tras quando o app foi p/ 0.2.0 — a tela "Sobre" mentia a versao. Agora so ha uma fonte.
            + Lang.t("lbl.version") + " " + BuildConfig.VERSION_NAME + " — " + Lang.t("about.firstbeta") + "\n\n"
            + Lang.t("about.developer") + ": " + Branding.AUTHOR + "\n"
            + Lang.t("about.studio") + ": " + Branding.VENDOR + "\n\n"
            + "© " + Branding.YEAR + " · " + Branding.URL;
        // Titulo PROPRIO em vez de setTitle+setIcon, para levar o logo ao lado do nome.
        // O corpo deixou de ser setMessage(String) e virou uma View: o bloco de apoio tem
        // imagem e link clicavel, e nenhum dos dois cabe num texto simples.
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setCustomTitle(tituloSobre())
            .setView(corpoSobre(msg))
            .setPositiveButton("OK", null)
            .create();
        dlg.show();
    }

    /** Corpo do "Sobre": o texto de sempre mais o bloco de apoio ao projeto (QR do Ko-fi
     *  e o link). Vai dentro de um ScrollView porque em tela pequena, com o idioma russo
     *  e o QR, o conteudo passa da altura do dialogo -- sem rolagem o botao OK sai da tela. */
    private View corpoSobre(String msg){
        final float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * dp);

        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(pad, pad / 2, pad, 0);

        TextView texto = new TextView(this);
        texto.setText(msg);
        texto.setTextIsSelectable(true);
        col.addView(texto);

        TextView apoio = new TextView(this);
        apoio.setText(Lang.t("about.support"));
        apoio.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        apoio.setPadding(0, Math.round(18 * dp), 0, Math.round(8 * dp));
        apoio.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        col.addView(apoio);

        // 180dp: tamanho confortavel para OUTRO celular ler o codigo da tela deste.
        int sz = Math.round(180 * dp);
        android.widget.ImageView qr = new android.widget.ImageView(this);
        qr.setImageResource(R.drawable.kofi_qr);
        // O PNG e o original do Ko-fi (820x820) e ja vem com fundo BRANCO OPACO -- o que
        // importa, porque um QR precisa de fundo claro para ser lido e o tema escuro
        // apagaria os modulos pretos se ele fosse transparente.
        android.widget.LinearLayout.LayoutParams lp =
            new android.widget.LinearLayout.LayoutParams(sz, sz);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        qr.setLayoutParams(lp);
        col.addView(qr);

        TextView dica = new TextView(this);
        dica.setText(Lang.t("about.scan"));
        dica.setTextSize(12);
        dica.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        dica.setPadding(0, Math.round(6 * dp), 0, 0);
        col.addView(dica);

        TextView link = new TextView(this);
        link.setText(Branding.KOFI);
        link.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        link.setPadding(0, Math.round(6 * dp), 0, Math.round(4 * dp));
        link.setTextColor(0xFF1F6FE8);
        link.getPaint().setUnderlineText(true);
        link.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                try {
                    startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(Branding.KOFI)));
                } catch(Throwable t){
                    // sem navegador instalado: o endereco continua na tela para copiar
                }
            }
        });
        col.addView(link);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        return sv;
    }

    /** Barra de titulo do "Sobre": logo do wolphun + nome. */
    private View tituloSobre(){
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout barra = new android.widget.LinearLayout(this);
        barra.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        barra.setGravity(android.view.Gravity.CENTER_VERTICAL);
        barra.setPadding(pad, pad, pad, pad/2);

        int sz = Math.round(44 * getResources().getDisplayMetrics().density);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.wolphun_logo);
        logo.setLayoutParams(new android.widget.LinearLayout.LayoutParams(sz, sz));
        barra.addView(logo);

        TextView t = new TextView(this);
        t.setText("wolphun emulator");
        t.setTextSize(20);
        t.setPadding(pad, 0, 0, 0);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        barra.addView(t);
        return barra;
    }
    private void showLanguagePicker(){
        int cur = 0;
        for(int i=0;i<Lang.CODES.length;i++) if(Lang.CODES[i].equals(cfg.language)) cur=i;
        new android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("menu.language"))
            .setSingleChoiceItems(Lang.NAMES, cur, (d, which) -> {
                cfg.language = Lang.CODES[which];
                Lang.setByCode(cfg.language);
                cfg.save(this);
                d.dismiss();
                recreate();                 // aplica o novo idioma na hora
            })
            .setNegativeButton(Lang.t("btn.cancel"), null)
            .show();
    }

    // ---- adapter ----
    private final class Adapter extends RecyclerView.Adapter<VH> {
        @Override public VH onCreateViewHolder(ViewGroup parent, int type){
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_game, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos){
            final GameEntry g = games.get(pos);
            h.title.setText(g.name);
            int model = PrefsConfig.detectModel(g.file.getName());
            String tag = model>=0 ? PrefsConfig.MODEL_NAMES[model] : "multiscreen";
            h.subtitle.setText(tag);
            // icone = miniatura do gameplay (capturada ao jogar), senao o logo padrao
            android.graphics.Bitmap thumb = null;
            try { File tf = PrefsConfig.thumbFile(LibraryActivity.this, g.name);
                  if(tf.isFile()) thumb = android.graphics.BitmapFactory.decodeFile(tf.getAbsolutePath()); } catch(Throwable t){}
            if(thumb!=null) h.icon.setImageBitmap(thumb); else h.icon.setImageResource(R.drawable.wolphun_logo);
            // cores/fonte do tema nos itens da lista
            Theme t = Theme.resolve(cfg);
            android.graphics.Typeface tf = Theme.typeface(t.font);
            h.title.setTextColor(t.fg); h.title.setTypeface(tf, android.graphics.Typeface.BOLD);
            h.subtitle.setTextColor((t.fg & 0x00FFFFFF) | 0x99000000); h.subtitle.setTypeface(tf);
            h.itemView.setOnClickListener(new View.OnClickListener(){
                public void onClick(View v){ launch(g); }
            });
            h.itemView.setOnLongClickListener(new View.OnLongClickListener(){
                public boolean onLongClick(View v){ showGameMenu(g); return true; }
            });
        }
        @Override public int getItemCount(){ return games.size(); }
    }
    static final class VH extends RecyclerView.ViewHolder {
        final TextView title, subtitle;
        final android.widget.ImageView icon;
        VH(View v){ super(v); title=v.findViewById(R.id.title); subtitle=v.findViewById(R.id.subtitle); icon=v.findViewById(R.id.icon); }
    }
}
