package mophun;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

/**
 * Janela que hospeda uma partida (o Canvas da VM) + menu de emulacao
 * (Pausar/Retomar, Reiniciar, Fechar). Fechar volta para a biblioteca.
 */
public final class GameWindow extends JFrame {
    final Config cfg;
    final File mpn;
    final String name;
    final Runnable onClose;
    GameSession session;
    JMenuItem pauseItem;
    JCheckBoxMenuItem maxItem;
    javax.swing.Timer statusTimer;
    int lastFlips=0;
    volatile boolean closing=false;   // evita fechar 2x / durante reset

    public GameWindow(Config cfg, File mpn, String name, Runnable onClose) throws Exception {
        super(name + " - " + Branding.APP_NAME);
        this.cfg=cfg; this.mpn=mpn; this.name=name; this.onClose=onClose;
        Branding.applyIcon(this);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        buildMenu();
        startSession();
        addWindowListener(new WindowAdapter(){
            @Override public void windowClosing(WindowEvent e){ close(); }
        });
        addWindowStateListener(new WindowStateListener(){
            public void windowStateChanged(WindowEvent e){
                boolean max = (e.getNewState() & Frame.MAXIMIZED_BOTH) != 0;
                if(maxItem!=null) maxItem.setSelected(max);
                if(cfg.gameMaximized != max){ cfg.gameMaximized = max; cfg.save(); }
            }
        });
        // atualiza FPS no titulo a cada segundo; se o JOGO terminar sozinho (ex.: softkey de
        // "sair" no menu, ou opcao Quit), NAO congela em "encerrado" -> volta a biblioteca.
        statusTimer = new javax.swing.Timer(500, new ActionListener(){
            public void actionPerformed(ActionEvent e){
                if(session==null || closing) return;
                if(session.error!=null){ setTitle(name+" - "+Branding.APP_NAME+" ["+Lang.t("st.error")+"]"); onGameEnded(); return; }
                if(session.isStopped()){ onGameEnded(); return; }   // jogo saiu sozinho -> volta a biblioteca
                int f = session.getScreen()!=null ? session.getScreen().flips : 0;
                int fps = f-lastFlips; lastFlips=f;
                if(System.currentTimeMillis() < shotMsgUntil && shotMsg!=null){
                    setTitle(name+" - "+Branding.APP_NAME+" ["+shotMsg+"]");   // aviso da captura
                    return;
                }
                setTitle(name+" - "+Branding.APP_NAME+" ["+(session.isPaused()? Lang.t("st.paused") : fps+" fps")+"]");
            }
        });
        statusTimer.start();
    }

    void buildMenu(){
        JMenuBar bar = new JMenuBar();
        JMenu emu = new JMenu(Lang.t("menu.emulation"));
        pauseItem = new JMenuItem(Lang.t("gw.pause"));
        pauseItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK));
        pauseItem.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ togglePause(); } });
        JMenuItem reset = new JMenuItem(Lang.t("gw.restart"));
        reset.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        reset.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ reset(); } });
        JMenuItem saveState = new JMenuItem(Lang.t("gw.savestate"));
        saveState.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        saveState.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ saveState(); } });
        JMenuItem loadState = new JMenuItem(Lang.t("gw.loadstate"));
        loadState.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0));
        loadState.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ loadState(); } });
        maxItem = new JCheckBoxMenuItem(Lang.t("gw.maximize"),
                    (getExtendedState() & Frame.MAXIMIZED_BOTH)!=0 || cfg.gameMaximized);
        maxItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0));
        maxItem.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ toggleMaximize(); } });
        JMenuItem close = new JMenuItem(Lang.t("gw.close"));
        close.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
        close.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ close(); } });
        // captura de tela: F12 = so o jogo, Shift+F12 = a janela inteira (com as barras preta)
        JMenu shot = new JMenu(Lang.t("gw.screenshot"));
        JMenuItem shotGame = new JMenuItem(Lang.t("shot.game"));
        shotGame.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0));
        shotGame.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ takeShot(false); } });
        JMenuItem shotFull = new JMenuItem(Lang.t("shot.full"));
        shotFull.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F12, InputEvent.SHIFT_DOWN_MASK));
        shotFull.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ takeShot(true); } });
        JMenuItem shotDir = new JMenuItem(Lang.t("shot.open"));
        shotDir.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openShotsDir(); } });
        shot.add(shotGame); shot.add(shotFull); shot.addSeparator(); shot.add(shotDir);

        emu.add(pauseItem); emu.add(reset); emu.addSeparator();
        emu.add(maxItem); emu.add(shot); emu.addSeparator();
        emu.add(saveState); emu.add(loadState); emu.addSeparator(); emu.add(close);
        bar.add(emu);
        setJMenuBar(bar);
    }

    void startSession() throws Exception {
        session = new GameSession(mpn, name, cfg);
        session.init();
        Canvas c = session.getCanvas();
        getContentPane().removeAll();
        getContentPane().setBackground(Color.BLACK);
        // o canvas OCUPA todo o painel (antes era centralizado no tamanho preferido): assim
        // ele acompanha o redimensionamento e o Screen.render() escala o quadro sozinho,
        // preservando a proporcao do aparelho e pintando as barras pretas.
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Color.BLACK);
        holder.add(c, BorderLayout.CENTER);
        add(holder, BorderLayout.CENTER);
        setResizable(true);
        boolean primeira = !isVisible();
        if((getExtendedState() & Frame.MAXIMIZED_BOTH) != 0){
            validate();                      // ja maximizada (Reiniciar): so refaz o layout
        } else {
            pack();                          // tamanho nativo = w*scale x h*scale
            if(primeira){
                setMinimumSize(new Dimension(Math.min(240,getWidth()), Math.min(200,getHeight())));
                setLocationRelativeTo(null);
                if(cfg.gameMaximized) setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
            }
        }
        setVisible(true);
        session.start();
        c.requestFocus();
    }

    /** F11 / menu: alterna entre maximizada e o tamanho nativo (w*scale). */
    void toggleMaximize(){
        boolean max = (getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        setExtendedState(max ? Frame.NORMAL : (getExtendedState() | Frame.MAXIMIZED_BOTH));
        cfg.gameMaximized = !max; cfg.save();
        if(maxItem!=null) maxItem.setSelected(!max);
        if(session!=null && session.getCanvas()!=null) session.getCanvas().requestFocus();
    }

    void togglePause(){
        if(session==null) return;
        if(session.isPaused()){ session.resume(); pauseItem.setText(Lang.t("gw.pause")); }
        else { session.pause(); pauseItem.setText(Lang.t("gw.resume")); }
    }

    void reset(){
        try {
            closing=true;                     // pausa a deteccao de "encerrado" durante a troca de sessao
            if(session!=null) session.stop();
            startSession();
            pauseItem.setText(Lang.t("gw.pause"));
            lastFlips=0; closing=false;
        } catch(Exception ex){
            closing=false;
            JOptionPane.showMessageDialog(this, Lang.t("err.restart")+" "+ex, Lang.t("dlg.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // o JOGO terminou por conta propria (softkey de "sair" no menu / Quit / fim de task): NAO congela
    // em "encerrado" -> oferece Reiniciar ou voltar a biblioteca. Save nativo ja persistido (flush por write).
    void onGameEnded(){
        if(closing) return; closing=true;
        if(statusTimer!=null) statusTimer.stop();
        try { if(session!=null) session.stop(); } catch(Throwable t){}   // flush do save (idempotente)
        setTitle(name+" - "+Branding.APP_NAME+" [encerrado]");
        Object[] opts = {Lang.t("gw.restartgame"), Lang.t("gw.backlib")};
        int ch = JOptionPane.showOptionDialog(this,
            Lang.t("gw.gameended"),
            name+" - "+Lang.t("st.ended"), JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
        if(ch==0){                                                        // Reiniciar: nova sessao, fica na janela
            try { startSession(); pauseItem.setText(Lang.t("gw.pause")); lastFlips=0; closing=false; statusTimer.start(); return; }
            catch(Exception ex){ /* se falhar, cai p/ voltar a biblioteca */ }
        }
        dispose();
        if(onClose!=null) onClose.run();                                  // reabre a biblioteca
    }

    File stateFile(){ return new File(cfg.savesDir(name), "savestate.bin"); }
    // ---- captura de tela ----
    // Sai na RESOLUCAO DA JANELA, nao na do jogo: o Screen compoe o quadro para o tamanho do
    // canvas, do mesmo jeito que ele ja faz para a tela. (O savePng() continua dando o
    // framebuffer cru, que e o que os modos headless/--snap precisam.)
    private String shotMsg; private long shotMsgUntil;
    void takeShot(boolean comBarras){
        wolphun.Screen scr = session!=null ? session.getScreen() : null;
        java.awt.image.BufferedImage img = scr!=null ? scr.capture(comBarras) : null;
        if(img==null){ aviso(Lang.t("shot.notready"), 2500); return; }
        String base = name.replaceAll("(?i)\\.mpn$","").replaceAll("[^A-Za-z0-9_.-]","_");
        if(base.length()>48) base = base.substring(0,48);
        if(base.isEmpty()) base = "wolphun";
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        File f = new File(Config.shotsDir(), base+"_"+ts+".png");
        try {
            javax.imageio.ImageIO.write(img, "png", f);
            aviso(Lang.t("shot.saved", img.getWidth(), img.getHeight()), 3000);
        } catch(Throwable t){
            JOptionPane.showMessageDialog(this, Lang.t("shot.fail")+" "+t, Lang.t("dlg.error"), JOptionPane.ERROR_MESSAGE);
        }
    }
    void openShotsDir(){
        File d = Config.shotsDir();
        try { java.awt.Desktop.getDesktop().open(d); }
        catch(Throwable t){
            JOptionPane.showMessageDialog(this, d.getAbsolutePath(), Lang.t("shot.open"), JOptionPane.INFORMATION_MESSAGE);
        }
    }
    /** recado curto na barra de titulo (o timer de FPS respeita o prazo). */
    private void aviso(String msg, long ms){
        shotMsg = msg; shotMsgUntil = System.currentTimeMillis()+ms;
        setTitle(name+" - "+Branding.APP_NAME+" ["+msg+"]");
    }

    void saveState(){
        if(session==null) return;
        try { session.saveState(stateFile());
            setTitle(name+" - "+Branding.APP_NAME+" ["+Lang.t("st.statesaved")+"]");
        } catch(Throwable t){ JOptionPane.showMessageDialog(this,Lang.t("err.savestate")+" "+t,Lang.t("dlg.error"),JOptionPane.ERROR_MESSAGE); }
    }
    void loadState(){
        if(session==null) return;
        File sf = stateFile();
        if(!sf.isFile()){ JOptionPane.showMessageDialog(this,Lang.t("dlg.nostate"),Lang.t("gw.loadstate"),JOptionPane.INFORMATION_MESSAGE); return; }
        try { session.loadState(sf);
            setTitle(name+" - "+Branding.APP_NAME+" ["+Lang.t("st.stateloaded")+"]");
        } catch(Throwable t){ JOptionPane.showMessageDialog(this,Lang.t("err.loadstate")+" "+t,Lang.t("dlg.error"),JOptionPane.ERROR_MESSAGE); }
    }

    void close(){
        closing=true;
        if(statusTimer!=null) statusTimer.stop();
        if(session!=null) session.stop();       // stop() ja faz flush do save nativo
        dispose();
        if(onClose!=null) onClose.run();
    }
    // usado pelo shutdown hook do Emulator: garante o flush do save nativo se a JVM sair
    // com o jogo ainda aberto (fechar o emulador principal). Nao mexe na GUI.
    public void stopSession(){ try { if(session!=null) session.stop(); } catch(Throwable t){} }
}
