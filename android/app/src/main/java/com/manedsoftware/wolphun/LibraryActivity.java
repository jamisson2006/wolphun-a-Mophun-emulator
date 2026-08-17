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
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new Adapter());

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ openPicker(); }
        });

        requestStoragePermission();
    }

    @Override protected void onResume(){ super.onResume(); reload(); }

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

    // ---- importar via seletor de arquivos (SAF) ----
    private void openPicker(){
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");                       // .mpn não tem MIME padrão -> qualquer arquivo
        try { startActivityForResult(i, REQ_PICK); }
        catch(Throwable t){ toast("Sem app de arquivos disponível"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data){
        super.onActivityResult(req, res, data);
        if(req==REQ_PICK && res==RESULT_OK && data!=null && data.getData()!=null)
            importRom(data.getData());
    }

    private void importRom(final Uri uri){
        final String display = queryName(uri);
        new Thread(new Runnable(){ public void run(){
            String msg;
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                byte[] bytes = MophunCrypt.readAll(in);
                in.close();
                boolean decrypted = false;
                if(MophunCrypt.looksEncrypted(bytes)){
                    byte[] dec = MophunCrypt.decrypt(bytes);
                    if(dec!=null){ bytes = dec; decrypted = true; }
                }
                String base = stripExt(display==null ? "jogo" : display);
                File out = uniqueFile(PrefsConfig.romsDir(LibraryActivity.this), base);
                MophunCrypt.writeAll(out, bytes);
                msg = decrypted ? ("Importado e descriptografado: "+base) : ("Importado: "+base);
            } catch(Throwable t){
                msg = "Falha ao importar: "+t;
            }
            final String fmsg = msg;
            runOnUiThread(new Runnable(){ public void run(){ toast(fmsg); reload(); }});
        }}, "import-rom").start();
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
            + Lang.t("lbl.version") + " 0.1.0 beta — " + Lang.t("about.firstbeta") + "\n\n"
            + Lang.t("about.developer") + ": Jamisson Tavares\n"
            + Lang.t("about.studio") + ": Maned Software\n\n"
            + "© 2026 · youtube.com/@jamissontavares7671";
        new android.app.AlertDialog.Builder(this)
            .setTitle("wolphun emulator")
            .setIcon(R.drawable.wolphun_logo)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show();
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
            h.itemView.setOnClickListener(new View.OnClickListener(){
                public void onClick(View v){ launch(g); }
            });
        }
        @Override public int getItemCount(){ return games.size(); }
    }
    static final class VH extends RecyclerView.ViewHolder {
        final TextView title, subtitle;
        VH(View v){ super(v); title=v.findViewById(R.id.title); subtitle=v.findViewById(R.id.subtitle); }
    }
}
