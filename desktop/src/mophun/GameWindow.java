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
        // atualiza FPS no titulo a cada segundo; se o JOGO terminar sozinho (ex.: softkey de
        // "sair" no menu, ou opcao Quit), NAO congela em "encerrado" -> volta a biblioteca.
        statusTimer = new javax.swing.Timer(500, new ActionListener(){
            public void actionPerformed(ActionEvent e){
                if(session==null || closing) return;
                if(session.error!=null){ setTitle(name+" - "+Branding.APP_NAME+" [erro]"); onGameEnded(); return; }
                if(session.isStopped()){ onGameEnded(); return; }   // jogo saiu sozinho -> volta a biblioteca
                int f = session.getScreen()!=null ? session.getScreen().flips : 0;
                int fps = f-lastFlips; lastFlips=f;
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
        JMenuItem close = new JMenuItem(Lang.t("gw.close"));
        close.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
        close.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ close(); } });
        emu.add(pauseItem); emu.add(reset); emu.addSeparator();
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
        JPanel holder = new JPanel(new GridBagLayout());   // centraliza o canvas
        holder.setBackground(Color.BLACK);
        holder.add(c);
        add(holder, BorderLayout.CENTER);
        setResizable(false);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        session.start();
        c.requestFocus();
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
            JOptionPane.showMessageDialog(this, "Falha ao reiniciar: "+ex, "Erro", JOptionPane.ERROR_MESSAGE);
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
            name+" - jogo encerrado", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
        if(ch==0){                                                        // Reiniciar: nova sessao, fica na janela
            try { startSession(); pauseItem.setText(Lang.t("gw.pause")); lastFlips=0; closing=false; statusTimer.start(); return; }
            catch(Exception ex){ /* se falhar, cai p/ voltar a biblioteca */ }
        }
        dispose();
        if(onClose!=null) onClose.run();                                  // reabre a biblioteca
    }

    File stateFile(){ return new File(cfg.savesDir(name), "savestate.bin"); }
    void saveState(){
        if(session==null) return;
        try { session.saveState(stateFile());
            setTitle(name+" - "+Branding.APP_NAME+" [estado salvo]");
        } catch(Throwable t){ JOptionPane.showMessageDialog(this,"Falha ao salvar estado: "+t,"Erro",JOptionPane.ERROR_MESSAGE); }
    }
    void loadState(){
        if(session==null) return;
        File sf = stateFile();
        if(!sf.isFile()){ JOptionPane.showMessageDialog(this,"Nenhum estado salvo para este jogo.","Sem estado",JOptionPane.INFORMATION_MESSAGE); return; }
        try { session.loadState(sf);
            setTitle(name+" - "+Branding.APP_NAME+" [estado carregado]");
        } catch(Throwable t){ JOptionPane.showMessageDialog(this,"Falha ao carregar estado: "+t,"Erro",JOptionPane.ERROR_MESSAGE); }
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
