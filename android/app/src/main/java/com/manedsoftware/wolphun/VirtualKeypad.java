package com.manedsoftware.wolphun;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import wolphun.Screen;

/**
 * Teclado virtual translúcido, CUSTOMIZÁVEL por jogo (estilo J2ME-Loader/PPSSPP):
 * cada botão pode ser MOVIDO (centro salvo como fração da tela) e todos podem ser
 * REDIMENSIONADOS por um fator global. layout() recalcula pelo tamanho da tela e
 * aplica as customizações; exportLayout()/importLayout() persistem por jogo.
 */
public final class VirtualKeypad {
    public static final int MENU = -1;
    // botões de TECLA NUMÉRICA literal: bit = KEYPAD_FLAG | ascii (não colide com os bits mophun <=0x100).
    // Emitem scr.setKeypad(ascii) em vez de um bit de botão. Usados por jogos tipo Bomb Jack (andar = '2'/'3').
    public static final int KEYPAD_FLAG = 0x10000;
    // ordem do teclado de telefone: 1 2 3 / 4 5 6 / 7 8 9 / * 0 #
    static final char[] KEYPAD_CHARS = {'1','2','3','4','5','6','7','8','9','*','0','#'};
    // TECLAS DO APARELHO (vmgpsonyericsson.h), que nao tem bit de botao equivalente e por isso
    // precisam de botao proprio: 203 = tecla vermelha "NO" (voltar/sair do jogo) e 204 = tecla "C"
    // (apagar; em alguns jogos e o pause). Descobertas pela analise estatica das ROMs -- pedem 203 o
    // Ice Digger, Nostalgia Ball, Bomb Jack, SAAF e Something Fishy ("no BACK function" na planilha),
    // e pedem 204 o 4-in-1 (o pause do jogo "Jake") e o Kely Slater.
    public static final int PHONE_BACK = 203, PHONE_CLEAR = 204;
    // ÍCONES VETORIAIS. Começamos com "←"/"→" e depois com o emoji do bonequinho, e os dois
    // falharam: em muitos aparelhos a fonte do sistema mapeia U+2190/U+2192 para glifos ERRADOS
    // (aparecia "Ĳ" e "ŉ" -- são caracteres reais, então nem Paint.hasGlyph detecta), e emoji ZWJ
    // depende da versão do Android. Desenhar em vetor é a única forma de sair igual em todo aparelho.
    public static final int ICON_WALK_L = 1, ICON_WALK_R = 2;

    // ---- PRESETS DE LAYOUT (menu "Alterar layout dos botões", como no J2ME Loader) ----
    // AUTO respeita o que a análise da ROM detectou; os demais são escolha explícita do usuário e
    // ignoram a detecção -- inclusive TUDO, que mostra todos os botões mesmo que a ROM não os leia.
    public static final int LAY_AUTO=0, LAY_GAMEPAD=1, LAY_PHONE=2, LAY_NUM_CTRL=3,
                            LAY_CTRL_NUM=4, LAY_NUMBERS=5, LAY_ARROWS=6, LAY_ALL=7;
    public static final int LAY_COUNT = 8;
    private int preset = LAY_AUTO;
    public void setLayoutPreset(int p){ preset = (p>=0 && p<LAY_COUNT) ? p : LAY_AUTO; recompute(); }
    public int getLayoutPreset(){ return preset; }

    public static final class Btn {
        final int bit; final String label; final boolean round;
        boolean visible = true;   // botões numéricos começam ocultos (ligados por jogo via setShowNumbers)
        boolean phone = false;    // tecla do APARELHO (203/204): visibilidade própria, separada dos dígitos
        int icon = 0;             // 0 = desenha o rótulo; ICON_WALK_L/R = desenha o bonequinho
        int grp  = GRP_NONE;      // grupo a que pertence NESTE layout (definido em put())
        final RectF rect = new RectF();
        Float cxFrac, cyFrac;    // centro customizado (fração 0..1 da tela); null = usa o default
        float scale = 1f;        // tamanho individual (só p/ botões não-dpad; o d-pad usa dpadScale de grupo)
        Btn(int bit, String label, boolean round){ this.bit=bit; this.label=label; this.round=round; }
    }
    static boolean isDpad(int bit){ return bit==Screen.UP||bit==Screen.DOWN||bit==Screen.LEFT||bit==Screen.RIGHT; }
    static float clampf(float v){ return v<0?0:v>1?1:v; }

    private final List<Btn> btns = new ArrayList<Btn>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillMenu = new Paint(Paint.ANTI_ALIAS_FLAG);   // botao de opcoes (≡): fundo OPACO p/ flutuar visivel sobre o jogo
    private final Paint edit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeMenu = new Paint(Paint.ANTI_ALIAS_FLAG); // borda de destaque do botao de opcoes
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);   // ícones vetoriais (bonequinho)

    private int viewW, viewH;
    // Dois grupos INDEPENDENTES sobre as mesmas teclas: "andar" (só '2'/'3', com o bonequinho) e
    // "teclado numérico completo" (os 12 dígitos). Quem só anda não precisa de grade nenhuma.
    private boolean walkMode = false, numbersVisible = false;
    private java.util.Set<Integer> phoneCodes = null;   // teclas de aparelho que a ROM consulta

    public VirtualKeypad(){
        fill.setColor(0x55FFFFFF);
        fillDown.setColor(0xAAE8451F);
        fillMenu.setColor(0xCC4A4A52);   // cinza-medio semi-opaco: visivel sobre PRETO (letterbox) E sobre cenas claras
        edit.setColor(0x66E8451F);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(2f); stroke.setColor(0x88FFFFFF);
        strokeMenu.setStyle(Paint.Style.STROKE); strokeMenu.setStrokeWidth(3f); strokeMenu.setColor(0xFFE8451F);   // borda accent
        text.setColor(0xEEFFFFFF); text.setTextAlign(Paint.Align.CENTER);

        btns.add(new Btn(Screen.UP,    "▲", false));
        btns.add(new Btn(Screen.DOWN,  "▼", false));
        btns.add(new Btn(Screen.LEFT,  "◀", false));
        btns.add(new Btn(Screen.RIGHT, "▶", false));
        btns.add(new Btn(Screen.FIRE,  "OK", true));       // acao principal / confirmar
        btns.add(new Btn(Screen.FIRE2, "B", true));        // 2a acao (era "F2", confuso) -> "B" (padrao gamepad)
        btns.add(new Btn(Screen.SELECT,"BACK", true));     // softkey esq. -> a maioria dos jogos VOLTA/SAI com ela ("SEL"/"SELECT" confundia)
        btns.add(new Btn(MENU,         "≡", true));
        // teclas numéricas 2/3 (ocultas por padrão; ligadas por jogo). No Bomb Jack ANDAM esq/dir,
        // por isso os rótulos são setas clássicas ← → (mais claras que "2"/"3").
        // TECLADO NUMERICO COMPLETO (0-9, * e #). Antes existiam so o '2' e o '3', criados p/ o Bomb Jack,
        // mas o censo de entrada mostrou uma familia inteira de jogos que le DIGITOS por vScanKeys/vTestKey:
        // o Number Drop (BUG-095) faz 7,3 MILHOES de vScanKeys contra 2 de vGetButtonData -- sem as teclas
        // na tela ele e injogavel no Android (no desktop o teclado fisico ja supre). Tambem usam digitos:
        // SAAF, Brain Vita, Revs!, Ace e 4-in-1. O '2'/'3' mantem os rotulos de seta p/ nao desfazer o
        // ajuste que ja existia no Bomb Jack (andar esq/dir); os demais mostram o proprio digito.
        for(char c : KEYPAD_CHARS){
            Btn k = new Btn(KEYPAD_FLAG|c, String.valueOf(c), true); k.visible=false; btns.add(k);
        }
        // teclas do aparelho. Rótulo por extenso e traduzido ("APAGAR"/"SAIR"), não a letra "C"
        // impressa no celular: quem usa o emulador não tem o aparelho na mão p/ associar.
        // Cada uma aparece SOZINHA, só no jogo cuja ROM consulta aquele código (ver KeyScan).
        for(int code : new int[]{ PHONE_CLEAR, PHONE_BACK }){
            Btn k = new Btn(KEYPAD_FLAG|code, Lang.t(code==PHONE_CLEAR ? "kp.clear" : "kp.exit"), true);
            k.visible=false; k.phone=true; btns.add(k);
        }
    }

    /** mostra/oculta o botão ≡ (MENU) desenhado no canvas. */
    public void setMenuVisible(boolean on){
        for(Btn b: btns) if(b.bit==MENU) b.visible = on;
    }

    /** liga/desliga o TECLADO NUMÉRICO COMPLETO no preset automático — por jogo. */
    public void setShowNumbers(boolean on){ numbersVisible = on; recompute(); }

    /**
     * Teclas do aparelho que a ROM REALMENTE consulta (vem do KeyScan, análise no carregamento).
     * No preset automático só aparece o que está aqui; o preset "tudo" ignora e mostra os dois.
     */
    public void setPhoneKeys(java.util.Set<Integer> codes){ phoneCodes = codes; recompute(); }
    public void hidePhoneKeys(){ phoneCodes = null; recompute(); }

    /**
     * Marca '2'/'3' como MOVIMENTO (andar esq/dir): mostram o bonequinho em vez do dígito. Só p/ quem
     * anda com essas teclas (Bomb Jack); nos outros o teclado é numérico e o dígito é o rótulo certo.
     */
    public void setWalkKeys(boolean on){ walkMode = on; recompute(); }

    public void layout(int w, int h){ viewW=w; viewH=h; recompute(); }

    /**
     * Quais botões o preset atual mostra. NÃO chama recompute() (seria recursão): quem chama é o
     * próprio recompute(), antes de posicionar.
     */
    private void applyVisibility(){
        boolean nums, dpad = true, acoes = true, todosFone = false;
        switch(preset){
            case LAY_GAMEPAD:  nums = false; break;
            case LAY_PHONE:    nums = true;  break;
            case LAY_NUM_CTRL:
            case LAY_CTRL_NUM: nums = true;  break;
            case LAY_NUMBERS:  nums = true;  dpad = false; acoes = false; break;
            case LAY_ARROWS:   nums = false; break;
            case LAY_ALL:      nums = true;  todosFone = true; break;
            default:           nums = numbersVisible; break;          // LAY_AUTO
        }
        for(Btn b: btns){
            // MENU=-1 tem de ser testado ANTES do KEYPAD_FLAG: -1 & KEYPAD_FLAG != 0, entao ele caia
            // no ramo das teclas numericas e voltava a aparecer junto com elas -- desenhando um
            // SEGUNDO botao de opcoes no canvas, atras do botao real da Activity.
            if(b.bit == MENU) continue;
            int code = b.bit & 0xFF;
            if(b.phone){
                b.visible = todosFone || (phoneCodes!=null && phoneCodes.contains(code));
            } else if((b.bit & KEYPAD_FLAG)!=0){
                char c = (char)code;
                boolean andar = walkMode && !nums && (c=='2' || c=='3');
                b.icon    = andar ? (c=='2' ? ICON_WALK_L : ICON_WALK_R) : 0;
                b.visible = nums || andar;
            } else if(isDpad(b.bit)){
                b.visible = dpad;
            } else if(b.bit==Screen.FIRE){
                b.visible = acoes || preset==LAY_ARROWS;   // sem o OK não dá p/ confirmar nada
            } else if(b.bit==Screen.FIRE2 || b.bit==Screen.SELECT){
                b.visible = acoes && preset!=LAY_ARROWS;
            }
            // MENU nunca some: é a única forma de reabrir as opções
        }
    }

    // ---- GRUPOS EDITAVEIS. Cada bloco (d-pad, teclado numerico, acoes) move e escala JUNTO, como no
    //      J2ME Loader: arrastar uma tecla arrasta o bloco todo, pincar redimensiona o bloco todo.
    //      A qual grupo cada botao pertence depende do LAYOUT e e definido na hora de posicionar
    //      (no layout de telefone as softkeys fazem parte da cruz; no de gamepad sao bloco proprio).
    public static final int GRP_NONE=0, GRP_DPAD=1, GRP_NUM=2, GRP_ACT=3, GRP_COUNT=4;
    // [orientacao][grupo] -- EM PE e DEITADO guardam arranjos SEPARADOS. Uma fracao boa em pe fica
    // pessima deitada (a tela troca de proporcao), entao reaproveitar o mesmo valor jogaria bloco
    // p/ fora da tela. E tambem o que o J2ME Loader faz: cada orientacao tem o seu lugar.
    private final float[][] grpFracX = {{-1f,-1f,-1f,-1f},{-1f,-1f,-1f,-1f}};
    private final float[][] grpFracY = {{-1f,-1f,-1f,-1f},{-1f,-1f,-1f,-1f}};
    private final float[][] grpScale = {{ 1f, 1f, 1f, 1f},{ 1f, 1f, 1f, 1f}};
    /** 0 = em pe, 1 = deitado */
    private int ori(){ return deitado() ? 1 : 0; }
    /** ancora do grupo: posicao salva pelo usuario NESTA orientacao, ou o padrao do layout */
    private float ancX(int g, float def){ int o=ori(); return grpFracX[o][g]>=0 ? grpFracX[o][g]*viewW : def; }
    private float ancY(int g, float def){ int o=ori(); return grpFracY[o][g]>=0 ? grpFracY[o][g]*viewH : def; }
    private float grpS(int g){ return grpScale[ori()][g]; }

    /** true quando a tela esta deitada: os blocos vao p/ as LATERAIS em vez de empilhar no rodape */
    private boolean deitado(){ return viewW > viewH; }

    // recalcula os retangulos conforme o PRESET e a ORIENTACAO.
    //
    // DEITADO e um problema diferente de EM PE, nao so "o mesmo layout mais largo": a tela fica baixa
    // e sobram apenas DUAS zonas confortaveis, uma de cada lado do jogo. Por isso os presets que em pe
    // usam tres blocos (numeros + d-pad + acoes) passam, deitados, a usar a CRUZ -- que ja junta d-pad,
    // OK e softkeys num bloco unico. E o arranjo do J2ME Loader: teclado de um lado, direcoes do outro.
    private void recompute(){
        if(viewW==0 || viewH==0) return;
        applyVisibility();
        boolean land = deitado();
        float m = Math.min(viewW, viewH);
        float u   = m * (land ? 0.115f : 0.11f);
        float cel = land ? m*0.135f : Math.min(viewW*0.19f, viewH*0.060f);
        float cyL = viewH*0.52f;                    // centro vertical dos blocos laterais

        switch(preset){
            case LAY_PHONE: case LAY_ALL: {
                if(land){ layGrade(viewW*0.17f, cyL, cel); layCruz(viewW*0.83f, cyL, cel); }
                else {
                    float gy = cel*1.22f, cyG = viewH - cel*1.2f - gy*1.5f;
                    layGrade(viewW*0.5f, cyG, cel);
                    layCruz (viewW*0.5f, cyG - gy*3.5f, cel);
                }
                layMenu(cel, menuX(cel, land, true)); return;
            }
            case LAY_NUMBERS: {
                float c2 = land ? m*0.165f : Math.min(viewW*0.22f, viewH*0.075f);
                // deitado o bloco vai p/ a LATERAL: centralizado ele cobria a tela do jogo
                layGrade(land ? viewW*0.20f : viewW*0.5f,
                         land ? cyL : viewH - c2*1.2f - c2*1.22f*1.5f, c2);
                layCruz (viewW*0.5f, -9999f, c2);
                layMenu(c2, viewW - c2*0.85f); return;
            }
            case LAY_ARROWS: {
                float c2 = land ? m*0.185f : Math.min(viewW*0.24f, viewH*0.085f);
                layCruz (land ? viewW*0.77f : viewW*0.5f,
                         land ? cyL : viewH - c2*2.0f, c2);
                layGrade(viewW*0.5f, -9999f, c2);
                layMenu(c2, land ? c2*0.85f : viewW - c2*0.85f); return;
            }
            case LAY_NUM_CTRL: {                        // numeros a ESQUERDA
                if(land){ layGrade(viewW*0.17f, cyL, cel); layCruz(viewW*0.83f, cyL, cel); layMenu(cel, menuX(cel, true, true)); return; }
                float c2 = Math.min(viewW*0.17f, viewH*0.050f);
                layGrade(viewW*0.5f, viewH - u*8.2f - c2*1.22f*1.5f, c2);
                layDpad (u*2.2f, viewH - u*2.2f, u);
                layAcoes(viewW - u*2.4f, viewH - u*2.4f, u);
                layMenu(u, menuX(u, false, false)); return;
            }
            case LAY_CTRL_NUM: {                        // numeros a DIREITA (espelho do anterior)
                if(land){ layCruz(viewW*0.17f, cyL, cel); layGrade(viewW*0.83f, cyL, cel); layMenu(cel, menuX(cel, true, true)); return; }
                float c2 = Math.min(viewW*0.17f, viewH*0.050f);
                float cyG = viewH - c2*1.0f - c2*1.22f*1.5f;
                layDpad (u*2.2f, cyG - c2*1.22f*2.2f - u*1.2f, u);
                layAcoes(viewW - u*2.4f, cyG - c2*1.22f*2.2f - u*1.2f, u);
                layGrade(viewW*0.5f, cyG, c2);
                layMenu(u, menuX(u, false, false)); return;
            }
            default: {                                  // LAY_AUTO e LAY_GAMEPAD
                if(numbersVisible){
                    // Jogo que pede os 12 digitos NAO cabe no arranjo de gamepad: a grade batia no
                    // bloco de acao. Nesse caso o arranjo de telefone e o certo, em pe ou deitado.
                    if(land){ layGrade(viewW*0.17f, cyL, cel); layCruz(viewW*0.83f, cyL, cel); }
                    else {
                        float gy2 = cel*1.22f, cyG2 = viewH - cel*1.2f - gy2*1.5f;
                        layGrade(viewW*0.5f, cyG2, cel);
                        layCruz (viewW*0.5f, cyG2 - gy2*3.5f, cel);
                    }
                    layMenu(cel, menuX(cel, land, true)); return;
                }
                float bx = land ? u*2.0f : u*2.2f;
                float by = land ? cyL     : viewH - u*2.2f;
                layDpad (bx, by, u);
                // deitado a tela e baixa: o bloco de acao (5 botoes, 4u de altura) nao cabe centrado,
                // o SAIR do topo saia da tela -- por isso desce um pouco em relacao ao d-pad.
                layAcoes(viewW - (land ? u*2.2f : u*2.4f), land ? by + u*0.8f : by, u);
                layAndar(bx, by - u*2.6f, u);
                layMenu(u, menuX(u, land, true)); return;
            }
        }
    }

    /**
     * Cruz de navegacao com o OK no meio e as 4 quinas (softkeys em cima, teclas do aparelho embaixo).
     * Tudo isso e UM grupo: arrastar qualquer peca move o bloco inteiro.
     */
    private void layCruz(float cx, float cy, float cel){
        int g = GRP_DPAD;
        float c = cel * grpS(g);
        float ax = ancX(g, cx), ay = ancY(g, cy);
        float gx = c*1.32f, gy = c*1.22f;
        put(Screen.UP,    g, ax,    ay-gy, c);
        put(Screen.DOWN,  g, ax,    ay+gy, c);
        put(Screen.LEFT,  g, ax-gx, ay,    c);
        put(Screen.RIGHT, g, ax+gx, ay,    c);
        put(Screen.FIRE,   g, ax,    ay,    c*1.05f);
        put(Screen.SELECT, g, ax-gx, ay-gy, c);
        put(Screen.FIRE2,  g, ax+gx, ay-gy, c);
        put(KEYPAD_FLAG|PHONE_BACK,  g, ax-gx, ay+gy, c);
        put(KEYPAD_FLAG|PHONE_CLEAR, g, ax+gx, ay+gy, c);
    }

    /** grade 3x4 do telefone (1 2 3 / 4 5 6 / 7 8 9 / * 0 #). (cx,cy) = CENTRO do bloco. */
    private void layGrade(float cx, float cy, float cel){
        int g = GRP_NUM;
        float c = cel * grpS(g);
        float ax = ancX(g, cx), ay = (cy < -1000f) ? cy : ancY(g, cy);
        float gx = c*1.32f, gy = c*1.22f;
        for(int i=0;i<KEYPAD_CHARS.length;i++)
            put(KEYPAD_FLAG|KEYPAD_CHARS[i], g, ax + (i%3-1)*gx, ay + (i/3-1.5f)*gy, c);
    }

    /** d-pad sozinho (layouts de gamepad). (cx,cy) = centro da cruz. */
    private void layDpad(float cx, float cy, float u){
        int g = GRP_DPAD;
        float du = u * grpS(g);
        float ax = ancX(g, cx), ay = ancY(g, cy);
        put(Screen.UP,    g, ax,    ay-du, du);
        put(Screen.DOWN,  g, ax,    ay+du, du);
        put(Screen.LEFT,  g, ax-du, ay,    du);
        put(Screen.RIGHT, g, ax+du, ay,    du);
    }

    /**
     * Bloco de acao: OK grande embaixo/direita, B ao lado, e acima deles BACK e APAGAR; o SAIR fica
     * no topo da coluna. Ficava espalhado por viewW/viewH solto -- agora e um grupo ancorado, o que
     * deixa o mesmo codigo servir em pe e deitado.
     */
    private void layAcoes(float cx, float cy, float u){
        int g = GRP_ACT;
        float s = u * grpS(g);
        float ax = ancX(g, cx), ay = ancY(g, cy);
        put(Screen.FIRE,   g, ax,          ay,          s*1.6f);
        put(Screen.FIRE2,  g, ax - s*2.0f, ay,          s*1.2f);
        put(Screen.SELECT, g, ax,          ay - s*2.0f, s*1.2f);
        put(KEYPAD_FLAG|PHONE_CLEAR, g, ax - s*2.0f, ay - s*2.0f, s*1.2f);
        put(KEYPAD_FLAG|PHONE_BACK,  g, ax,          ay - s*4.0f, s*1.2f);
    }

    /** as duas teclas de ANDAR (Bomb Jack): acompanham o d-pad, mas com posicao propria por botao */
    private void layAndar(float cx, float cy, float u){
        float du = u * grpS(GRP_DPAD);
        float ax = ancX(GRP_DPAD, cx), ay = ancY(GRP_DPAD, cy + du*2.6f) - du*2.6f;
        placeInd(KEYPAD_FLAG|'2', ax - u*0.85f, ay, u*1.2f);
        placeInd(KEYPAD_FLAG|'3', ax + u*0.85f, ay, u*1.2f);
    }

    private void layMenu(float base, float cx){
        placeInd(MENU, cx, base*1.1f, base*1.15f);
    }
    /** x padrao do menu: canto direito; deitado com blocos NAS LATERAIS vai p/ o topo central. */
    private float menuX(float base, boolean land, boolean lateral){
        return (land && lateral) ? viewW*0.5f : viewW - base*0.85f;
    }

    /** posiciona um botao de GRUPO: guarda a qual grupo ele pertence NESTE layout */
    private void put(int bit, int grp, float cx, float cy, float size){
        for(Btn b: btns) if(b.bit==bit){
            b.grp = grp;
            b.rect.set(cx-size/2, cy-size/2, cx+size/2, cy+size/2);
            return;
        }
    }

    // posiciona um botão do d-pad (centro absoluto derivado do grupo)
    private void placeAbs(int bit, float cx, float cy, float size){
        for(Btn b: btns) if(b.bit==bit){ b.rect.set(cx-size/2, cy-size/2, cx+size/2, cy+size/2); return; }
    }
    // posiciona um botão individual: centro custom (cxFrac) ou default; tamanho = base * escala do botão
    private void placeInd(int bit, float defCx, float defCy, float baseSize){
        for(Btn b: btns) if(b.bit==bit){
            float size = baseSize * b.scale;
            float cx = b.cxFrac!=null ? b.cxFrac*viewW : defCx;
            float cy = b.cyFrac!=null ? b.cyFrac*viewH : defCy;
            b.rect.set(cx-size/2, cy-size/2, cx+size/2, cy+size/2);
            return;
        }
    }

    /**
     * Bonequinho andando, em vetor. 'right' espelha a figura; a passada e o braço à frente já dão a
     * direção, e a setinha embaixo remove qualquer dúvida num botão pequeno.
     */
    private void drawWalker(Canvas c, float cx, float cy, float size, boolean right){
        float s2 = size*0.39f, dir = right ? 1f : -1f;   // 0.39 (e nao 0.5): a figura inteira TEM de caber dentro do circulo
        icon.setColor(0xEEFFFFFF);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        float lw = Math.max(2f, size*0.075f);
        cy -= s2*0.08f;                                   // sobe um pouco: a seta ocupa a base
        float headR = s2*0.20f;
        float hx = cx + dir*s2*0.10f, hy = cy - s2*0.60f;
        icon.setStyle(Paint.Style.FILL);
        c.drawCircle(hx, hy, headR, icon);                // cabeça
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(lw);
        float qx = cx - dir*s2*0.04f, qy = cy + s2*0.14f; // quadril
        c.drawLine(hx, hy+headR, qx, qy, icon);           // tronco
        float ox = hx + dir*s2*0.02f, oy = hy + headR + s2*0.14f;   // ombro
        c.drawLine(ox, oy, ox + dir*s2*0.40f, cy - s2*0.06f, icon); // braço da frente
        c.drawLine(ox, oy, ox - dir*s2*0.32f, cy + s2*0.10f, icon); // braço de trás
        c.drawLine(qx, qy, qx + dir*s2*0.38f, cy + s2*0.66f, icon); // perna da frente
        c.drawLine(qx, qy, qx - dir*s2*0.30f, cy + s2*0.70f, icon); // perna de trás
        // seta de direção na base
        float ay = cy + s2*0.92f, ax = cx + dir*s2*0.46f, aw = s2*0.30f;
        icon.setStrokeWidth(lw*0.85f);
        c.drawLine(cx - dir*s2*0.34f, ay, ax, ay, icon);
        c.drawLine(ax - dir*aw*0.55f, ay - aw*0.42f, ax, ay, icon);
        c.drawLine(ax - dir*aw*0.55f, ay + aw*0.42f, ax, ay, icon);
    }

    public void draw(Canvas c, Screen s, boolean menuDown, boolean editMode){
        for(Btn x: btns){
            if(!x.visible) continue;
            boolean down;
            if(x.bit==MENU) down = menuDown;
            else if((x.bit & KEYPAD_FLAG)!=0) down = s.keypadPressed(x.bit & 0xFF);
            else down = (s.getButtons() & x.bit)!=0;
            boolean isMenu = (x.bit==MENU);
            // botao de opcoes ocioso: fundo OPACO + borda accent -> sempre flutua visivel sobre o jogo
            Paint p = editMode ? edit : (down ? fillDown : (isMenu ? fillMenu : fill));
            Paint st = (isMenu && !editMode && !down) ? strokeMenu : stroke;
            if(x.round){
                float rad = Math.min(x.rect.width(), x.rect.height())/2f, mx=x.rect.centerX(), my=x.rect.centerY();
                c.drawCircle(mx,my,rad,p); c.drawCircle(mx,my,rad,st);
            } else {
                float r = x.rect.height()*0.18f;
                c.drawRoundRect(x.rect, r, r, p); c.drawRoundRect(x.rect, r, r, st);
            }
            if(x.icon!=0){ drawWalker(c, x.rect.centerX(), x.rect.centerY(),
                                      Math.min(x.rect.width(), x.rect.height()),
                                      x.icon==ICON_WALK_R); continue; }
            // tamanho do texto AUTO-AJUSTADO ao rotulo, MEDINDO a largura de verdade. O calculo
            // antigo estimava por label.length(), o que arruina emoji: uma sequencia ZWJ como o
            // bonequinho andando conta 7 chars UTF-16 e o glifo saia microscopico.
            float diam = Math.min(x.rect.width(), x.rect.height());
            float base = Math.min(viewW, viewH) * 0.055f;                 // ~ u*0.5
            text.setTextSize(base);
            float wTxt = text.measureText(x.label), maxW = diam*0.78f;
            if(wTxt > maxW) text.setTextSize(Math.max(diam*0.22f, base*maxW/wTxt));
            c.drawText(x.label, x.rect.centerX(), x.rect.centerY() + text.getTextSize()*0.35f, text);
        }
    }

    /** bit do botão sob (x,y), ou 0 se nenhum. (bit codificado KEYPAD_FLAG|ascii p/ teclas numéricas) */
    public int hitTest(float x, float y){
        for(Btn b: btns) if(b.visible && b.rect.contains(x,y)) return b.bit;
        return 0;
    }
    /** o Btn sob (x,y) (p/ arrastar no modo edição), ou null. */
    public Btn hitButton(float x, float y){
        for(Btn b: btns) if(b.visible && b.rect.contains(x,y)) return b;
        return null;
    }
    /**
     * Arrasta o BLOCO a que o botão pertence (d-pad, teclado numérico ou ações), mantendo a tecla
     * tocada sob o dedo. Antes só o d-pad andava em grupo e mover o teclado numérico exigia arrastar
     * as 12 teclas uma a uma. Botão sem grupo (menu, teclas de andar) continua individual.
     */
    public void moveButton(Btn b, float cx, float cy){
        if(b.grp != GRP_NONE){
            int g = b.grp, o = ori();
            float ancoraX = grpFracX[o][g]>=0 ? grpFracX[o][g]*viewW : b.rect.centerX();
            float ancoraY = grpFracY[o][g]>=0 ? grpFracY[o][g]*viewH : b.rect.centerY();
            float offX = b.rect.centerX() - ancoraX, offY = b.rect.centerY() - ancoraY;
            grpFracX[o][g] = clampf((cx-offX)/viewW);
            grpFracY[o][g] = clampf((cy-offY)/viewH);
        } else {
            b.cxFrac = clampf(cx/viewW); b.cyFrac = clampf(cy/viewH);
        }
        recompute();
    }

    /** escala atual do alvo (o BLOCO, se o botão pertence a um) — base p/ a pinça. */
    public float startScaleOf(Btn b){ return b.grp!=GRP_NONE ? grpS(b.grp) : b.scale; }
    /** define a escala do alvo: pinçar uma tecla redimensiona o BLOCO inteiro. */
    public void setScaleOf(Btn b, float s){
        s = Math.max(0.5f, Math.min(2.5f, s));
        if(b.grp!=GRP_NONE) grpScale[ori()][b.grp] = s; else b.scale = s;
        recompute();
    }

    public void resetLayout(){
        for(Btn b: btns){ b.cxFrac=null; b.cyFrac=null; b.scale=1f; }
        for(int o=0;o<2;o++) for(int g=0; g<GRP_COUNT; g++){
            grpFracX[o][g]=-1f; grpFracY[o][g]=-1f; grpScale[o][g]=1f;
        }
        recompute();
    }

    // serializa: "D:dx,dy,dscale|bit:cx,cy,scale|..." (grupo d-pad + botões individuais alterados)
    public String exportLayout(){
        StringBuilder sb = new StringBuilder();
        for(int o=0;o<2;o++) for(int g=1; g<GRP_COUNT; g++)
            sb.append(sb.length()>0?"|":"").append('G').append(o).append(g).append(':')
              .append(grpFracX[o][g]).append(',').append(grpFracY[o][g]).append(',').append(grpScale[o][g]);
        for(Btn b: btns){
            if(b.grp!=GRP_NONE) continue;      // quem esta num bloco e salvo pelo bloco
            boolean moved = b.cxFrac!=null && b.cyFrac!=null, scaled = b.scale!=1f;
            if(moved || scaled){
                float cx = b.cxFrac!=null?b.cxFrac:-1f, cy = b.cyFrac!=null?b.cyFrac:-1f;
                sb.append('|').append(b.bit).append(':').append(cx).append(',').append(cy).append(',').append(b.scale);
            }
        }
        return sb.toString();
    }
    public void importLayout(String s){
        resetLayout();
        if(s==null || s.isEmpty()) return;
        try {
            for(String parte : s.split("\\|")){
                int dp = parte.indexOf(':');
                if(dp<0) continue;
                String chave = parte.substring(0, dp);
                String[] v = parte.substring(dp+1).split(",");
                if(v.length<3) continue;
                if(chave.length()==3 && chave.charAt(0)=='G'){          // grupo: G<orientacao><grupo>
                    int o = chave.charAt(1)-'0', g = chave.charAt(2)-'0';
                    if(o<0||o>1||g<0||g>=GRP_COUNT) continue;
                    grpFracX[o][g]=Float.parseFloat(v[0]);
                    grpFracY[o][g]=Float.parseFloat(v[1]);
                    grpScale[o][g]=Float.parseFloat(v[2]);
                } else if(chave.charAt(0)!='G' && chave.charAt(0)!='D'){ // botao solto: <bit>
                    int bit = Integer.parseInt(chave);
                    for(Btn b: btns) if(b.bit==bit){
                        float cx=Float.parseFloat(v[0]), cy=Float.parseFloat(v[1]);
                        if(cx>=0 && cy>=0){ b.cxFrac=cx; b.cyFrac=cy; }
                        b.scale=Float.parseFloat(v[2]);
                        break;
                    }
                }
                // "D:" e o formato antigo do d-pad: ignorado de proposito (o arranjo mudou)
            }
            recompute();
        } catch(Exception e){ resetLayout(); }
    }
}
