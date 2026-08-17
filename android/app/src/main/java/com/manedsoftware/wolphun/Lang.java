package com.manedsoftware.wolphun;

import java.util.*;

/**
 * Localizacao (i18n) da interface do emulador. 6 idiomas: English, Espanol, Deutsch,
 * Portugues, Ukrainian, Russian. Lang.t("chave") devolve o texto no idioma atual;
 * chaves ausentes caem no Portugues (fallback). Persistido no Config (codigo do idioma).
 */
public final class Lang {
    // ordem das colunas em cada traducao:                EN  ES  DE  PT  UK  RU
    public static final String[] NAMES = {"English","Español","Deutsch","Português","Українська","Русский"};
    public static final String[] CODES = {"en","es","de","pt","uk","ru"};
    static final int PT = 3;                       // indice do fallback
    public static int cur = PT;                    // idioma atual (default Portugues)

    static final Map<String,String[]> T = new HashMap<String,String[]>();
    static void p(String k,String en,String es,String de,String pt,String uk,String ru){ T.put(k, new String[]{en,es,de,pt,uk,ru}); }
    static {
        // ---- menus ----
        p("menu.file","File","Archivo","Datei","Arquivo","Файл","Файл");
        p("menu.settings","Settings","Configuración","Einstellungen","Configurações","Налаштування","Настройки");
        p("menu.help","Help","Ayuda","Hilfe","Ajuda","Довідка","Справка");
        p("menu.emulation","Emulation","Emulación","Emulation","Emulação","Емуляція","Эмуляция");
        p("menu.language","Language","Idioma","Sprache","Idioma","Мова","Язык");
        p("m.setrom","Set ROMs folder...","Definir carpeta de ROMs...","ROM-Ordner festlegen...","Definir pasta de ROMs...","Вказати теку ROM...","Указать папку ROM...");
        p("m.controls","Controls (key mapping)...","Controles (asignar teclas)...","Steuerung (Tastenbelegung)...","Controles (mapear teclas)...","Керування (клавіші)...","Управление (клавиши)...");
        p("m.savedec","Save decrypted ROM...","Guardar ROM descifrada...","Entschlüsseltes ROM speichern...","Salvar ROM descriptografado...","Зберегти розшифрований ROM...","Сохранить расшифрованный ROM...");
        p("m.exit","Exit","Salir","Beenden","Sair","Вийти","Выход");
        p("m.about","About","Acerca de","Über","Sobre","Про програму","О программе");
        // ---- dialogo Sobre ----
        p("about.tagline","Emulator for Mophun (PIP2) mobile games","Emulador de juegos móviles Mophun (PIP2)","Emulator für Mophun-Handyspiele (PIP2)","Emulador de jogos mobile Mophun (PIP2)","Емулятор мобільних ігор Mophun (PIP2)","Эмулятор мобильных игр Mophun (PIP2)");
        p("about.firstbeta","first public beta","primera beta pública","erste öffentliche Beta","primeira beta pública","перша публічна бета","первая публичная бета");
        p("about.developer","Developer","Desarrollador","Entwickler","Desenvolvedor","Розробник","Разработчик");
        p("about.studio","Studio","Estudio","Studio","Estúdio","Студія","Студия");
        // ---- biblioteca / botoes ----
        p("lbl.search","Search:","Buscar:","Suche:","Buscar:","Пошук:","Поиск:");
        p("lbl.version","Version:","Versión:","Version:","Versão:","Версія:","Версия:");
        p("btn.play","▶  Play","▶  Jugar","▶  Spielen","▶  Jogar","▶  Грати","▶  Играть");
        p("btn.save","Save","Guardar","Speichern","Salvar","Зберегти","Сохранить");
        p("btn.cancel","Cancel","Cancelar","Abbrechen","Cancelar","Скасувати","Отмена");
        p("btn.default","Default","Predeterminado","Standard","Padrão","Типово","По умолчанию");
        p("btn.clear","Clear","Limpiar","Löschen","Limpar","Очистити","Очистить");
        p("msg.selgame","(select a game)","(selecciona un juego)","(Spiel auswählen)","(selecione um jogo)","(виберіть гру)","(выберите игру)");
        p("msg.noshot","(no screenshot)","(sin captura)","(kein Screenshot)","(sem screenshot)","(немає скриншота)","(нет скриншота)");
        // ---- dialogo de configuracoes ----
        p("lbl.scale","Scale (zoom):","Escala (zoom):","Skalierung (Zoom):","Escala (zoom):","Масштаб (зум):","Масштаб (зум):");
        p("lbl.res","Resolution (WxH):","Resolución (AxA):","Auflösung (BxH):","Resolução (LxA):","Роздільність (ШxВ):","Разрешение (ШxВ):");
        p("lbl.device","Device model:","Modelo del dispositivo:","Gerätemodell:","Modelo do aparelho:","Модель пристрою:","Модель устройства:");
        p("lbl.fps","FPS limit:","Límite de FPS:","FPS-Limit:","Limite de FPS:","Ліміт FPS:","Лимит FPS:");
        p("lbl.gamma","Brightness (gamma):","Brillo (gamma):","Helligkeit (Gamma):","Brilho (gamma):","Яскравість (гама):","Яркость (гамма):");
        p("lbl.sound","Sound","Sonido","Ton","Som","Звук","Звук");
        p("lbl.screen","Game screen height:","Altura de la pantalla:","Bildschirmhoehe:","Altura da tela:","Висота екрана:","Высота экрана:");
        p("lbl.language","Language:","Idioma:","Sprache:","Idioma:","Мова:","Язык:");
        p("dlg.video","Settings","Configuración","Einstellungen","Configurações","Налаштування","Настройки");
        // ---- janela do jogo ----
        p("gw.pause","Pause","Pausar","Pause","Pausar","Пауза","Пауза");
        p("gw.resume","Resume","Reanudar","Fortsetzen","Retomar","Продовжити","Продолжить");
        p("gw.restart","Restart","Reiniciar","Neustart","Reiniciar","Перезапуск","Перезапуск");
        p("gw.savestate","Save state","Guardar estado","Zustand speichern","Salvar estado","Зберегти стан","Сохранить состояние");
        p("gw.loadstate","Load state","Cargar estado","Zustand laden","Carregar estado","Завантажити стан","Загрузить состояние");
        p("gw.close","Close (back to library)","Cerrar (volver a la biblioteca)","Schließen (zur Bibliothek)","Fechar (voltar à biblioteca)","Закрити (до бібліотеки)","Закрыть (в библиотеку)");
        p("gw.editkeys","Edit controls","Editar controles","Steuerung bearbeiten","Editar controles","Редагувати керування","Редактировать управление");
        p("gw.edithint","Drag buttons to move; pinch to resize. Press BACK to finish.","Arrastra para mover; pellizca para cambiar tamaño. ATRÁS para terminar.","Ziehen zum Verschieben; kneifen zum Skalieren. ZURÜCK zum Beenden.","Arraste p/ mover; pinça p/ redimensionar. VOLTAR p/ terminar.","Перетягуйте кнопки; щипок — розмір. НАЗАД — завершити.","Перетаскивайте кнопки; щипок — размер. НАЗАД — завершить.");
        p("gw.save","Save","Guardar","Speichern","Salvar","Зберегти","Сохранить");
        p("gw.reset","Reset","Restablecer","Zurücksetzen","Resetar","Скинути","Сбросить");
        p("st.paused","paused","pausado","pausiert","pausado","пауза","пауза");
        p("st.ended","ended","terminado","beendet","encerrado","завершено","завершено");
        p("gw.restartgame","Restart game","Reiniciar el juego","Spiel neu starten","Reiniciar o jogo","Перезапустити гру","Перезапустить игру");
        p("gw.backlib","Back to library","Volver a la biblioteca","Zurück zur Bibliothek","Voltar à biblioteca","До бібліотеки","В библиотеку");
        p("gw.gameended","The game exited (you pressed the in-game 'exit' softkey, or the game ended).","El juego se cerró (pulsaste la tecla 'salir' del menú, o el juego terminó).","Das Spiel wurde beendet (Softkey 'Beenden' im Menü, oder Spielende).","O jogo saiu (você apertou a softkey de 'sair' no menu, ou o jogo terminou).","Гра завершилася (ви натиснули 'вихід' у меню, або гра закінчилася).","Игра завершилась (вы нажали 'выход' в меню, или игра закончилась).");
        // ---- comuns ----
        p("dlg.error","Error","Error","Fehler","Erro","Помилка","Ошибка");
        p("dlg.nostate","No saved state for this game.","No hay estado guardado para este juego.","Kein gespeicherter Zustand für dieses Spiel.","Nenhum estado salvo para este jogo.","Немає збереженого стану для цієї гри.","Нет сохранённого состояния для этой игры.");
        p("ctrl.title","Controls - map keys (several per button)","Controles - asignar teclas (varias por botón)","Steuerung - Tasten zuweisen (mehrere pro Taste)","Controles - mapear teclas (várias por botão)","Керування - клавіші (кілька на кнопку)","Управление - клавиши (несколько на кнопку)");
    }

    public static String t(String key){
        String[] a = T.get(key);
        if(a==null) return key;
        return (cur>=0 && cur<a.length && a[cur]!=null && !a[cur].isEmpty()) ? a[cur] : a[PT];
    }
    public static void setByCode(String code){ if(code==null) return; for(int i=0;i<CODES.length;i++) if(CODES[i].equals(code)){ cur=i; return; } }
    public static String code(){ return CODES[cur]; }
}
