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
        JMenuItem openRom = new JMenuItem("Abrir ROM (.mpn)...");
        openRom.addActionListener(e -> openSingleRom());
        JMenuItem saveDec = new JMenuItem(Lang.t("m.savedec"));
        saveDec.addActionListener(e -> saveDecryptedRom());
        JMenuItem exit = new JMenuItem(Lang.t("m.exit"));
        exit.addActionListener(e -> { cfg.save(); System.exit(0); });
        file.add(setRoot); file.add(openRom); file.add(saveDec); file.addSeparator(); file.add(exit);

        JMenu settings = new JMenu(Lang.t("menu.settings"));
        JMenuItem video = new JMenuItem("Video...");
        video.addActionListener(e -> videoDialog());
        JCheckBoxMenuItem sound = new JCheckBoxMenuItem("Som", cfg.sound);
        sound.addActionListener(e -> { cfg.sound = sound.isSelected(); cfg.save(); });
        JMenuItem controls = new JMenuItem(Lang.t("m.controls"));
        controls.addActionListener(e -> controlsDialog());
        // ---- submenu de idioma ----
        JMenu langMenu = new JMenu(Lang.t("menu.language"));
        ButtonGroup lg = new ButtonGroup();
        for(int i=0;i<Lang.NAMES.length;i++){
            final int idx=i;
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(Lang.NAMES[i], Lang.cur==i);
            it.addActionListener(e -> setLanguage(idx));
            lg.add(it); langMenu.add(it);
        }
        settings.add(video); settings.add(sound); settings.add(controls); settings.addSeparator(); settings.add(langMenu);

        JMenu help = new JMenu(Lang.t("menu.help"));
        JMenuItem about = new JMenuItem(Lang.t("m.about"));
        about.addActionListener(e -> aboutDialog());
        help.add(about);

        bar.add(file); bar.add(settings); bar.add(help);
        setJMenuBar(bar);
    }

    // troca de idioma AO VIVO: aplica, salva, e atualiza os textos visiveis (menu + rotulos + botao)
    void setLanguage(int idx){
        Lang.cur = idx; cfg.language = Lang.code(); cfg.save();
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

    // ---------------- biblioteca ----------------
    void reloadLibrary(){
        File root = cfg.gamesRoot!=null ? new File(cfg.gamesRoot) : null;
        games = GameLibrary.scan(root);
        applyFilter();
        setTitle(Branding.APP_NAME + "  —  " + (root!=null? root.getAbsolutePath()+"  ("+games.size()+")" : "—"));
        if(games.isEmpty() && root==null){
            SwingUtilities.invokeLater(() -> {
                int r = JOptionPane.showConfirmDialog(this,
                    "Nenhuma pasta de ROMs definida. Deseja selecionar agora?",
                    "Biblioteca vazia", JOptionPane.YES_NO_OPTION);
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
        if(g==null){ shot.setIcon(null); shot.setText("(selecione um jogo)"); info.setText(""); playBtn.setEnabled(false); return; }
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
        if(f==null || !f.isFile()){ shot.setIcon(null); shot.setText("(sem screenshot)"); return; }
        try {
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(f);
            if(im==null){ shot.setIcon(null); shot.setText("(sem screenshot)"); return; }
            int maxW=270, maxH=250;
            double sc = Math.min((double)maxW/im.getWidth(), (double)maxH/im.getHeight());
            if(sc>4) sc=4;   // pixel art: nao ampliar demais
            int w=(int)(im.getWidth()*sc), h=(int)(im.getHeight()*sc);
            Image scaled = im.getScaledInstance(w,h, Image.SCALE_FAST);
            shot.setText(null); shot.setIcon(new ImageIcon(scaled));
        } catch(Exception ex){ shot.setIcon(null); shot.setText("(erro na imagem)"); }
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
                Object[] opts = {"Descriptografar e jogar","Cancelar"};
                int r = JOptionPane.showOptionDialog(this,
                    "'"+g.name+"' esta CRIPTOGRAFADO (DRM mophun).\n" +
                    "Deseja descriptografar agora para jogar normalmente?\n\n" +
                    "A copia descriptografada fica em cache e sera REUTILIZADA nas proximas\n" +
                    "vezes (esta tela nao aparece de novo). O .mpn original NAO e alterado.\n" +
                    "Para uma copia permanente, use Arquivo > Salvar ROM descriptografado.",
                    "ROM criptografado", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
                if(r!=0) return;
                File dec;
                try { dec = MophunCrypt.ensureDecrypted(mpn); }
                catch(Throwable t){
                    JOptionPane.showMessageDialog(this, "Falha ao descriptografar:\n"+t, "Erro", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if(dec==null){
                    JOptionPane.showMessageDialog(this,
                        "Nao foi possivel descriptografar '"+g.name+"'.\n" +
                        "Nenhuma chave RSA embutida corresponde (talvez nao seja um jogo Sony Ericsson).",
                        "Sem chave compativel", JOptionPane.WARNING_MESSAGE);
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
            JOptionPane.showMessageDialog(this, "Nao foi possivel iniciar '"+g.name+"':\n"+ex,
                "Erro ao iniciar", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- acoes de menu ----------------
    void chooseRoot(){
        JFileChooser fc = new JFileChooser(cfg.gamesRoot!=null? new File(cfg.gamesRoot) : null);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Selecione a pasta com as ROMs mophun");
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
        if(g==null){ JOptionPane.showMessageDialog(this,"Selecione um jogo primeiro."); return; }
        final File src = g.mpn;
        if(!GameLibrary.looksEncrypted(src)){
            JOptionPane.showMessageDialog(this, "'"+g.name+"' ja esta descriptografado.", "Nada a fazer", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] opts = {"Salvar copia...","Substituir original","Cancelar"};
        int r = JOptionPane.showOptionDialog(this,
            "Descriptografar '"+g.name+"' permanentemente (nao passa mais pela tela de descriptografia):\n\n" +
            "• Salvar copia: grava um .mpn descriptografado onde voce escolher.\n" +
            "• Substituir original: sobrescreve o arquivo .mpn original (irreversivel).",
            "Salvar ROM descriptografado", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        try {
            if(r==0){                                   // salvar copia
                JFileChooser fc = new JFileChooser(src.getParentFile());
                fc.setDialogTitle("Salvar .mpn descriptografado como");
                fc.setSelectedFile(new File(src.getParentFile(), GameLibrary.stripExt(src.getName())+"_decrypted.mpn"));
                if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return;
                if(MophunCrypt.decryptTo(src, fc.getSelectedFile()))
                    JOptionPane.showMessageDialog(this, "Salvo:\n"+fc.getSelectedFile().getAbsolutePath());
                else JOptionPane.showMessageDialog(this, "Sem chave RSA compativel.", "Falha", JOptionPane.WARNING_MESSAGE);
            } else if(r==1){                            // substituir original (destrutivo)
                int c = JOptionPane.showConfirmDialog(this,
                    "Isto vai SOBRESCREVER o arquivo original:\n"+src.getAbsolutePath()+"\n\nConfirmar?",
                    "Substituir original", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if(c!=JOptionPane.YES_OPTION) return;
                if(MophunCrypt.decryptTo(src, src)){
                    reloadLibrary();
                    JOptionPane.showMessageDialog(this, "Original substituido pela versao descriptografada.");
                } else JOptionPane.showMessageDialog(this, "Sem chave RSA compativel.", "Falha", JOptionPane.WARNING_MESSAGE);
            }
        } catch(Throwable t){
            JOptionPane.showMessageDialog(this, "Erro: "+t, "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    void videoDialog(){
        final JSpinner scaleSp = new JSpinner(new SpinnerNumberModel(cfg.scale,1,8,1));
        final JSpinner fpsSp   = new JSpinner(new SpinnerNumberModel(cfg.targetFps,10,120,5));
        final JSpinner gammaSp = new JSpinner(new SpinnerNumberModel(cfg.gamma,0.40,1.00,0.05));
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
        if(JOptionPane.showConfirmDialog(this,p,Lang.t("dlg.video"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){
            cfg.scale = (Integer)scaleSp.getValue();
            cfg.targetFps = (Integer)fpsSp.getValue();
            cfg.gamma = ((Number)gammaSp.getValue()).doubleValue();
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
        if(vks==null || vks.isEmpty()) return "(nenhuma)";
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
                b.setText("["+keysText(temp.get(action))+"]  (Esc termina)");
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
        JLabel hint = new JLabel("<html>Clique num botao (fica <b>amarelo</b>) e tecle p/ <b>adicionar</b> (tecle de novo p/ remover). "
            +"Funciona com <b>Espaco, Enter, numpad, setas</b> — qualquer tecla. <b>Esc</b> termina a captura.<br>"
            +"Nos menus: <b>Fire / OK</b> (Espaco/Enter) <b>ESCOLHE</b> o item. A softkey <b>Select</b> = menu / voltar / "
            +"<b>sair</b> (no menu principal sai do jogo -> volta a biblioteca). <b>Fire2</b> = 2a acao.</html>");
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
        SwingUtilities.invokeLater(new Runnable(){ public void run(){ new Emulator(cfg); } });
    }
}
