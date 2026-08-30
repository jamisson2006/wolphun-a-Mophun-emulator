package com.manedsoftware.wolphun;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.content.res.ColorStateList;
import androidx.appcompat.app.AppCompatActivity;
import java.util.WeakHashMap;

/**
 * Tema visual do launcher Android (equivalente ao Theme.java do desktop): cores
 * (fundo, painel, texto, destaque) + fonte + escala do texto. Aplica pintando as
 * Views programaticamente (Android nao tem UIManager). Cada tema tem um 'id' estavel
 * salvo em PrefsConfig; o nome exibido e localizado via Lang ("theme.<id>").
 */
public final class Theme {
    public final String id;
    public final int bg, panel, fg, accent;   // ARGB (alpha 0xFF)
    public final String font;                  // "SansSerif" | "Serif" | "Monospaced"

    public Theme(String id, int bg, int panel, int fg, int accent, String font){
        this.id=id; this.bg=0xFF000000|bg; this.panel=0xFF000000|panel;
        this.fg=0xFF000000|fg; this.accent=0xFF000000|accent; this.font=font;
    }

    // presets prontos (id, fundo, painel, texto, destaque, fonte) — iguais ao desktop.
    // "system" aqui e um tema CLARO nativo (o app base e Light) p/ o preview funcionar.
    public static final Theme[] PRESETS = {
        new Theme("system",  0xFAFAFA, 0xFFFFFF, 0x1B1D21, 0xE8451F, "SansSerif"),
        new Theme("wolphun", 0x232228, 0x2E2C34, 0xF0EDE6, 0xE8451F, "SansSerif"),
        new Theme("dark",    0x1B1B1D, 0x262629, 0xE6E6E6, 0x4C8DFF, "SansSerif"),
        new Theme("light",   0xF4F5F7, 0xFFFFFF, 0x1B1D21, 0x2563EB, "SansSerif"),
        new Theme("amber",   0x1A1305, 0x241A08, 0xFFC24D, 0xFF8A00, "Monospaced"),
        new Theme("crt",     0x04140A, 0x08210F, 0x55FF88, 0x17C964, "Monospaced"),
        new Theme("ocean",   0x0C1E2E, 0x143049, 0xD3EBFA, 0x2BA6DE, "SansSerif"),
        new Theme("purple",  0x150A20, 0x221033, 0xEBD9FF, 0xA855F7, "SansSerif"),
    };

    /** ids na ordem de exibicao (system, presets..., custom). */
    public static String[] ids(){
        String[] n = new String[PRESETS.length+1];
        for(int i=0;i<PRESETS.length;i++) n[i]=PRESETS[i].id;
        n[n.length-1]="custom";
        return n;
    }

    public static Theme custom(PrefsConfig c){
        return new Theme("custom", c.uiBg, c.uiPanel, c.uiFg, c.uiAccent, c.uiFont);
    }
    public static Theme resolve(PrefsConfig c){ return byId(c.theme, c); }
    public static Theme byId(String id, PrefsConfig c){
        if("custom".equals(id)) return custom(c);
        for(Theme t: PRESETS) if(t.id.equals(id)) return t;
        return PRESETS[0];   // system
    }

    /** cor de texto legivel (preto/branco) sobre uma cor. */
    public static int contrast(int argb){
        int r=Color.red(argb), g=Color.green(argb), b=Color.blue(argb);
        double l = 0.299*r + 0.587*g + 0.114*b;
        return l>140 ? 0xFF101010 : 0xFFFFFFFF;
    }
    public static Typeface typeface(String font){
        if("Serif".equals(font)) return Typeface.SERIF;
        if("Monospaced".equals(font)) return Typeface.MONOSPACE;
        return Typeface.SANS_SERIF;
    }

    // guarda o tamanho ORIGINAL (px) de cada TextView p/ a escala nao acumular a cada apply
    private static final WeakHashMap<TextView,Float> BASE = new WeakHashMap<TextView,Float>();

    /** aplica o tema (cores+fonte+escala) a uma Activity inteira (arvore de Views + ActionBar). */
    public static void apply(Activity a, PrefsConfig cfg){
        Theme t = resolve(cfg);
        Typeface tf = typeface(t.font);
        float scale = cfg.uiFontScale;
        View root = a.findViewById(android.R.id.content);
        if(root!=null){ walk(root, t, tf, scale); root.setBackgroundColor(t.bg); }
        if(a instanceof AppCompatActivity){
            androidx.appcompat.app.ActionBar ab = ((AppCompatActivity)a).getSupportActionBar();
            if(ab!=null) ab.setBackgroundDrawable(new ColorDrawable(t.accent));   // barra na cor de destaque
        }
    }

    static void walk(View v, Theme t, Typeface tf, float scale){
        // swatches de cor (Aparencia): tem cor propria — nao re-tematizar
        if("themeSwatch".equals(v.getTag())) return;
        if(v instanceof TextView){
            TextView tv=(TextView)v;
            tv.setTextColor(t.fg);
            tv.setTypeface(tf);
            Float base = BASE.get(tv);
            if(base==null){ base=tv.getTextSize(); BASE.put(tv, base); }   // px original
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base*scale);
        }
        if(v instanceof SeekBar && Build.VERSION.SDK_INT>=21){
            ColorStateList acc = ColorStateList.valueOf(t.accent);
            ((SeekBar)v).setProgressTintList(acc);
            ((SeekBar)v).setThumbTintList(acc);
        }
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) walk(g.getChildAt(i), t, tf, scale);
        }
    }
}
