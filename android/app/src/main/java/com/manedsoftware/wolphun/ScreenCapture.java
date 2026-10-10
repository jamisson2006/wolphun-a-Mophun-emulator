package com.manedsoftware.wolphun;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Gravacao das capturas de tela em PNG.
 *
 * A imagem chega aqui JA no tamanho em pixels do aparelho (quem captura e o
 * EmulatorView, a partir da superficie) — aqui so se escolhe ONDE gravar, e isso
 * depende da versao do Android:
 *
 *   API >= 29 : MediaStore (Pictures/wolphun). Nao precisa de permissao nenhuma e a
 *               foto aparece na Galeria na hora.
 *   API 24-28 : nao existe RELATIVE_PATH; grava o arquivo em
 *               /sdcard/Pictures/wolphun e avisa o scanner de midia. Precisa de
 *               WRITE_EXTERNAL_STORAGE (pedida na hora do 1o screenshot).
 *   Sem permissao / cartao indisponivel: cai na pasta privada do app
 *               (Android/data/<pkg>/files/Pictures/wolphun) — nao aparece na Galeria,
 *               mas o screenshot NAO se perde.
 */
public final class ScreenCapture {
    private ScreenCapture(){}

    /** subpasta usada dentro de Pictures/ (e dentro da pasta privada no modo de reserva). */
    public static final String DIR = "wolphun";

    /** true se ainda falta a permissao de escrita (so nos Android antigos). */
    public static boolean needsPermission(Context ctx){
        return Build.VERSION.SDK_INT < 29
            && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
               != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** <jogo>_AAAAMMDD_HHMMSS.png, sem caracteres que o sistema de arquivos recuse. */
    public static String fileName(String gameName){
        String safe = (gameName==null || gameName.length()==0) ? "wolphun" : gameName;
        if(safe.toLowerCase(Locale.US).endsWith(".mpn")) safe = safe.substring(0, safe.length()-4);
        safe = safe.replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("_+", "_");
        if(safe.length() > 48) safe = safe.substring(0, 48);
        if(safe.length() == 0) safe = "wolphun";
        return safe + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
    }

    /**
     * Grava o PNG. Devolve um caminho curto para mostrar ao usuario, ou null se falhou.
     * Pode demorar (compressao): NAO chamar na thread de UI nem na de render.
     */
    public static String save(Context ctx, Bitmap bmp, String gameName){
        if(ctx==null || bmp==null || bmp.isRecycled()) return null;
        final String name = fileName(gameName);
        if(Build.VERSION.SDK_INT >= 29){
            String r = saveMediaStore(ctx, bmp, name);
            if(r != null) return r;            // falhou? ainda tenta o arquivo direto
        }
        return saveFile(ctx, bmp, name);
    }

    // ---- API 29+: a Galeria cuida do arquivo; nenhuma permissao necessaria ----
    private static String saveMediaStore(Context ctx, Bitmap bmp, String name){
        Uri uri = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + DIR);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);        // invisivel enquanto grava
            uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if(uri == null) return null;

            boolean ok;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if(os == null){ ctx.getContentResolver().delete(uri, null, null); return null; }
            try { ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, os); }
            finally { os.close(); }
            if(!ok){ ctx.getContentResolver().delete(uri, null, null); return null; }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);     // agora aparece na Galeria
            ctx.getContentResolver().update(uri, done, null, null);
            return Environment.DIRECTORY_PICTURES + "/" + DIR + "/" + name;
        } catch(Throwable t){
            if(uri != null) try { ctx.getContentResolver().delete(uri, null, null); } catch(Throwable ignore){}
            return null;
        }
    }

    // ---- API 24-28, ou reserva: arquivo direto ----
    private static String saveFile(Context ctx, Bitmap bmp, String name){
        File dir = publicDir(ctx);
        boolean publica = dir != null;
        if(dir == null) dir = privateDir(ctx);
        if(dir == null) return null;
        if(!dir.exists() && !dir.mkdirs()) return null;

        File f = new File(dir, name);
        try {
            FileOutputStream fos = new FileOutputStream(f);
            boolean ok;
            try { ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, fos); }
            finally { fos.close(); }
            if(!ok){ f.delete(); return null; }
        } catch(Throwable t){ return null; }

        if(publica){
            // sem isso a foto so aparece na Galeria depois de reiniciar o aparelho
            try { MediaScannerConnection.scanFile(ctx, new String[]{ f.getAbsolutePath() },
                                                 new String[]{ "image/png" }, null); }
            catch(Throwable ignore){}
        }
        return f.getAbsolutePath();
    }

    /** /sdcard/Pictures/wolphun — so se der para escrever ali. */
    private static File publicDir(Context ctx){
        if(needsPermission(ctx)) return null;
        if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return null;
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIR);
    }

    /** Android/data/<pkg>/files/Pictures/wolphun — sempre gravavel, nunca na Galeria. */
    private static File privateDir(Context ctx){
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if(base == null) base = ctx.getFilesDir();
        if(base == null) return null;
        return new File(base, DIR);
    }
}
