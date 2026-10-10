package mophun;

import javax.swing.*;
import java.awt.*;

/**
 * Tema visual do launcher: cores (fundo, painel, texto, destaque) + fonte.
 * Aplica APENAS cores e fontes, mantendo o Look&Feel nativo do SO (mudanca
 * discreta, sem repintar botoes/estilos). Cada tema tem um 'id' estavel salvo
 * no config; o nome exibido e localizado via Lang ("theme.<id>").
 *
 * "system" = sem tema (look nativo, vale apos reiniciar). "custom" = cores/fonte
 * escolhidas pelo usuario (Config.uiBg/uiPanel/uiFg/uiAccent/uiFont).
 */
public final class Theme {
    public final String id;
    public final Color bg, panel, fg, accent;
    public final String font;

    public Theme(String id, int bg, int panel, int fg, int accent, String font){
        this.id=id; this.bg=new Color(bg); this.panel=new Color(panel);
        this.fg=new Color(fg); this.accent=new Color(accent); this.font=font;
    }

    // presets prontos (id, fundo, painel, texto, destaque, fonte)
    public static final Theme[] PRESETS = {
        new Theme("wolphun", 0x232228, 0x2E2C34, 0xF0EDE6, 0xE8451F, "SansSerif"),
        new Theme("dark",    0x1B1B1D, 0x262629, 0xE6E6E6, 0x4C8DFF, "SansSerif"),
        new Theme("light",   0xF4F5F7, 0xFFFFFF, 0x1B1D21, 0x2563EB, "SansSerif"),
        new Theme("amber",   0x1A1305, 0x241A08, 0xFFC24D, 0xFF8A00, "Monospaced"),
        new Theme("crt",     0x04140A, 0x08210F, 0x55FF88, 0x17C964, "Monospaced"),
        new Theme("ocean",   0x0C1E2E, 0x143049, 0xD3EBFA, 0x2BA6DE, "SansSerif"),
        new Theme("purple",  0x150A20, 0x221033, 0xEBD9FF, 0xA855F7, "SansSerif"),
    };

    /** ids na ordem de exibicao: system, presets..., custom. */
    public static String[] ids(){
        String[] n = new String[PRESETS.length+2];
        n[0]="system";
        for(int i=0;i<PRESETS.length;i++) n[i+1]=PRESETS[i].id;
        n[n.length-1]="custom";
        return n;
    }

    public static Theme custom(Config c){
        return new Theme("custom", c.uiBg, c.uiPanel, c.uiFg, c.uiAccent, c.uiFont);
    }
    /** resolve o Theme do config; null = "system" (sem tema). */
    public static Theme resolve(Config c){
        if(c.theme==null || "system".equals(c.theme)) return null;
        if("custom".equals(c.theme)) return custom(c);
        for(Theme t: PRESETS) if(t.id.equals(c.theme)) return t;
        return null;
    }
    public static Theme byId(String id, Config c){
        if(id==null || "system".equals(id)) return null;
        if("custom".equals(id)) return custom(c);
        for(Theme t: PRESETS) if(t.id.equals(id)) return t;
        return null;
    }

    /** luminancia relativa (WCAG) de uma cor, 0..1 */
    static double lum(Color c){
        double[] v = new double[3];
        int[] ch = { c.getRed(), c.getGreen(), c.getBlue() };
        for(int i=0;i<3;i++){
            double x = ch[i]/255.0;
            v[i] = (x <= 0.03928) ? x/12.92 : Math.pow((x+0.055)/1.055, 2.4);
        }
        return 0.2126*v[0] + 0.7152*v[1] + 0.0722*v[2];
    }
    /** razao de contraste entre duas cores (WCAG): 1 = igual, 21 = preto sobre branco */
    public static double contrastRatio(Color a, Color b){
        double la = lum(a), lb = lum(b);
        return (Math.max(la,lb) + 0.05) / (Math.min(la,lb) + 0.05);
    }
    /**
     * Devolve 'fg' se ele for legivel sobre 'bg'; senao escurece/clareia ate passar de 4.5:1 (o
     * minimo da WCAG p/ texto). Existe porque o Look&Feel do Windows pinta alguns paineis (o
     * conteudo do seletor de arquivos, principalmente) com fundo BRANCO proprio, ignorando as
     * chaves de cor do tema -- e ai um texto azul-claro como o do tema Ocean fica ilegivel.
     */
    /** true = respeita a cor escolhida ao pe da letra, mesmo ilegivel (opcao do usuario). */
    public static boolean exactColors = false;

    public static Color readable(Color fg, Color bg){
        if(exactColors) return fg;
        if(contrastRatio(fg, bg) >= 4.5) return fg;
        boolean escurecer = lum(bg) > 0.35;              // fundo claro -> texto mais escuro
        Color melhor = fg;
        for(int i=1;i<=20;i++){
            float f = i/20f;
            Color alvo = escurecer ? Color.BLACK : Color.WHITE;
            melhor = new Color(
                Math.round(fg.getRed()   + (alvo.getRed()  -fg.getRed())  *f),
                Math.round(fg.getGreen() + (alvo.getGreen()-fg.getGreen())*f),
                Math.round(fg.getBlue()  + (alvo.getBlue() -fg.getBlue()) *f));
            if(contrastRatio(melhor, bg) >= 4.5) return melhor;
        }
        return melhor;
    }

    /** cor de texto legivel (preto/branco) sobre uma cor de fundo. */
    public static Color contrast(Color c){
        double l = 0.299*c.getRed() + 0.587*c.getGreen() + 0.114*c.getBlue();
        return l>140 ? new Color(0x101010) : Color.WHITE;
    }

    // ---- aplicacao ----
    /** aplica o tema (cores+fonte) a TODAS as janelas abertas e aos dialogos futuros.
     *  IMPORTANTE: updateComponentTreeUI PRIMEIRO (reinstala o UI com os defaults novos),
     *  DEPOIS applyTree pinta os overrides explicitos por cima — senao o update apaga as cores.
     *  t==null ("system") reverte AO VIVO: limpa nossas chaves, reinstala o L&F nativo e
     *  zera as cores/fonte explicitas dos componentes (voltam ao padrao do SO). */
    public static void apply(Theme t, String fam, int size){
        if(t==null){
            clearUIManager();
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch(Exception e){}
            for(Window w : Window.getWindows()){
                revertTree(w);
                SwingUtilities.updateComponentTreeUI(w);
            }
            return;
        }
        setUIManager(t, fam, size);
        for(Window w : Window.getWindows()){
            SwingUtilities.updateComponentTreeUI(w);
            applyTree(w, t, fam, size);
        }
    }

    // nomes de todas as chaves de COR que setamos (p/ limpar no modo "system")
    static final String[] COLOR_KEYS = {
        "Panel.background","OptionPane.background","OptionPane.messageForeground",
        "Label.foreground","Label.background","List.background","List.foreground",
        "List.selectionBackground","List.selectionForeground","TextField.background",
        "TextField.foreground","TextField.caretForeground","TextArea.background","TextArea.foreground",
        "ComboBox.background","ComboBox.foreground","ComboBox.selectionBackground","ComboBox.selectionForeground",
        "MenuBar.background","MenuBar.foreground","Menu.background","Menu.foreground",
        "MenuItem.background","MenuItem.foreground","MenuItem.selectionBackground","MenuItem.selectionForeground",
        "CheckBoxMenuItem.background","CheckBoxMenuItem.foreground","RadioButtonMenuItem.background",
        "RadioButtonMenuItem.foreground","PopupMenu.background","Separator.foreground",
        "Button.background","Button.foreground","ScrollPane.background","Viewport.background",
        "Spinner.background","Spinner.foreground","ToolTip.background","ToolTip.foreground",
        "FileChooser.background","FileChooser.foreground","FileChooser.listViewBackground",
        "FileChooser.listViewForeground","FileView.background","FileView.foreground",
        "Tree.background","Tree.foreground","Tree.textBackground","Tree.textForeground",
        "Tree.selectionBackground","Tree.selectionForeground",
        "Table.background","Table.foreground","Table.selectionBackground","Table.selectionForeground",
        "Table.gridColor","TableHeader.background","TableHeader.foreground",
        "Panel.foreground","OptionPane.foreground","CheckBox.background","CheckBox.foreground",
        "RadioButton.background","RadioButton.foreground","ToggleButton.background",
        "ToggleButton.foreground","TitledBorder.titleColor","TabbedPane.background",
        "TabbedPane.foreground","ScrollBar.background","ComboBox.buttonBackground",
        "TextPane.background","TextPane.foreground","EditorPane.background","EditorPane.foreground",
    };
    static final String[] FONT_TYPES = {"Label","Button","ToggleButton","CheckBox","RadioButton","Menu","MenuItem",
        "CheckBoxMenuItem","RadioButtonMenuItem","MenuBar","List","ComboBox","TextField","TextArea",
        "Spinner","TabbedPane","OptionPane","Panel","ScrollPane","Viewport","ToolTip"};

    /**
     * Abre uma "janela" temporaria com as cores NATIVAS do sistema, para dialogos que o Look&Feel
     * pinta por conta propria. O caso concreto e o JFileChooser do Windows: ele mantem a barra de
     * cima, os atalhos da esquerda e os botoes CLAROS, ignorando as chaves de fundo do tema, mas
     * herda `Label.foreground` -- entao o nome das pastas saia em azul-claro sobre branco.
     * Tematizar so pela metade e pior que nao tematizar: aqui o seletor fica igual ao do sistema,
     * legivel com qualquer tema. As janelas ja abertas nao mudam (as cores delas sao explicitas).
     */
    public static void pushNative(){ clearUIManager(); }
    /** volta as chaves do tema depois do dialogo nativo. */
    public static void popTheme(Config c){
        Theme t = resolve(c);
        if(t!=null) setUIManager(t, c.uiFont, c.uiFontSize);
    }

    /** remove nossos overrides do UIManager (volta aos defaults do L&F). */
    public static void clearUIManager(){
        for(String k : COLOR_KEYS) UIManager.put(k, null);
        for(String k : FONT_TYPES) UIManager.put(k+".font", null);
    }
    /** zera cores/fonte explicitas de um componente (e filhos) p/ voltar ao padrao nativo. */
    static void revertTree(Component c){
        if(!(c instanceof JComponent && Boolean.TRUE.equals(((JComponent)c).getClientProperty("themeSwatch")))){
            c.setBackground(null); c.setForeground(null); c.setFont(null);
            // o opaque foi forcado por nos; o L&F nao o desfaz sozinho (installProperty respeita
            // o que a aplicacao setou), entao devolvemos o padrao: item transparente, barra opaca
            if(c instanceof JMenuItem || c instanceof JPopupMenu) ((JComponent)c).setOpaque(false);
            if(c instanceof JList){ ((JList<?>)c).setSelectionBackground(null); ((JList<?>)c).setSelectionForeground(null); }
        }
        if(c instanceof Container) for(Component ch : ((Container)c).getComponents()) revertTree(ch);
        if(c instanceof JMenu){
            revertTree(((JMenu)c).getPopupMenu());
            for(Component ch : ((JMenu)c).getMenuComponents()) revertTree(ch);
        }
        if(c instanceof JFrame){ JMenuBar mb=((JFrame)c).getJMenuBar(); if(mb!=null) revertTree(mb); }
    }

    /**
     * O Look&Feel do Windows pinta JButton / JComboBox / JCheckBox / JRadioButton com a cor de
     * controle do SISTEMA e simplesmente IGNORA o setBackground que pedimos. Resultado: o fundo
     * ficava claro e a letra do tema (azul-claro no Ocean) sumia em cima dele. Para esses
     * componentes o contraste tem de ser medido contra a cor que o L&F realmente usa.
     */
    static boolean lafPintaSozinho(){
        LookAndFeel l = UIManager.getLookAndFeel();
        return l!=null && "Windows".equals(l.getID());
    }
    /** cor de letra legivel para os componentes que o L&F pinta com o fundo do sistema */
    public static Color fgControle(Color fg){
        if(exactColors || !lafPintaSozinho()) return fg;
        Color ctrl = UIManager.getColor("control");
        return ctrl==null ? fg : readable(fg, ctrl);
    }

    /** chaves do UIManager -> os dialogos (JOptionPane/JFileChooser) novos ja nascem no tema. */
    public static void setUIManager(Theme t, String fam, int size){
        if(t==null) return;
        Font f = new Font(fam, Font.PLAIN, size);
        Color selFg = contrast(t.accent);
        Color fgCtl = fgControle(t.fg);        // p/ botoes/combos que o L&F pinta por conta propria
        Object[] kv = {
            "Panel.background", t.bg,
            "OptionPane.background", t.bg,
            "OptionPane.messageForeground", t.fg,
            "Label.foreground", t.fg,
            "Label.background", t.bg,
            "List.background", t.panel,
            "List.foreground", t.fg,
            "List.selectionBackground", t.accent,
            "List.selectionForeground", selFg,
            "TextField.background", t.panel,
            "TextField.foreground", t.fg,
            "TextField.caretForeground", t.fg,
            "TextArea.background", t.panel,
            "TextArea.foreground", t.fg,
            "ComboBox.background", t.panel,
            "ComboBox.foreground", fgCtl,
            "ComboBox.selectionBackground", t.accent,
            "ComboBox.selectionForeground", selFg,
            "MenuBar.background", t.bg,
            "MenuBar.foreground", t.fg,
            "Menu.background", t.bg,
            "Menu.foreground", t.fg,
            "MenuItem.background", t.bg,
            "MenuItem.foreground", t.fg,
            "MenuItem.selectionBackground", t.accent,
            "MenuItem.selectionForeground", selFg,
            "CheckBoxMenuItem.background", t.bg,
            "CheckBoxMenuItem.foreground", t.fg,
            "RadioButtonMenuItem.background", t.bg,
            "RadioButtonMenuItem.foreground", t.fg,
            "PopupMenu.background", t.bg,
            "Separator.foreground", t.panel,
            "Button.background", t.panel,
            "Button.foreground", fgCtl,
            "ScrollPane.background", t.bg,
            "Viewport.background", t.panel,
            "Spinner.background", t.panel,
            "Spinner.foreground", fgCtl,
            "ToolTip.background", t.panel,
            "ToolTip.foreground", t.fg,
            // --- SELETOR DE ARQUIVOS/PASTAS. Sem estas chaves o L&F do Windows mantinha o painel
            //     BRANCO e so herdava a cor da LETRA do tema: nome de pasta em azul-claro sobre
            //     branco, ilegivel. Agora fundo e letra andam juntos.
            "FileChooser.background", t.bg,
            "FileChooser.foreground", t.fg,
            "FileChooser.listViewBackground", t.panel,
            "FileChooser.listViewForeground", t.fg,
            "FileView.background", t.panel,
            "FileView.foreground", t.fg,
            "Tree.background", t.panel,
            "Tree.foreground", t.fg,
            "Tree.textBackground", t.panel,
            "Tree.textForeground", t.fg,
            "Tree.selectionBackground", t.accent,
            "Tree.selectionForeground", selFg,
            "Table.background", t.panel,
            "Table.foreground", t.fg,
            "Table.selectionBackground", t.accent,
            "Table.selectionForeground", selFg,
            "Table.gridColor", t.bg,
            "TableHeader.background", t.bg,
            "TableHeader.foreground", t.fg,
            // --- rotulos que passavam batido e ficavam com a cor do sistema
            "Panel.foreground", t.fg,
            "OptionPane.foreground", t.fg,
            "CheckBox.background", t.bg,
            "CheckBox.foreground", fgCtl,
            "RadioButton.background", t.bg,
            "RadioButton.foreground", fgCtl,
            "ToggleButton.background", t.panel,
            "ToggleButton.foreground", fgCtl,
            "TitledBorder.titleColor", t.fg,
            "TabbedPane.background", t.bg,
            "TabbedPane.foreground", t.fg,
            "ScrollBar.background", t.bg,
            "ComboBox.buttonBackground", t.panel,
            "TextPane.background", t.panel,
            "TextPane.foreground", t.fg,
            "EditorPane.background", t.panel,
            "EditorPane.foreground", t.fg,
        };
        for(int i=0;i<kv.length;i+=2) UIManager.put(kv[i], kv[i+1]);
        // fonte de todos os tipos de componente
        String[] fk = {"Label","Button","ToggleButton","CheckBox","RadioButton","Menu","MenuItem",
            "CheckBoxMenuItem","RadioButtonMenuItem","MenuBar","List","ComboBox","TextField","TextArea",
            "Spinner","TabbedPane","OptionPane","Panel","ScrollPane","Viewport","ToolTip"};
        for(String k: fk) UIManager.put(k+".font", f);
    }

    /** percorre a arvore de componentes e pinta cores/fonte (o L&F nativo ignora algumas chaves). */
    public static void applyTree(Component c, Theme t, String fam, int size){
        Font f = new Font(fam, Font.PLAIN, size);
        applyOne(c, t, f);
        if(c instanceof Container) for(Component ch : ((Container)c).getComponents()) applyTree(ch, t, fam, size);
        if(c instanceof JMenu){
            applyTree(((JMenu)c).getPopupMenu(), t, fam, size);      // o fundo do popup tambem
            for(Component ch : ((JMenu)c).getMenuComponents()) applyTree(ch, t, fam, size);
        }
        if(c instanceof JFrame){ JMenuBar mb=((JFrame)c).getJMenuBar(); if(mb!=null) applyTree(mb, t, fam, size); }
    }
    /**
     * Rede de seguranca contra fundo que nao e nosso: alguns paineis do L&F nativo (o conteudo do
     * JFileChooser em especial) se pintam de branco ignorando as chaves do tema. Se a letra do tema
     * nao for legivel sobre o fundo que o componente REALMENTE tem, usa um tom ajustado.
     */
    static void fgLegivel(Component c, Color fg){
        Color bg = c.getBackground();
        c.setForeground(bg==null ? fg : readable(fg, bg));
    }

    static void applyOne(Component c, Theme t, Font f){
        c.setFont(f);
        // swatches de cor (Aparencia) tem sua propria cor fixa — nao re-tematizar (senao some o texto/cor)
        if(c instanceof JComponent && Boolean.TRUE.equals(((JComponent)c).getClientProperty("themeSwatch"))) return;
        Color selFg = contrast(t.accent);
        if(c instanceof JLabel){
            fgLegivel(c, t.fg);
        } else if(c instanceof JList){
            c.setBackground(t.panel); fgLegivel(c, t.fg);
            ((JList<?>)c).setSelectionBackground(t.accent); ((JList<?>)c).setSelectionForeground(selFg);
        } else if(c instanceof JTextField || c instanceof JTextArea){
            c.setBackground(t.panel); fgLegivel(c, t.fg);
            if(c instanceof JTextField) ((JTextField)c).setCaretColor(t.fg);
        } else if(c instanceof JComboBox){
            c.setBackground(t.panel); c.setForeground(fgControle(t.fg));
        } else if(c instanceof JButton){
            c.setBackground(t.panel); c.setForeground(fgControle(t.fg));
        } else if(c instanceof JMenuBar || c instanceof JMenu || c instanceof JMenuItem
               || c instanceof JPopupMenu){
            // O L&F do Windows deixa os itens de menu NAO-OPACOS e pinta o popup de #F0F0F0 por
            // conta propria; so a cor da LETRA vinha do tema -> azul-claro sobre branco, ilegivel.
            // Forcando opaque o item passa a pintar o fundo do tema e o menu inteiro fica coerente.
            ((JComponent)c).setOpaque(true);
            c.setBackground(t.bg); c.setForeground(t.fg);
        } else if(c instanceof JTree){
            c.setBackground(t.panel); fgLegivel(c, t.fg);
        } else if(c instanceof JTable){
            c.setBackground(t.panel); c.setForeground(t.fg);
            ((JTable)c).setSelectionBackground(t.accent); ((JTable)c).setSelectionForeground(selFg);
            ((JTable)c).setGridColor(t.bg);
        } else if(c instanceof JCheckBox || c instanceof JRadioButton){
            c.setBackground(t.bg); c.setForeground(fgControle(t.fg));
        } else if(c instanceof JViewport){
            c.setBackground(t.panel);
        } else if(c instanceof JPanel || c instanceof JScrollPane || c instanceof JRootPane){
            c.setBackground(t.bg);
        }
    }
}
