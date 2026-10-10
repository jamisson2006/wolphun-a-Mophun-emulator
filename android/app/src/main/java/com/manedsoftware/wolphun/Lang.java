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
    public static int cur = 0;                     // idioma default = ingles (indice 0 = "en")

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
        p("about.firstbeta","public beta","beta pública","öffentliche Beta","beta pública","публічна бета","публичная бета");
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
        p("lbl.addrom","Add ROM","Añadir ROM","ROM hinzufügen","Adicionar ROM","Додати ROM","Добавить ROM");
        p("lib.empty","No ROMs found.\n\nCopy your .mpn files to:\n","No se encontraron ROMs.\n\nCopia tus archivos .mpn a:\n","Keine ROMs gefunden.\n\nKopieren Sie Ihre .mpn-Dateien nach:\n","Nenhuma ROM encontrada.\n\nCopie seus arquivos .mpn para:\n","ROM не знайдено.\n\nСкопіюйте файли .mpn до:\n","ROM не найдены.\n\nСкопируйте файлы .mpn в:\n");
        p("lbl.fps","FPS limit:","Límite de FPS:","FPS-Limit:","Limite de FPS:","Ліміт FPS:","Лимит FPS:");
        p("lbl.gamma","Brightness (gamma):","Brillo (gamma):","Helligkeit (Gamma):","Brilho (gamma):","Яскравість (гама):","Яркость (гамма):");
        p("lbl.sound","Sound","Sonido","Ton","Som","Звук","Звук");
        p("lbl.vibrate","Vibration","Vibración","Vibration","Vibração","Вібрація","Вибрация");
        p("about.support","Support the project","Apoya el proyecto","Unterstütze das Projekt","Apoie o projeto","Підтримати проєкт","Поддержать проект");
        p("about.scan","Scan the code or tap the link","Escanea el código o toca el enlace","Code scannen oder auf den Link tippen","Escaneie o código ou toque no link","Скануйте код або торкніться посилання","Отсканируйте код или нажмите на ссылку");
        p("m.reverb","Echo (reverb)","Eco (reverberación)","Hall (Echo)","Eco (reverb)","Відлуння (реверб)","Эхо (реверберация)");
        p("m.soundfont","Soundfont (.sf2)","Banco de sonidos (.sf2)","Soundfont (.sf2)","Banco de sons (.sf2)","Банк звуків (.sf2)","Банк звуков (.sf2)");
        p("gm.resolution","Change resolution","Cambiar resolución","Auflösung ändern","Mudar resolução","Змінити роздільність","Изменить разрешение");
        p("gm.cleardata","Delete game data","Borrar datos del juego","Spieldaten löschen","Apagar dados do jogo","Видалити дані гри","Удалить данные игры");
        p("res.auto","Auto (detect)","Auto (detectar)","Auto (erkennen)","Automático (detectar)","Авто (визначити)","Авто (определить)");
        p("res.set","Resolution set. Applies next time you open the game.","Resolución aplicada al abrir el juego.","Auflösung gilt beim nächsten Start.","Resolução definida. Vale ao abrir o jogo.","Роздільність застосується при запуску гри.","Разрешение применится при запуске игры.");
        p("cleardata.msg","Delete saves and states of \"%s\"? The game stays installed.","¿Borrar partidas de \"%s\"? El juego sigue instalado.","Spielstände von \"%s\" löschen? Das Spiel bleibt installiert.","Apagar os saves e estados de \"%s\"? O jogo continua instalado.","Видалити збереження \"%s\"? Гра залишиться.","Удалить сохранения \"%s\"? Игра останется.");
        p("cleardata.done","Data of \"%s\" deleted","Datos de \"%s\" borrados","Daten von \"%s\" gelöscht","Dados de \"%s\" apagados","Дані \"%s\" видалено","Данные \"%s\" удалены");
        p("uninst.title","Uninstall game","Desinstalar juego","Spiel deinstallieren","Desinstalar jogo","Видалити гру","Удалить игру");
        p("uninst.msg","Remove \"%s\" from the library? The .mpn and its saves will be deleted.","¿Quitar \"%s\" de la biblioteca? Se borrará el .mpn y sus partidas.","\"%s\" aus der Bibliothek entfernen? Die .mpn und Spielstände werden gelöscht.","Remover \"%s\" da biblioteca? O .mpn e os saves serão apagados.","Прибрати \"%s\"? Файл .mpn і збереження буде видалено.","Убрать \"%s\"? Файл .mpn и сохранения будут удалены.");
        p("uninst.ok","Uninstall","Desinstalar","Deinstallieren","Desinstalar","Видалити","Удалить");
        p("uninst.done","\"%s\" uninstalled","\"%s\" desinstalado","\"%s\" deinstalliert","\"%s\" desinstalado","\"%s\" видалено","\"%s\" удалено");
        p("uninst.fail","Could not delete the file (shared folder may be read-only)","No se pudo borrar el archivo","Datei konnte nicht gelöscht werden","Não foi possível apagar o arquivo (pasta compartilhada pode ser somente-leitura)","Не вдалося видалити файл","Не удалось удалить файл");
        p("sf.default","Default (built-in)","Predeterminado","Standard","Padrão (embutido)","Типовий","По умолчанию");
        p("sf.current","Soundfont:","Banco de sonidos:","Soundfont:","Banco de sons:","Банк звуків:","Банк звуков:");
        p("sf.applied","Soundfont set. Applies to the next game you open.","Se aplica al próximo juego.","Gilt fürs nächste Spiel.","Vale para o próximo jogo aberto.","Діє для наступної гри.","Применится к следующей игре.");
        p("sf.folderhint","Put .sf2 files in /sdcard/mophun/soundbanks","Coloca .sf2 en /sdcard/mophun/soundbanks",".sf2 nach /sdcard/mophun/soundbanks","Coloque .sf2 em /sdcard/mophun/soundbanks","Покладіть .sf2 у /sdcard/mophun/soundbanks","Положите .sf2 в /sdcard/mophun/soundbanks");
        // ---- aparencia / tema ----
        p("m.appearance","Appearance","Apariencia","Erscheinungsbild","Aparência","Вигляд","Оформление");
        p("ap.theme","Theme","Tema","Thema","Tema","Тема","Тема");
        p("ap.font","Font","Fuente","Schriftart","Fonte","Шрифт","Шрифт");
        p("ap.size","Text size","Tamaño del texto","Textgröße","Tamanho do texto","Розмір тексту","Размер текста");
        p("ap.bg","Background","Fondo","Hintergrund","Fundo","Тло","Фон");
        p("ap.panel","Panels","Paneles","Panels","Painéis","Панелі","Панели");
        p("ap.text","Text","Texto","Text","Texto","Текст","Текст");
        p("ap.accent","Accent","Acento","Akzent","Destaque","Акцент","Акцент");
        p("theme.system","System (native)","Sistema (nativo)","System (nativ)","Sistema (nativo)","Системна","Системная");
        p("theme.wolphun","Wolphun","Wolphun","Wolphun","Wolphun","Wolphun","Wolphun");
        p("theme.dark","Dark","Oscuro","Dunkel","Escuro","Темна","Тёмная");
        p("theme.light","Light","Claro","Hell","Claro","Світла","Светлая");
        p("theme.amber","Retro amber","Ámbar retro","Retro-Bernstein","Âmbar retrô","Ретро бурштин","Ретро янтарь");
        p("theme.crt","Phosphor (CRT)","Fósforo (CRT)","Phosphor (CRT)","Fósforo (CRT)","Люмінофор (CRT)","Люминофор (ЭЛТ)");
        p("theme.ocean","Ocean","Océano","Ozean","Oceano","Океан","Океан");
        p("theme.purple","Neon purple","Púrpura neón","Neon-Lila","Roxo neon","Неоново-фіолетова","Неоновый фиолетовый");
        p("theme.custom","Custom","Personalizado","Benutzerdefiniert","Personalizado","Власна","Свой");
        p("font.sans","Sans","Sans","Sans","Sans","Без засічок","Без засечек");
        p("font.serif","Serif","Serif","Serif","Serif","Із засічками","С засечками");
        p("font.mono","Monospace","Monoespaciada","Monospace","Monoespaçada","Моноширинний","Моноширинный");
        p("lbl.screen","Game screen height:","Altura de la pantalla:","Bildschirmhoehe:","Altura da tela:","Висота екрана:","Высота экрана:");
        p("lbl.fontsize","In-game font size:","Tamaño de fuente (juego):","Schriftgröße (Spiel):","Tamanho da fonte (jogo):","Розмір шрифту (гра):","Размер шрифта (игра):");
        p("lbl.hd","3D render scale (HD):","Escala de render 3D (HD):","3D-Renderskalierung (HD):","Escala de render 3D (HD):","Масштаб рендера 3D (HD):","Масштаб рендера 3D (HD):");
        p("lbl.texfilter","3D texture filter:","Filtro de textura 3D:","3D-Texturfilter:","Filtro de textura 3D:","Фільтр текстур 3D:","Фильтр текстур 3D:");
        p("tf.game","Follow the game","Seguir el juego","Dem Spiel folgen","Seguir o jogo","Слідувати грі","Следовать игре");
        p("tf.linear","Bilinear (smooth)","Bilineal (suave)","Bilinear (weich)","Bilinear (suave)","Білінійний (згладжений)","Билинейный (сглаженный)");
        p("tf.nearest","Nearest (sharp)","Cercano (nitido)","Nearest (scharf)","Nearest (nitido)","Найближчий (різкий)","Ближайший (резкий)");
        p("hd.off","off","desactivado","aus","desligado","вимк.","выкл.");
        p("imp.nofileapp","No file app available","No hay app de archivos","Keine Datei-App verfügbar","Sem app de arquivos disponível","Немає застосунку файлів","Нет приложения для файлов");
        p("imp.okdec","Imported and decrypted: ","Importado y descifrado: ","Importiert und entschlüsselt: ","Importado e descriptografado: ","Імпортовано й розшифровано: ","Импортировано и расшифровано: ");
        p("imp.ok","Imported: ","Importado: ","Importiert: ","Importado: ","Імпортовано: ","Импортировано: ");
        p("imp.fail","Import failed: ","Error al importar: ","Import fehlgeschlagen: ","Falha ao importar: ","Помилка імпорту: ","Ошибка импорта: ");
        p("imp.bulkok","%d game(s) imported","%d juego(s) importado(s)","%d Spiel(e) importiert","%d jogo(s) importado(s)","Імпортовано ігор: %d","Импортировано игр: %d");
        p("imp.bulkpart","%d imported, %d failed","%d importados, %d fallidos","%d importiert, %d fehlgeschlagen","%d importados, %d com falha","%d імпортовано, %d з помилкою","%d импортировано, %d с ошибкой");
        p("lbl.language","Language:","Idioma:","Sprache:","Idioma:","Мова:","Язык:");
        p("dlg.video","Settings","Configuración","Einstellungen","Configurações","Налаштування","Настройки");
        // ---- janela do jogo ----
        p("gw.pause","Pause","Pausar","Pause","Pausar","Пауза","Пауза");
        p("gw.resume","Resume","Reanudar","Fortsetzen","Retomar","Продовжити","Продолжить");
        p("gw.restart","Restart","Reiniciar","Neustart","Reiniciar","Перезапуск","Перезапуск");
        p("gw.savestate","Save state","Guardar estado","Zustand speichern","Salvar estado","Зберегти стан","Сохранить состояние");
        p("gw.loadstate","Load state","Cargar estado","Zustand laden","Carregar estado","Завантажити стан","Загрузить состояние");
        p("gw.close","Exit (back to library)","Salir (a la biblioteca)","Beenden (zur Bibliothek)","Sair (para a biblioteca)","Вийти (до бібліотеки)","Выйти (в библиотеку)");
        p("gw.editkeys","Edit controls","Editar controles","Steuerung bearbeiten","Editar controles","Редагувати керування","Редактировать управление");
        p("gw.numkeys","Number keypad (0-9)","Teclado numérico (0-9)","Zifferntastatur (0-9)","Teclado numérico (0-9)","Цифрова клавіатура (0-9)","Цифровая клавиатура (0-9)");
        p("gw.layout","Change button layout","Cambiar diseño de botones","Tastenlayout ändern","Alterar layout dos botões","Змінити розкладку кнопок","Изменить раскладку кнопок");
        p("lay.auto","Automatic (what the game uses)","Automático (lo que usa el juego)","Automatisch (was das Spiel nutzt)","Automático (o que o jogo usa)","Автоматично","Автоматически");
        p("lay.gamepad","Gamepad","Gamepad","Gamepad","Gamepad","Геймпад","Геймпад");
        p("lay.phone","Phone","Teléfono","Telefon","Telefone","Телефон","Телефон");
        p("lay.numctrl","Numbers & controls","Números y controles","Ziffern & Steuerung","Números e controles","Цифри та керування","Цифры и управление");
        p("lay.ctrlnum","Controls & numbers","Controles y números","Steuerung & Ziffern","Controles e números","Керування та цифри","Управление и цифры");
        p("lay.numbers","Numbers only","Sólo números","Nur Ziffern","Só números","Лише цифри","Только цифры");
        p("lay.arrows","Arrows only","Sólo flechas","Nur Pfeile","Só setas","Лише стрілки","Только стрелки");
        p("lay.all","All buttons","Todos los botones","Alle Tasten","Todos os botões","Усі кнопки","Все кнопки");
        p("gw.phonekeys","Phone keys (C / Exit)","Teclas del teléfono (C / Salir)","Telefontasten (C / Ende)","Teclas do telefone (C / Sair)","Клавіші телефону (C / Вихід)","Клавиши телефона (C / Выход)");
        p("gw.nophonekeys","This game does not use phone keys","Este juego no usa teclas del teléfono","Dieses Spiel nutzt keine Telefontasten","Este jogo não usa teclas do telefone","Ця гра не використовує клавіші телефону","Эта игра не использует клавиши телефона");
        p("kp.exit","EXIT","SALIR","ENDE","SAIR","ВИХІД","ВЫХОД");
        p("kp.clear","CLEAR","BORRAR","LÖSCHEN","APAGAR","СТЕРТИ","СТЕРЕТЬ");
        p("gw.edithint","Drag to move, pinch to resize. The D-pad moves/resizes as a group; other buttons individually. BACK to finish.","Arrastra para mover, pellizca para redimensionar. La cruceta se mueve/redimensiona en grupo; los demás individualmente. ATRÁS para terminar.","Ziehen zum Verschieben, kneifen zum Skalieren. Das D-Pad als Gruppe; andere einzeln. ZURÜCK zum Beenden.","Arraste p/ mover, pinça p/ redimensionar. O direcional move/redimensiona em GRUPO; os outros botões individualmente. VOLTAR p/ terminar.","Перетягуйте, щипок — розмір. Хрестовина — групою; інші окремо. НАЗАД — завершити.","Перетаскивайте, щипок — размер. Крестовина — группой; остальные по отдельности. НАЗАД — завершить.");
        // ---- captura de tela ----
        p("gw.screenshot","Screenshot","Captura de pantalla","Bildschirmfoto","Captura de tela","Знімок екрана","Снимок экрана");
        p("shot.game","Game only (no controls)","Sólo el juego (sin controles)","Nur das Spiel (ohne Steuerung)","Só o jogo (sem controles)","Лише гра (без керування)","Только игра (без управления)");
        p("shot.full","Full screen (with controls)","Pantalla completa (con controles)","Ganzer Bildschirm (mit Steuerung)","Tela cheia (com controles)","Весь екран (з керуванням)","Весь экран (с управлением)");
        p("shot.saved","Screenshot saved ({0}×{1})\n{2}","Captura guardada ({0}×{1})\n{2}","Bildschirmfoto gespeichert ({0}×{1})\n{2}","Captura salva ({0}×{1})\n{2}","Знімок збережено ({0}×{1})\n{2}","Снимок сохранён ({0}×{1})\n{2}");
        p("shot.fail","Could not save the screenshot","No se pudo guardar la captura","Bildschirmfoto konnte nicht gespeichert werden","Não foi possível salvar a captura","Не вдалося зберегти знімок","Не удалось сохранить снимок");
        p("shot.notready","Nothing drawn yet — wait for the game to show up","Aún no hay nada dibujado — espera a que el juego aparezca","Noch nichts gezeichnet — warte, bis das Spiel erscheint","Nada desenhado ainda — espere o jogo aparecer","Ще нічого не намальовано — дочекайтеся гри","Ещё ничего не нарисовано — дождитесь игры");
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
        // ---- erros de inicializacao / estado (antes hardcoded em PT) ----
        p("err.starttitle","Startup error","Error al iniciar","Startfehler","Erro ao iniciar","Помилка запуску","Ошибка запуска");
        p("err.cantstart","Could not start “{0}”:","No se pudo iniciar “{0}”:","„{0}“ konnte nicht gestartet werden:","Não foi possível iniciar “{0}”:","Не вдалося запустити “{0}”:","Не удалось запустить “{0}”:");
        p("err.notvmgp","This file is not a valid Mophun (VMGP) game, or it is still encrypted (DRM). Try another version of the game.","Este archivo no es un juego Mophun (VMGP) válido, o sigue cifrado (DRM). Prueba otra versión del juego.","Diese Datei ist kein gültiges Mophun-Spiel (VMGP) oder noch verschlüsselt (DRM). Versuche eine andere Version.","Este arquivo não é um jogo Mophun (VMGP) válido, ou ainda está criptografado (DRM). Tente outra versão do jogo.","Цей файл не є коректною грою Mophun (VMGP) або досі зашифрований (DRM). Спробуйте іншу версію гри.","Этот файл не является корректной игрой Mophun (VMGP) или всё ещё зашифрован (DRM). Попробуйте другую версию игры.");
        p("st.saved","State saved","Estado guardado","Zustand gespeichert","Estado salvo","Стан збережено","Состояние сохранено");
        p("st.loaded","State loaded","Estado cargado","Zustand geladen","Estado carregado","Стан завантажено","Состояние загружено");
        p("err.savestate","Failed to save state:","No se pudo guardar el estado:","Zustand speichern fehlgeschlagen:","Falha ao salvar estado:","Не вдалося зберегти стан:","Не удалось сохранить состояние:");
        p("err.loadstate","Failed to load state:","No se pudo cargar el estado:","Zustand laden fehlgeschlagen:","Falha ao carregar estado:","Не вдалося завантажити стан:","Не удалось загрузить состояние:");
    }

    public static String t(String key){
        String[] a = T.get(key);
        if(a==null) return key;
        return (cur>=0 && cur<a.length && a[cur]!=null && !a[cur].isEmpty()) ? a[cur] : a[PT];
    }
    /** Como t(key), mas substitui os marcadores {0}, {1}, ... pelos args (na ordem). */
    public static String t(String key, Object... args){
        String s = t(key);
        if(args!=null) for(int i=0;i<args.length;i++) s = s.replace("{"+i+"}", String.valueOf(args[i]));
        return s;
    }
    public static void setByCode(String code){ if(code==null) return; for(int i=0;i<CODES.length;i++) if(CODES[i].equals(code)){ cur=i; return; } }
    public static String code(){ return CODES[cur]; }
}
