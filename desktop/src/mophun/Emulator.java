package mophun;

import mophun.GameLibrary.Game;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.List;

/**
 * Janela principal do emulador Mophun (JavaSE): biblioteca de ROMs com preview,
 * menus (Arquivo/Configuracoes/Ajuda), Settings de video/som e mapeamento de
 * teclas. Roda os jogos na VM PIP2 (pacote wolphun.vm) por meio de GameSession.
 */
public final class Emulator extends JFrame {
    final Config cfg;
    List<Game> games = java.util.Collections.emptyList();
    final DefaultListModel<Game> model = new DefaultListModel<Game>();
    final JList<Game> list = new JList<Game>(model);
    final JTextField search = new JTextField();
    final JLabel shot = new JLabel("", SwingConstants.CENTER);
    final JLabel info = new JLabel("", SwingConstants.CENTER);
    final JComboBox<File> variantBox = new JComboBox<File>();
    final JButton playBtn = new JButton(Lang.t("btn.play"));
    final JLabel searchLabel = new JLabel(Lang.t("lbl.search"));
    final JLabel versionLabel = new JLabel(Lang.t("lbl.version"));

    volatile GameWindow activeGame;   // janela de jogo em execucao (p/ garantir save ao fechar o emulador)

    public Emulator(Config cfg){
        super(Branding.APP_NAME);
        this.cfg=cfg;
        Branding.applyIcon(this);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        // ao fechar o emulador (ou qualquer saida da JVM), para a sessao ativa -> flush do save nativo
        Runtime.getRuntime().addShutdownHook(new Thread(){ @Override public void run(){
            try { GameWindow g=activeGame; if(g!=null) g.stopSession(); } catch(Throwable t){}
        }});
        buildMenu();
        buildBody();
        applyTheme();                                   // aplica o tema salvo (cores + fonte) antes de dimensionar
        setPreferredSize(new Dimension(760, 500));
        pack();
        setLocationRelativeTo(null);
        reloadLibrary();
        setVisible(true);
    }

    // ---------------- menu ----------------
    void buildMenu(){
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu(Lang.t("menu.file"));
        JMenuItem setRoot = new JMenuItem(Lang.t("m.setrom"));
        setRoot.addActionListener(e -> chooseRoot());
        JMenuItem openRom = new JMenuItem(Lang.t("m.openrom"));
        openRom.addActionListener(e -> openSingleRom());
        JMenuItem saveDec = new JMenuItem(Lang.t("m.savedec"));
        saveDec.addActionListener(e -> saveDecryptedRom());
        JMenuItem exit = new JMenuItem(Lang.t("m.exit"));
        exit.addActionListener(e -> { cfg.save(); System.exit(0); });
        file.add(setRoot); file.add(openRom); file.add(saveDec); file.addSeparator(); file.add(exit);

        JMenu settings = new JMenu(Lang.t("menu.settings"));
        JMenuItem video = new JMenuItem(Lang.t("m.video"));
        video.addActionListener(e -> videoDialog());
        // ---- submenu Sound (liga/desliga, soundfont, reverb) ----
        JMenu soundMenu = new JMenu(Lang.t("menu.sound"));
        JCheckBoxMenuItem sound = new JCheckBoxMenuItem(Lang.t("m.sound.on"), cfg.sound);
        sound.addActionListener(e -> { cfg.sound = sound.isSelected(); cfg.save(); });
        JMenuItem soundfont = new JMenuItem(Lang.t("m.soundfont"));
        soundfont.addActionListener(e -> chooseSoundFont());
        JCheckBoxMenuItem reverb = new JCheckBoxMenuItem(Lang.t("m.reverb"), cfg.reverb);
        reverb.addActionListener(e -> { cfg.reverb = reverb.isSelected(); cfg.save(); });
        soundMenu.add(sound); soundMenu.add(soundfont); soundMenu.add(reverb);
        JMenuItem controls = new JMenuItem(Lang.t("m.controls"));
        controls.addActionListener(e -> controlsDialog());
        JMenuItem appearance = new JMenuItem(Lang.t("m.appearance"));
        appearance.addActionListener(e -> appearanceDialog());
        // ---- submenu de idioma ----
        JMenu langMenu = new JMenu(Lang.t("menu.language"));
        ButtonGroup lg = new ButtonGroup();
        for(int i=0;i<Lang.NAMES.length;i++){
            final int idx=i;
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(Lang.NAMES[i], Lang.cur==i);
            it.addActionListener(e -> setLanguage(idx));
            lg.add(it); langMenu.add(it);
        }
        settings.add(video); settings.add(soundMenu); settings.add(controls); settings.add(appearance); settings.addSeparator(); settings.add(langMenu);

        JMenu help = new JMenu(Lang.t("menu.help"));
        JMenuItem about = new JMenuItem(Lang.t("m.about"));
        about.addActionListener(e -> aboutDialog());
        help.add(about);

        bar.add(file); bar.add(settings); bar.add(help);
        setJMenuBar(bar);
    }

    // troca de idioma AO VIVO: aplica, salva, e atualiza os textos visiveis (menu + rotulos + botao)
    void setLanguage(int idx){
        Lang.cur = idx; Lang.applyLocale(); cfg.language = Lang.code(); cfg.save();
        buildMenu();                                   // reconstroi a barra de menus no novo idioma
        searchLabel.setText(Lang.t("lbl.search"));
        versionLabel.setText(Lang.t("lbl.version"));
        playBtn.setText(Lang.t("btn.play"));
        if(getJMenuBar()!=null) getJMenuBar().revalidate();
        revalidate(); repaint();
    }

    // ---------------- diálogo "Sobre" ----------------
    void aboutDialog(){
        String body = "<html><div style='text-align:center; width:340px; font-family:sans-serif;'>"
            + "<div style='font-size:16px; font-weight:bold; color:#e8451f;'>" + Branding.APP_NAME + "</div>"
            + "<div style='margin-top:2px;'>" + Lang.t("about.tagline") + "</div>"
            + "<br>"
            + "<div>" + Lang.t("lbl.version") + " <b>" + Branding.VERSION + " " + Branding.CHANNEL + "</b>"
            + " &mdash; " + Lang.t("about.firstbeta") + "</div>"
            + "<br>"
            + "<div>" + Lang.t("about.developer") + ": <b>" + Branding.AUTHOR + "</b></div>"
            + "<div>" + Lang.t("about.studio") + ": <b>" + Branding.VENDOR + "</b></div>"
            + "<div style='color:#888888; margin-top:6px;'>&copy; " + Branding.YEAR + " &middot; " + Branding.URL + "</div>"
            + "</div></html>";
        Icon ic = null;
        Image logo = Branding.logo();
        if(logo != null) ic = new ImageIcon(logo.getScaledInstance(84, 84, Image.SCALE_SMOOTH));
        JOptionPane.showMessageDialog(this, body, Lang.t("m.about"), JOptionPane.INFORMATION_MESSAGE, ic);
    }

    // ---------------- corpo (biblioteca + preview) ----------------
    void buildBody(){
        // esquerda: busca + lista
        JPanel left = new JPanel(new BorderLayout(4,4));
        left.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        JPanel top = new JPanel(new BorderLayout(4,0));
        top.add(searchLabel, BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        left.add(top, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        left.add(new JScrollPane(list), BorderLayout.CENTER);

        search.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){ applyFilter(); }
            public void removeUpdate(DocumentEvent e){ applyFilter(); }
            public void changedUpdate(DocumentEvent e){ applyFilter(); }
        });
        list.addListSelectionListener(e -> { if(!e.getValueIsAdjusting()) showSelected(); });
        list.addMouseListener(new MouseAdapter(){
            @Override public void mouseClicked(MouseEvent e){ if(e.getClickCount()==2) launchSelected(); }
        });
        list.addKeyListener(new KeyAdapter(){
            @Override public void keyPressed(KeyEvent e){ if(e.getKeyCode()==KeyEvent.VK_ENTER) launchSelected(); }
        });

        // direita: screenshot + info + variante + jogar
        JPanel right = new JPanel(new BorderLayout(6,6));
        right.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        shot.setPreferredSize(new Dimension(280,260));
        shot.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        shot.setVerticalAlignment(SwingConstants.CENTER);
        right.add(shot, BorderLayout.CENTER);
        JPanel south = new JPanel(new GridLayout(0,1,4,4));
        info.setFont(info.getFont().deriveFont(Font.BOLD));
        south.add(info);
        JPanel vp = new JPanel(new BorderLayout(4,0));
        vp.add(versionLabel, BorderLayout.WEST);
        vp.add(variantBox, BorderLayout.CENTER);
        south.add(vp);
        playBtn.setFont(playBtn.getFont().deriveFont(Font.BOLD, 15f));
        playBtn.addActionListener(e -> launchSelected());
        south.add(playBtn);
        right.add(south, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(430);
        add(split, BorderLayout.CENTER);
    }

    // ---------------- aparencia (tema: cores + fonte) ----------------
    /** aplica o tema atual (cfg) a esta janela + dialogos, e destaca o botao Jogar com a cor de acento. */
    void applyTheme(){
        Theme t = Theme.resolve(cfg);
        Theme.apply(t, cfg.uiFont, cfg.uiFontSize);
        if(t!=null){
            // botao Jogar em destaque (cor de acento)
            playBtn.setBackground(t.accent);
            playBtn.setForeground(Theme.contrast(t.accent));
            playBtn.setOpaque(true);
            playBtn.setBorderPainted(false);
            shot.setBorder(BorderFactory.createLineBorder(t.accent.darker()));
        } else {
            // "system": devolve o botao Jogar e a borda ao visual nativo
            playBtn.setBackground(null);
            playBtn.setForeground(null);
            playBtn.setOpaque(false);
            playBtn.setBorderPainted(true);
            shot.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        }
        revalidate(); repaint();
    }

    void appearanceDialog(){
        // snapshot p/ cancelar (restaura tudo se o usuario desistir)
        final String oT=cfg.theme, oF=cfg.uiFont; final int oSz=cfg.uiFontSize,
            oBg=cfg.uiBg, oPn=cfg.uiPanel, oFg=cfg.uiFg, oAc=cfg.uiAccent;

        final String[] ids = Theme.ids();
        final JComboBox<String> themeBox = new JComboBox<String>();
        for(String id: ids) themeBox.addItem(id);
        themeBox.setSelectedItem(cfg.theme);
        themeBox.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean s,boolean f){
                super.getListCellRendererComponent(l,v,i,s,f);
                if(v!=null) setText(Lang.t("theme."+v));
                return this;
            }
        });

        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        java.util.List<String> fam = new java.util.ArrayList<String>();
        fam.add("SansSerif"); fam.add("Serif"); fam.add("Monospaced");
        for(String s: fonts) fam.add(s);
        final JComboBox<String> fontBox = new JComboBox<String>(fam.toArray(new String[0]));
        fontBox.setSelectedItem(cfg.uiFont);
        final JSpinner sizeSp = new JSpinner(new SpinnerNumberModel(cfg.uiFontSize, 9, 28, 1));

        // botoes de cor (swatches) — marcados p/ o tema NAO os repintar (mantem a cor verdadeira)
        final JButton bgBtn = new JButton(), pnBtn = new JButton(), fgBtn = new JButton(), acBtn = new JButton();
        for(JButton b : new JButton[]{bgBtn,pnBtn,fgBtn,acBtn}) b.putClientProperty("themeSwatch", Boolean.TRUE);

        // aplica o estado atual do dialogo no cfg + na tela (ao vivo)
        final Runnable live = new Runnable(){ public void run(){
            String id = (String)themeBox.getSelectedItem();
            cfg.theme = id;
            boolean custom = "custom".equals(id);
            Theme preview = Theme.byId(id, cfg);
            // decide as cores que os 4 swatches devem MOSTRAR
            Color c0,c1,c2,c3;
            if(custom){
                c0=new Color(colorOf(bgBtn)); c1=new Color(colorOf(pnBtn)); c2=new Color(colorOf(fgBtn)); c3=new Color(colorOf(acBtn));
                cfg.uiBg=colorOf(bgBtn); cfg.uiPanel=colorOf(pnBtn); cfg.uiFg=colorOf(fgBtn); cfg.uiAccent=colorOf(acBtn);
            } else if(preview!=null){
                c0=preview.bg; c1=preview.panel; c2=preview.fg; c3=preview.accent;
                fontBox.setSelectedItem(preview.font);
            } else { // system: swatches neutros
                c0=new Color(0x2E2C34); c1=new Color(0x2E2C34); c2=new Color(0xF0EDE6); c3=new Color(0xE8451F);
            }
            cfg.uiFont = (String)fontBox.getSelectedItem();
            cfg.uiFontSize = (Integer)sizeSp.getValue();
            applyTheme();
            // re-afirma os swatches DEPOIS de aplicar o tema (o updateComponentTreeUI reseta cores;
            // como sao 'themeSwatch' o applyTree os ignora, entao restauramos aqui com texto de contraste)
            setBtnColor(bgBtn,c0); setBtnColor(pnBtn,c1); setBtnColor(fgBtn,c2); setBtnColor(acBtn,c3);
        }};

        // cores iniciais dos swatches
        Theme init = Theme.byId(cfg.theme, cfg);
        if("custom".equals(cfg.theme)){ setBtnColor(bgBtn,new Color(cfg.uiBg)); setBtnColor(pnBtn,new Color(cfg.uiPanel)); setBtnColor(fgBtn,new Color(cfg.uiFg)); setBtnColor(acBtn,new Color(cfg.uiAccent)); }
        else if(init!=null){ setBtnColor(bgBtn,init.bg); setBtnColor(pnBtn,init.panel); setBtnColor(fgBtn,init.fg); setBtnColor(acBtn,init.accent); }
        else { setBtnColor(bgBtn,new Color(0x2E2C34)); setBtnColor(pnBtn,new Color(0x2E2C34)); setBtnColor(fgBtn,new Color(0xF0EDE6)); setBtnColor(acBtn,new Color(0xE8451F)); }

        // ao clicar num swatch: abre o seletor de cor, entra no modo custom e aplica ao vivo
        final JButton[] sw = { bgBtn, pnBtn, fgBtn, acBtn };
        final String[] swTitle = { Lang.t("ap.bg"), Lang.t("ap.panel"), Lang.t("ap.text"), Lang.t("ap.accent") };
        for(int i=0;i<sw.length;i++){ final JButton b=sw[i]; final String title=swTitle[i];
            b.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
                Color c = JColorChooser.showDialog(Emulator.this, title, b.getBackground());
                if(c==null) return;
                setBtnColor(b, c);
                if(!"custom".equals(themeBox.getSelectedItem())) themeBox.setSelectedItem("custom"); // dispara live (le os 4 swatches)
                else live.run();
            }});
        }

        themeBox.addActionListener(e -> live.run());
        fontBox.addActionListener(e -> live.run());
        sizeSp.addChangeListener(e -> live.run());

        JPanel p = new JPanel(new GridLayout(0,2,6,6));
        p.add(new JLabel(Lang.t("ap.theme")));  p.add(themeBox);
        p.add(new JLabel(Lang.t("ap.font")));   p.add(fontBox);
        p.add(new JLabel(Lang.t("ap.size")));   p.add(sizeSp);
        p.add(new JLabel(Lang.t("ap.bg")));     p.add(bgBtn);
        p.add(new JLabel(Lang.t("ap.panel")));  p.add(pnBtn);
        p.add(new JLabel(Lang.t("ap.text")));   p.add(fgBtn);
        p.add(new JLabel(Lang.t("ap.accent"))); p.add(acBtn);
        int r = JOptionPane.showConfirmDialog(this, p, Lang.t("dlg.appearance"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(r==JOptionPane.OK_OPTION){
            live.run(); cfg.save();
        } else {
            // cancelar: restaura tudo e re-aplica o tema anterior
            cfg.theme=oT; cfg.uiFont=oF; cfg.uiFontSize=oSz; cfg.uiBg=oBg; cfg.uiPanel=oPn; cfg.uiFg=oFg; cfg.uiAccent=oAc;
            applyTheme();
        }
    }
    // helpers de cor p/ os botoes-swatch
    static int colorOf(JButton b){ Color c=b.getBackground(); return c!=null? (c.getRGB()&0xFFFFFF) : 0; }
    static void setBtnColor(JButton b, Color c){
        b.setBackground(c); b.setOpaque(true); b.setBorderPainted(true);
        b.setText("  #"+String.format("%06X", c.getRGB()&0xFFFFFF)+"  ");
        b.setForeground(Theme.contrast(c));
    }

    // ---------------- biblioteca ----------------
    void reloadLibrary(){
        File root = cfg.gamesRoot!=null ? new File(cfg.gamesRoot) : null;
        games = GameLibrary.scan(root);
        applyFilter();
        setTitle(Branding.APP_NAME + "  —  " + (root!=null? root.getAbsolutePath()+"  ("+games.size()+")" : "—"));
        if(games.isEmpty() && root==null){
            SwingUtilities.invokeLater(() -> {
                int r = JOptionPane.showConfirmDialog(this,
                    Lang.t("lib.emptyask"),
                    Lang.t("lib.emptytitle"), JOptionPane.YES_NO_OPTION);
                if(r==JOptionPane.YES_OPTION) chooseRoot();
            });
        }
    }
    void applyFilter(){
        String q = search.getText().trim().toLowerCase();
        model.clear();
        for(Game g: games) if(q.isEmpty() || g.name.toLowerCase().contains(q)) model.addElement(g);
        if(!model.isEmpty()) list.setSelectedIndex(0);
        else showSelected();
    }
    void showSelected(){
        Game g = list.getSelectedValue();
        variantBox.removeAllItems();
        if(g==null){ shot.setIcon(null); shot.setText(Lang.t("msg.selgame")); info.setText(""); playBtn.setEnabled(false); return; }
        playBtn.setEnabled(true);
        info.setText(g.name);
        for(File v: g.variants) variantBox.addItem(v);
        variantBox.setSelectedItem(g.mpn);
        // renderer que mostra so o nome do arquivo
        variantBox.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> l,Object val,int i,boolean s,boolean f){
                super.getListCellRendererComponent(l,val,i,s,f);
                if(val instanceof File) setText(((File)val).getName());
                return this;
            }
        });
        loadShot(g.screenshot);
    }
    void loadShot(File f){
        if(f==null || !f.isFile()){ shot.setIcon(null); shot.setText(Lang.t("msg.noshot")); return; }
        try {
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(f);
            if(im==null){ shot.setIcon(null); shot.setText(Lang.t("msg.noshot")); return; }
            int maxW=270, maxH=250;
            double sc = Math.min((double)maxW/im.getWidth(), (double)maxH/im.getHeight());
            if(sc>4) sc=4;   // pixel art: nao ampliar demais
            int w=(int)(im.getWidth()*sc), h=(int)(im.getHeight()*sc);
            Image scaled = im.getScaledInstance(w,h, Image.SCALE_FAST);
            shot.setText(null); shot.setIcon(new ImageIcon(scaled));
        } catch(Exception ex){ shot.setIcon(null); shot.setText(Lang.t("img.error")); }
    }

    void launchSelected(){
        Game g = list.getSelectedValue();
        if(g==null) return;
        File mpn = (File)variantBox.getSelectedItem();
        if(mpn==null) mpn = g.mpn;
        launch(g, mpn);
    }
    void launch(Game g, File mpn){
        if(GameLibrary.looksEncrypted(mpn)){
            File cached = MophunCrypt.cachedValid(mpn);
            if(cached!=null){
                mpn = cached;                       // ja descriptografado antes -> sem perguntar de novo
            } else {
                Object[] opts = {Lang.t("dec.playbtn"),Lang.t("btn.cancel")};
                int r = JOptionPane.showOptionDialog(this,
                    Lang.t("dec.encmsg", g.name),
                    Lang.t("dec.enctitle"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
                if(r!=0) return;
                File dec;
                try { dec = MophunCrypt.ensureDecrypted(mpn); }
                catch(Throwable t){
                    JOptionPane.showMessageDialog(this, Lang.t("dec.fail")+"\n"+t, Lang.t("dlg.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if(dec==null){
                    JOptionPane.showMessageDialog(this,
                        Lang.t("dec.nokeymsg", g.name),
                        Lang.t("dec.nokeytitle"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                mpn = dec;
            }
        }
        cfg.save();
        try {
            setVisible(false);
            final Emulator self = this;
            activeGame = new GameWindow(cfg, mpn, g.name, new Runnable(){ public void run(){ self.setVisible(true); activeGame=null; } });
        } catch(Throwable ex){
            setVisible(true);
            // detecta o erro tipico de container invalido/criptografado e mostra msg amigavel localizada,
            // em vez da excecao crua em ingles (antes vazava "Nao e um VMGP valido" em PT).
            Throwable root = ex; while(root.getCause()!=null) root = root.getCause();
            boolean notVmgp = (root instanceof IllegalArgumentException)
                && root.getMessage()!=null && root.getMessage().toLowerCase().contains("vmgp");
            String detail = notVmgp ? Lang.t("err.notvmgp") : String.valueOf(ex);
            JOptionPane.showMessageDialog(this, Lang.t("err.cantstart", g.name)+"\n\n"+detail,
                Lang.t("err.starttitle"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- acoes de menu ----------------
    // escolhe um .sf2 customizado p/ o MIDI (Gervill), ou volta ao synth padrao do SO.
    // Vale a partir do PROXIMO jogo aberto (o Sdk le cfg.soundFont ao iniciar a sessao).
    void chooseSoundFont(){
        String cur = cfg.soundFont!=null ? new File(cfg.soundFont).getName() : Lang.t("sf.default");
        Object[] opts = {Lang.t("sf.choose"), Lang.t("sf.usedefault"), Lang.t("btn.cancel")};
        int r = JOptionPane.showOptionDialog(this, Lang.t("sf.current")+" "+cur, Lang.t("m.soundfont"),
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if(r==0){
            JFileChooser fc = new JFileChooser(cfg.soundFont!=null? new File(cfg.soundFont).getParentFile() : null);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("SoundFont (*.sf2)","sf2"));
            if(fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){
                cfg.soundFont = fc.getSelectedFile().getAbsolutePath(); cfg.save();
                JOptionPane.showMessageDialog(this, Lang.t("sf.applied"));
            }
        } else if(r==1){ cfg.soundFont = null; cfg.save(); JOptionPane.showMessageDialog(this, Lang.t("sf.applied")); }
    }

    void chooseRoot(){
        JFileChooser fc = new JFileChooser(cfg.gamesRoot!=null? new File(cfg.gamesRoot) : null);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(Lang.t("m.selromtitle"));
        if(fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){
            cfg.gamesRoot = fc.getSelectedFile().getAbsolutePath();
            cfg.save();
            reloadLibrary();
        }
    }
    void openSingleRom(){
        JFileChooser fc = new JFileChooser(cfg.gamesRoot!=null? new File(cfg.gamesRoot) : null);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ROM mophun (*.mpn)","mpn"));
        if(fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){
            File f = fc.getSelectedFile();
            launch(new Game(GameLibrary.stripExt(f.getName()), f, null, java.util.Collections.singletonList(f)), f);
        }
    }

    // descriptografa o jogo selecionado permanentemente (copia ou substitui original)
    void saveDecryptedRom(){
        Game g = list.getSelectedValue();
        if(g==null){ JOptionPane.showMessageDialog(this,Lang.t("dec.selfirst")); return; }
        final File src = g.mpn;
        if(!GameLibrary.looksEncrypted(src)){
            JOptionPane.showMessageDialog(this, Lang.t("dec.already", g.name), Lang.t("dec.nothing"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] opts = {Lang.t("dec.optcopy"),Lang.t("dec.optreplace"),Lang.t("btn.cancel")};
        int r = JOptionPane.showOptionDialog(this,
            Lang.t("dec.permmsg", g.name),
            Lang.t("dec.savetitle"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        try {
            if(r==0){                                   // salvar copia
                JFileChooser fc = new JFileChooser(src.getParentFile());
                fc.setDialogTitle(Lang.t("dec.saveas"));
                fc.setSelectedFile(new File(src.getParentFile(), GameLibrary.stripExt(src.getName())+"_decrypted.mpn"));
                if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return;
                if(MophunCrypt.decryptTo(src, fc.getSelectedFile()))
                    JOptionPane.showMessageDialog(this, Lang.t("dec.saved")+"\n"+fc.getSelectedFile().getAbsolutePath());
                else JOptionPane.showMessageDialog(this, Lang.t("dec.nokeyrsa"), Lang.t("dlg.fail"), JOptionPane.WARNING_MESSAGE);
            } else if(r==1){                            // substituir original (destrutivo)
                int c = JOptionPane.showConfirmDialog(this,
                    Lang.t("dec.overwritemsg", src.getAbsolutePath()),
                    Lang.t("dec.optreplace"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if(c!=JOptionPane.YES_OPTION) return;
                if(MophunCrypt.decryptTo(src, src)){
                    reloadLibrary();
                    JOptionPane.showMessageDialog(this, Lang.t("dec.replaced"));
                } else JOptionPane.showMessageDialog(this, Lang.t("dec.nokeyrsa"), Lang.t("dlg.fail"), JOptionPane.WARNING_MESSAGE);
            }
        } catch(Throwable t){
            JOptionPane.showMessageDialog(this, Lang.t("dlg.error")+": "+t, Lang.t("dlg.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    void videoDialog(){
        final JSpinner scaleSp = new JSpinner(new SpinnerNumberModel(cfg.scale,1,8,1));
        final JSpinner fpsSp   = new JSpinner(new SpinnerNumberModel(cfg.targetFps,10,120,5));
        final JSpinner gammaSp = new JSpinner(new SpinnerNumberModel(cfg.gamma,0.40,1.00,0.05));
        final JSpinner fontSp  = new JSpinner(new SpinnerNumberModel(cfg.fontScale,1.00,2.50,0.10));
        final JSpinner hdSp    = new JSpinner(new SpinnerNumberModel(cfg.renderScale,1,4,1));
        String[] presets = {"101 x 80","128 x 128","128 x 160","176 x 208","176 x 220","208 x 208","208 x 320","240 x 320"};
        final JComboBox<String> resBox = new JComboBox<String>(presets);
        resBox.setEditable(true);
        resBox.setSelectedItem(cfg.width+" x "+cfg.height);
        final JComboBox<String> modelBox = new JComboBox<String>(Config.MODEL_NAMES);
        if(cfg.deviceModel>=0 && cfg.deviceModel<Config.MODEL_NAMES.length) modelBox.setSelectedIndex(cfg.deviceModel);
        // ao trocar o modelo, sugere a resolucao nativa dele
        modelBox.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
            int[] wh = Config.resForModel(modelBox.getSelectedIndex());
            resBox.setSelectedItem(wh[0]+" x "+wh[1]);
        }});
        JPanel p = new JPanel(new GridLayout(0,2,6,6));
        p.add(new JLabel(Lang.t("lbl.device"))); p.add(modelBox);
        p.add(new JLabel(Lang.t("lbl.res")));    p.add(resBox);
        p.add(new JLabel(Lang.t("lbl.scale")));  p.add(scaleSp);
        p.add(new JLabel(Lang.t("lbl.fps")));    p.add(fpsSp);
        p.add(new JLabel(Lang.t("lbl.gamma")));  p.add(gammaSp);
        p.add(new JLabel(Lang.t("lbl.fontsize"))); p.add(fontSp);
        p.add(new JLabel(Lang.t("lbl.hd")));     p.add(hdSp);
        if(JOptionPane.showConfirmDialog(this,p,Lang.t("dlg.video"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){
            cfg.scale = (Integer)scaleSp.getValue();
            cfg.targetFps = (Integer)fpsSp.getValue();
            cfg.gamma = ((Number)gammaSp.getValue()).doubleValue();
            cfg.fontScale = ((Number)fontSp.getValue()).doubleValue();
            cfg.renderScale = (Integer)hdSp.getValue();
            cfg.deviceModel = modelBox.getSelectedIndex();
            int[] wh = parseRes((String)resBox.getSelectedItem());
            if(wh!=null){ cfg.width=wh[0]; cfg.height=wh[1]; }
            cfg.save();
        }
    }
    static int[] parseRes(String s){
        try {
            String[] parts = s.toLowerCase().replace(" ","").split("x");
            return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch(Exception e){ return null; }
    }

    // formata uma lista de VKs como "↑, W, Num 8"
    static String keysText(java.util.List<Integer> vks){
        if(vks==null || vks.isEmpty()) return Lang.t("keys.none");
        StringBuilder b = new StringBuilder();
        for(int i=0;i<vks.size();i++){ if(i>0) b.append(", "); b.append(KeyEvent.getKeyText(vks.get(i))); }
        return b.toString();
    }
    void controlsDialog(){
        final JDialog dlg = new JDialog(this, Lang.t("ctrl.title"), true);
        JPanel grid = new JPanel(new GridLayout(0,3,6,6));
        grid.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        // copia editavel: acao -> lista de VKs
        final java.util.Map<String,java.util.List<Integer>> temp = new java.util.LinkedHashMap<String,java.util.List<Integer>>();
        for(String a: Config.ACTIONS){ java.util.List<Integer> l=new java.util.ArrayList<Integer>();
            int[] cur=cfg.binds.get(a); if(cur!=null) for(int v:cur) l.add(v); temp.put(a,l); }
        final java.util.Map<String,JButton> btns = new java.util.HashMap<String,JButton>();
        final String[] capturing = {null};                         // acao em captura (ou null)
        final Color CAP = new Color(0xFFE08A);
        // termina a captura: restaura o texto/cor do botao
        final Runnable[] stopCap = new Runnable[1];
        stopCap[0] = new Runnable(){ public void run(){
            if(capturing[0]==null) return;
            JButton b=btns.get(capturing[0]); if(b!=null){ b.setText(keysText(temp.get(capturing[0]))); b.setBackground(null); }
            capturing[0]=null;
        }};
        // CAPTURA GLOBAL via KeyEventDispatcher: pega QUALQUER tecla (inclusive Espaco/Enter/Tab/setas/
        // numpad) ANTES do Swing -> resolve o bug de nao conseguir mapear Espaco/Enter (que "clicavam" o botao).
        final KeyEventDispatcher disp = new KeyEventDispatcher(){ public boolean dispatchKeyEvent(KeyEvent e){
            if(capturing[0]==null) return false;                   // fora de captura: comportamento normal
            if(e.getID()==KeyEvent.KEY_PRESSED){
                int vk=e.getKeyCode();
                if(vk==KeyEvent.VK_ESCAPE){ stopCap[0].run(); return true; }
                java.util.List<Integer> l=temp.get(capturing[0]);
                if(l.contains(vk)) l.remove((Integer)vk); else l.add(vk);   // toggle add/remove
                btns.get(capturing[0]).setText("["+keysText(l)+"]  (Esc termina)");
            }
            return true;                                            // consome tudo enquanto captura
        }};
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(disp);
        for(final String action: Config.ACTIONS){
            grid.add(new JLabel(action+":"));
            final JButton b = new JButton(keysText(temp.get(action)));
            b.setFocusable(false);                                 // captura e global; o botao nao precisa de foco
            btns.put(action, b);
            b.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
                stopCap[0].run();                                  // fecha captura anterior
                capturing[0]=action; b.setBackground(CAP);
                b.setText("["+keysText(temp.get(action))+"]  "+Lang.t("ctrl.esc"));
            }});
            JButton clr = new JButton(Lang.t("btn.clear")); clr.setFocusable(false);
            clr.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
                stopCap[0].run(); temp.get(action).clear(); b.setText(keysText(temp.get(action))); }});
            grid.add(b); grid.add(clr);
        }
        JButton ok = new JButton(Lang.t("btn.save"));
        ok.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
            stopCap[0].run();
            for(String a: Config.ACTIONS){ java.util.List<Integer> l=temp.get(a);
                int[] arr=new int[l.size()]; for(int i=0;i<arr.length;i++) arr[i]=l.get(i); cfg.binds.put(a,arr); }
            cfg.save(); dlg.dispose();
        }});
        JButton def = new JButton(Lang.t("btn.default"));
        def.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
            stopCap[0].run();
            for(int i=0;i<Config.ACTIONS.length;i++){ String a=Config.ACTIONS[i];
                java.util.List<Integer> l=temp.get(a); l.clear();
                for(int v: Config.ACTION_DEF_VK[i]) l.add(v);
                btns.get(a).setText(keysText(l)); }
        }});
        JButton cancel = new JButton(Lang.t("btn.cancel"));
        cancel.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ stopCap[0].run(); dlg.dispose(); }});
        JPanel south = new JPanel(); south.add(def); south.add(cancel); south.add(ok);
        JLabel hint = new JLabel(Lang.t("ctrl.hint"));
        hint.setBorder(BorderFactory.createEmptyBorder(8,10,0,10));
        dlg.getContentPane().add(hint, BorderLayout.NORTH);
        dlg.getContentPane().add(grid, BorderLayout.CENTER);
        dlg.getContentPane().add(south, BorderLayout.SOUTH);
        dlg.pack(); dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);                                       // bloqueia (modal) ate fechar
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(disp);   // limpa
    }

    // ---------------- main ----------------
    public static void main(String[] args){
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch(Exception e){}
        final Config cfg = Config.load();
        if(cfg.gamesRoot==null){
            // default: arquivo Games_SE se existir
            File guess = new File("C:/Users/tavar/Downloads/Mophun_Archive_2.1/Games_SE");
            if(guess.isDirectory()){ cfg.gamesRoot = guess.getAbsolutePath(); cfg.save(); }
        }
        SwingUtilities.invokeLater(new Runnable(){ public void run(){
            Theme.setUIManager(Theme.resolve(cfg), cfg.uiFont, cfg.uiFontSize);   // dialogos ja nascem no tema
            new Emulator(cfg);
        } });
    }
}
