package mophun;

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
        p("m.openrom","Open ROM (.mpn)...","Abrir ROM (.mpn)...","ROM öffnen (.mpn)...","Abrir ROM (.mpn)...","Відкрити ROM (.mpn)...","Открыть ROM (.mpn)...");
        p("m.video","Video...","Vídeo...","Video...","Vídeo...","Відео...","Видео...");
        p("m.sound","Sound","Sonido","Ton","Som","Звук","Звук");
        p("menu.sound","Sound","Sonido","Ton","Som","Звук","Звук");
        p("m.sound.on","Enabled","Activado","Aktiviert","Ativado","Увімкнено","Включено");
        p("m.reverb","Echo (reverb)","Eco (reverberación)","Hall (Echo)","Eco (reverb)","Відлуння (реверб)","Эхо (реверберация)");
        // ---- aparencia / tema ----
        p("m.appearance","Appearance...","Apariencia...","Erscheinungsbild...","Aparência...","Вигляд...","Оформление...");
        p("dlg.appearance","Appearance","Apariencia","Erscheinungsbild","Aparência","Вигляд","Оформление");
        p("ap.theme","Theme:","Tema:","Thema:","Tema:","Тема:","Тема:");
        p("ap.font","Font:","Fuente:","Schriftart:","Fonte:","Шрифт:","Шрифт:");
        p("ap.size","Size:","Tamaño:","Größe:","Tamanho:","Розмір:","Размер:");
        p("ap.bg","Background","Fondo","Hintergrund","Fundo","Тло","Фон");
        p("ap.text","Text","Texto","Text","Texto","Текст","Текст");
        p("ap.accent","Accent","Acento","Akzent","Destaque","Акцент","Акцент");
        p("ap.exact","Use the exact color (no contrast fix)","Usar el color exacto (sin ajuste de contraste)","Exakte Farbe verwenden (ohne Kontrastkorrektur)","Usar a cor exata (sem ajuste de contraste)","\u0412\u0438\u043a\u043e\u0440\u0438\u0441\u0442\u043e\u0432\u0443\u0432\u0430\u0442\u0438 \u0442\u043e\u0447\u043d\u0438\u0439 \u043a\u043e\u043b\u0456\u0440","\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c \u0442\u043e\u0447\u043d\u044b\u0439 \u0446\u0432\u0435\u0442");
        p("ap.panel","Panels","Paneles","Panels","Painéis","Панелі","Панели");
        p("ap.restart","The \"System\" theme fully applies after restarting.","El tema \"Sistema\" se aplica por completo al reiniciar.","Das Thema \"System\" wird nach einem Neustart vollständig übernommen.","O tema \"Sistema\" é aplicado por completo ao reiniciar.","Тема «Система» повністю застосується після перезапуску.","Тема «Система» полностью применится после перезапуска.");
        p("theme.system","System (native)","Sistema (nativo)","System (nativ)","Sistema (nativo)","Системна","Системная");
        p("theme.wolphun","Wolphun","Wolphun","Wolphun","Wolphun","Wolphun","Wolphun");
        p("theme.dark","Dark","Oscuro","Dunkel","Escuro","Темна","Тёмная");
        p("theme.light","Light","Claro","Hell","Claro","Світла","Светлая");
        p("theme.amber","Retro amber","Ámbar retro","Retro-Bernstein","Âmbar retrô","Ретро бурштин","Ретро янтарь");
        p("theme.crt","Phosphor (CRT)","Fósforo (CRT)","Phosphor (CRT)","Fósforo (CRT)","Люмінофор (CRT)","Люминофор (ЭЛТ)");
        p("theme.ocean","Ocean","Océano","Ozean","Oceano","Океан","Океан");
        p("theme.purple","Neon purple","Púrpura neón","Neon-Lila","Roxo neon","Неоново-фіолетова","Неоновый фиолетовый");
        p("theme.custom","Custom","Personalizado","Benutzerdefiniert","Personalizado","Власна","Свой");
        p("m.soundfont","Soundfont (.sf2)...","Banco de sonidos (.sf2)...","Soundfont (.sf2)...","Banco de sons (.sf2)...","Банк звуків (.sf2)...","Банк звуков (.sf2)...");
        p("sf.current","Current soundfont:","Banco de sonidos actual:","Aktueller Soundfont:","Banco de sons atual:","Поточний банк звуків:","Текущий банк звуков:");
        p("sf.default","default (built-in)","predeterminado (integrado)","Standard (integriert)","padrão (embutido)","типовий (вбудований)","по умолчанию (встроенный)");
        p("sf.choose","Choose .sf2...","Elegir .sf2...",".sf2 wählen...","Escolher .sf2...","Обрати .sf2...","Выбрать .sf2...");
        p("sf.usedefault","Use default","Usar predeterminado","Standard verwenden","Usar padrão","Використати типовий","Использовать по умолчанию");
        p("sf.applied","Soundfont set. It applies to the next game you open.","Banco de sonidos definido. Se aplica al próximo juego que abras.","Soundfont gesetzt. Gilt für das nächste geöffnete Spiel.","Banco de sons definido. Vale para o próximo jogo aberto.","Банк звуків встановлено. Діє для наступної відкритої гри.","Банк звуков установлен. Применяется к следующей открытой игре.");
        p("ctrl.esc","(Esc ends)","(Esc termina)","(Esc beendet)","(Esc termina)","(Esc завершує)","(Esc завершает)");
        // ---- dialogo Sobre ----
        p("about.tagline","Emulator for Mophun (PIP2) mobile games","Emulador de juegos móviles Mophun (PIP2)","Emulator für Mophun-Handyspiele (PIP2)","Emulador de jogos mobile Mophun (PIP2)","Емулятор мобільних ігор Mophun (PIP2)","Эмулятор мобильных игр Mophun (PIP2)");
        p("about.firstbeta","public beta","beta pública","öffentliche Beta","beta pública","публічна бета","публичная бета");
        p("about.developer","Developer","Desarrollador","Entwickler","Desenvolvedor","Розробник","Разработчик");
        p("about.studio","Studio","Estudio","Studio","Estúdio","Студія","Студия");
        p("about.support","Support the project","Apoya el proyecto","Unterstütze das Projekt","Apoie o projeto","Підтримати проєкт","Поддержать проект");
        p("about.scan","Scan the code or click the link","Escanea el código o haz clic en el enlace","Code scannen oder auf den Link klicken","Escaneie o código ou clique no link","Скануйте код або натисніть на посилання","Отсканируйте код или нажмите на ссылку");
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
        p("lbl.fontsize","In-game font size:","Tamaño de fuente (juego):","Schriftgröße (Spiel):","Tamanho da fonte (jogo):","Розмір шрифту (гра):","Размер шрифта (игра):");
        p("lbl.hd","3D render scale (HD):","Escala de render 3D (HD):","3D-Renderskalierung (HD):","Escala de render 3D (HD):","Масштаб рендера 3D (HD):","Масштаб рендера 3D (HD):");
        p("lbl.texfilter","3D texture filter:","Filtro de textura 3D:","3D-Texturfilter:","Filtro de textura 3D:","Фільтр текстур 3D:","Фильтр текстур 3D:");
        p("tf.game","Follow the game","Seguir el juego","Dem Spiel folgen","Seguir o jogo","Слідувати грі","Следовать игре");
        p("tf.linear","Bilinear (smooth)","Bilineal (suave)","Bilinear (weich)","Bilinear (suave)","Білінійний (згладжений)","Билинейный (сглаженный)");
        p("tf.nearest","Nearest (sharp)","Cercano (nitido)","Nearest (scharf)","Nearest (nitido)","Найближчий (різкий)","Ближайший (резкий)");
        p("lbl.language","Language:","Idioma:","Sprache:","Idioma:","Мова:","Язык:");
        p("dlg.video","Settings","Configuración","Einstellungen","Configurações","Налаштування","Настройки");
        // ---- janela do jogo ----
        p("gw.pause","Pause","Pausar","Pause","Pausar","Пауза","Пауза");
        p("gw.resume","Resume","Reanudar","Fortsetzen","Retomar","Продовжити","Продолжить");
        p("gw.maximize","Maximize window","Maximizar ventana","Fenster maximieren","Maximizar janela",
            "\u0420\u043e\u0437\u0433\u043e\u0440\u043d\u0443\u0442\u0438 \u0432\u0456\u043a\u043d\u043e",
            "\u0420\u0430\u0437\u0432\u0435\u0440\u043d\u0443\u0442\u044c \u043e\u043a\u043d\u043e");
        // ---- captura de tela ----
        p("gw.screenshot","Screenshot","Captura de pantalla","Bildschirmfoto","Captura de tela","Знімок екрана","Снимок экрана");
        p("shot.game","Game only (no black bars)","Sólo el juego (sin barras negras)","Nur das Spiel (ohne schwarze Balken)","Só o jogo (sem barras preta)","Лише гра (без чорних смуг)","Только игра (без чёрных полос)");
        p("shot.full","Whole window (with black bars)","Ventana completa (con barras negras)","Ganzes Fenster (mit schwarzen Balken)","Janela inteira (com as barras preta)","Усе вікно (з чорними смугами)","Всё окно (с чёрными полосами)");
        p("shot.open","Open screenshots folder","Abrir la carpeta de capturas","Ordner mit Bildschirmfotos öffnen","Abrir a pasta de capturas","Відкрити теку знімків","Открыть папку снимков");
        p("shot.saved","screenshot {0}×{1} saved","captura {0}×{1} guardada","Bildschirmfoto {0}×{1} gespeichert","captura {0}×{1} salva","знімок {0}×{1} збережено","снимок {0}×{1} сохранён");
        p("shot.fail","Could not save the screenshot:","No se pudo guardar la captura:","Bildschirmfoto konnte nicht gespeichert werden:","Não foi possível salvar a captura:","Не вдалося зберегти знімок:","Не удалось сохранить снимок:");
        p("shot.notready","Nothing drawn yet — wait for the game to show up.","Aún no hay nada dibujado — espera a que el juego aparezca.","Noch nichts gezeichnet — warte, bis das Spiel erscheint.","Nada desenhado ainda — espere o jogo aparecer.","Ще нічого не намальовано — дочекайтеся гри.","Ещё ничего не нарисовано — дождитесь игры.");
        p("gw.restart","Restart","Reiniciar","Neustart","Reiniciar","Перезапуск","Перезапуск");
        p("gw.savestate","Save state","Guardar estado","Zustand speichern","Salvar estado","Зберегти стан","Сохранить состояние");
        p("gw.loadstate","Load state","Cargar estado","Zustand laden","Carregar estado","Завантажити стан","Загрузить состояние");
        p("gw.close","Exit to library","Salir a la biblioteca","Zur Bibliothek beenden","Sair para a biblioteca","Вийти до бібліотеки","Выйти в библиотеку");
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
        p("err.notvmgp","This file is not a valid Mophun (VMGP) game, or it is still encrypted (DRM). Try “Decrypt and play”, or pick another version of the game.","Este archivo no es un juego Mophun (VMGP) válido, o sigue cifrado (DRM). Prueba “Descifrar y jugar” o elige otra versión del juego.","Diese Datei ist kein gültiges Mophun-Spiel (VMGP) oder noch verschlüsselt (DRM). Versuche „Entschlüsseln und spielen“ oder wähle eine andere Version.","Este arquivo não é um jogo Mophun (VMGP) válido, ou ainda está criptografado (DRM). Tente “Descriptografar e jogar”, ou escolha outra versão do jogo.","Цей файл не є коректною грою Mophun (VMGP) або досі зашифрований (DRM). Спробуйте “Розшифрувати та грати” або оберіть іншу версію гри.","Этот файл не является корректной игрой Mophun (VMGP) или всё ещё зашифрован (DRM). Попробуйте “Расшифровать и играть” или выберите другую версию игры.");
        p("err.restart","Failed to restart:","No se pudo reiniciar:","Neustart fehlgeschlagen:","Falha ao reiniciar:","Не вдалося перезапустити:","Не удалось перезапустить:");
        p("err.savestate","Failed to save state:","No se pudo guardar el estado:","Zustand speichern fehlgeschlagen:","Falha ao salvar estado:","Не вдалося зберегти стан:","Не удалось сохранить состояние:");
        p("err.loadstate","Failed to load state:","No se pudo cargar el estado:","Zustand laden fehlgeschlagen:","Falha ao carregar estado:","Не вдалося завантажити стан:","Не удалось загрузить состояние:");
        // ---- UI extra internacionalizada (antes hardcoded em PT) ----
        p("lib.emptytitle","Empty library","Biblioteca vacía","Leere Bibliothek","Biblioteca vazia","Порожня бібліотека","Пустая библиотека");
        p("lib.emptyask","No ROMs folder set. Select one now?","No hay carpeta de ROMs definida. ¿Seleccionarla ahora?","Kein ROM-Ordner festgelegt. Jetzt einen auswählen?","Nenhuma pasta de ROMs definida. Deseja selecionar agora?","Теку ROM не вказано. Обрати зараз?","Папка ROM не задана. Выбрать сейчас?");
        p("img.error","(image error)","(error de imagen)","(Bildfehler)","(erro na imagem)","(помилка зображення)","(ошибка изображения)");
        p("dec.playbtn","Decrypt and play","Descifrar y jugar","Entschlüsseln und spielen","Descriptografar e jogar","Розшифрувати та грати","Расшифровать и играть");
        p("dec.encmsg","'{0}' is ENCRYPTED (mophun DRM).\nDecrypt it now to play normally?\n\nThe decrypted copy is cached and REUSED next time\n(this dialog won't appear again). The original .mpn is NOT changed.\nFor a permanent copy, use File > Save decrypted ROM.","'{0}' está CIFRADO (DRM mophun).\n¿Descifrarlo ahora para jugar normalmente?\n\nLa copia descifrada se guarda en caché y se REUTILIZA las próximas veces\n(este diálogo no vuelve a aparecer). El .mpn original NO se modifica.\nPara una copia permanente, usa Archivo > Guardar ROM descifrada.","„{0}“ ist VERSCHLÜSSELT (mophun-DRM).\nJetzt entschlüsseln, um normal zu spielen?\n\nDie entschlüsselte Kopie wird zwischengespeichert und beim nächsten Mal WIEDERVERWENDET\n(dieser Dialog erscheint nicht erneut). Die originale .mpn wird NICHT verändert.\nFür eine dauerhafte Kopie: Datei > Entschlüsseltes ROM speichern.","'{0}' esta CRIPTOGRAFADO (DRM mophun).\nDeseja descriptografar agora para jogar normalmente?\n\nA copia descriptografada fica em cache e sera REUTILIZADA nas proximas vezes\n(esta tela nao aparece de novo). O .mpn original NAO e alterado.\nPara uma copia permanente, use Arquivo > Salvar ROM descriptografado.","'{0}' ЗАШИФРОВАНО (DRM mophun).\nРозшифрувати зараз, щоб грати нормально?\n\nРозшифрована копія кешується й ПОВТОРНО використовується наступного разу\n(це вікно більше не з'явиться). Оригінальний .mpn НЕ змінюється.\nДля постійної копії: Файл > Зберегти розшифрований ROM.","'{0}' ЗАШИФРОВАН (DRM mophun).\nРасшифровать сейчас, чтобы играть нормально?\n\nРасшифрованная копия кешируется и ПОВТОРНО используется в следующий раз\n(это окно больше не появится). Оригинальный .mpn НЕ изменяется.\nДля постоянной копии: Файл > Сохранить расшифрованный ROM.");
        p("dec.enctitle","Encrypted ROM","ROM cifrada","Verschlüsseltes ROM","ROM criptografado","Зашифрований ROM","Зашифрованный ROM");
        p("dec.fail","Failed to decrypt:","No se pudo descifrar:","Entschlüsselung fehlgeschlagen:","Falha ao descriptografar:","Не вдалося розшифрувати:","Не удалось расшифровать:");
        p("dec.nokeymsg","Could not decrypt '{0}'.\nNo embedded RSA key matches (maybe it isn't a Sony Ericsson game).","No se pudo descifrar '{0}'.\nNinguna clave RSA integrada coincide (quizá no sea un juego Sony Ericsson).","„{0}“ konnte nicht entschlüsselt werden.\nKein eingebetteter RSA-Schlüssel passt (evtl. kein Sony-Ericsson-Spiel).","Nao foi possivel descriptografar '{0}'.\nNenhuma chave RSA embutida corresponde (talvez nao seja um jogo Sony Ericsson).","Не вдалося розшифрувати '{0}'.\nЖоден вбудований ключ RSA не підходить (можливо, це не гра Sony Ericsson).","Не удалось расшифровать '{0}'.\nНи один встроенный RSA-ключ не подходит (возможно, это не игра Sony Ericsson).");
        p("dec.nokeytitle","No matching key","Sin clave compatible","Kein passender Schlüssel","Sem chave compativel","Немає сумісного ключа","Нет подходящего ключа");
        p("m.selromtitle","Select the folder with mophun ROMs","Selecciona la carpeta con las ROMs mophun","Ordner mit den mophun-ROMs auswählen","Selecione a pasta com as ROMs mophun","Виберіть теку з ROM mophun","Выберите папку с ROM mophun");
        p("dec.selfirst","Select a game first.","Selecciona un juego primero.","Zuerst ein Spiel auswählen.","Selecione um jogo primeiro.","Спершу виберіть гру.","Сначала выберите игру.");
        p("dec.already","'{0}' is already decrypted.","'{0}' ya está descifrado.","„{0}“ ist bereits entschlüsselt.","'{0}' ja esta descriptografado.","'{0}' вже розшифровано.","'{0}' уже расшифрован.");
        p("dec.nothing","Nothing to do","Nada que hacer","Nichts zu tun","Nada a fazer","Нічого робити","Нечего делать");
        p("dec.optcopy","Save copy...","Guardar copia...","Kopie speichern...","Salvar copia...","Зберегти копію...","Сохранить копию...");
        p("dec.optreplace","Replace original","Reemplazar original","Original ersetzen","Substituir original","Замінити оригінал","Заменить оригинал");
        p("dec.permmsg","Decrypt '{0}' permanently (the decryption dialog won't appear again):\n\n• Save copy: writes a decrypted .mpn wherever you choose.\n• Replace original: overwrites the original .mpn file (irreversible).","Descifrar '{0}' permanentemente (el diálogo de descifrado no volverá a aparecer):\n\n• Guardar copia: escribe un .mpn descifrado donde elijas.\n• Reemplazar original: sobrescribe el archivo .mpn original (irreversible).","„{0}“ dauerhaft entschlüsseln (der Entschlüsselungsdialog erscheint nicht mehr):\n\n• Kopie speichern: schreibt eine entschlüsselte .mpn an einen Ort deiner Wahl.\n• Original ersetzen: überschreibt die originale .mpn-Datei (unwiderruflich).","Descriptografar '{0}' permanentemente (nao passa mais pela tela de descriptografia):\n\n• Salvar copia: grava um .mpn descriptografado onde voce escolher.\n• Substituir original: sobrescreve o arquivo .mpn original (irreversivel).","Розшифрувати '{0}' назавжди (вікно розшифрування більше не з'являтиметься):\n\n• Зберегти копію: запише розшифрований .mpn у вибране місце.\n• Замінити оригінал: перезапише оригінальний файл .mpn (незворотно).","Расшифровать '{0}' навсегда (окно расшифровки больше не появится):\n\n• Сохранить копию: запишет расшифрованный .mpn в выбранное место.\n• Заменить оригинал: перезапишет оригинальный файл .mpn (необратимо).");
        p("dec.savetitle","Save decrypted ROM","Guardar ROM descifrada","Entschlüsseltes ROM speichern","Salvar ROM descriptografado","Зберегти розшифрований ROM","Сохранить расшифрованный ROM");
        p("dec.saveas","Save decrypted .mpn as","Guardar .mpn descifrado como","Entschlüsselte .mpn speichern als","Salvar .mpn descriptografado como","Зберегти розшифрований .mpn як","Сохранить расшифрованный .mpn как");
        p("dec.saved","Saved:","Guardado:","Gespeichert:","Salvo:","Збережено:","Сохранено:");
        p("dec.nokeyrsa","No compatible RSA key.","Sin clave RSA compatible.","Kein kompatibler RSA-Schlüssel.","Sem chave RSA compativel.","Немає сумісного ключа RSA.","Нет совместимого RSA-ключа.");
        p("dlg.fail","Failed","Error","Fehlgeschlagen","Falha","Помилка","Ошибка");
        p("dec.overwritemsg","This will OVERWRITE the original file:\n{0}\n\nConfirm?","Esto SOBRESCRIBIRÁ el archivo original:\n{0}\n\n¿Confirmar?","Dies ÜBERSCHREIBT die Originaldatei:\n{0}\n\nBestätigen?","Isto vai SOBRESCREVER o arquivo original:\n{0}\n\nConfirmar?","Це ПЕРЕЗАПИШЕ оригінальний файл:\n{0}\n\nПідтвердити?","Это ПЕРЕЗАПИШЕТ оригинальный файл:\n{0}\n\nПодтвердить?");
        p("dec.replaced","Original replaced by the decrypted version.","Original reemplazado por la versión descifrada.","Original durch die entschlüsselte Version ersetzt.","Original substituido pela versao descriptografada.","Оригінал замінено розшифрованою версією.","Оригинал заменён расшифрованной версией.");
        p("keys.none","(none)","(ninguna)","(keine)","(nenhuma)","(немає)","(нет)");
        p("ctrl.hint","<html>Click a button (turns <b>yellow</b>) and press a key to <b>add</b> it (press again to remove). Works with <b>Space, Enter, numpad, arrows</b> — any key. <b>Esc</b> ends capture.<br>In menus: <b>Fire / OK</b> (Space/Enter) <b>SELECTS</b> the item. The <b>Select</b> softkey = menu / back / <b>exit</b> (on the main menu it quits the game -> back to library). <b>Fire2</b> = 2nd action.</html>","<html>Haz clic en un botón (se pone <b>amarillo</b>) y pulsa una tecla para <b>añadir</b> (pulsa de nuevo para quitar). Funciona con <b>Espacio, Enter, teclado numérico, flechas</b> — cualquier tecla. <b>Esc</b> termina la captura.<br>En los menús: <b>Fire / OK</b> (Espacio/Enter) <b>ELIGE</b> el elemento. La softkey <b>Select</b> = menú / atrás / <b>salir</b> (en el menú principal sale del juego -> vuelve a la biblioteca). <b>Fire2</b> = 2ª acción.</html>","<html>Klicke auf eine Schaltfläche (wird <b>gelb</b>) und drücke eine Taste zum <b>Hinzufügen</b> (erneut drücken zum Entfernen). Funktioniert mit <b>Leertaste, Enter, Ziffernblock, Pfeiltasten</b> — jede Taste. <b>Esc</b> beendet die Erfassung.<br>In Menüs: <b>Fire / OK</b> (Leertaste/Enter) <b>WÄHLT</b> den Eintrag. Der <b>Select</b>-Softkey = Menü / zurück / <b>beenden</b> (im Hauptmenü beendet es das Spiel -> zurück zur Bibliothek). <b>Fire2</b> = 2. Aktion.</html>","<html>Clique num botao (fica <b>amarelo</b>) e tecle p/ <b>adicionar</b> (tecle de novo p/ remover). Funciona com <b>Espaco, Enter, numpad, setas</b> — qualquer tecla. <b>Esc</b> termina a captura.<br>Nos menus: <b>Fire / OK</b> (Espaco/Enter) <b>ESCOLHE</b> o item. A softkey <b>Select</b> = menu / voltar / <b>sair</b> (no menu principal sai do jogo -> volta a biblioteca). <b>Fire2</b> = 2a acao.</html>","<html>Клацніть кнопку (стане <b>жовтою</b>) і натисніть клавішу, щоб <b>додати</b> (натисніть ще раз, щоб прибрати). Працює з <b>Пробілом, Enter, цифровим блоком, стрілками</b> — будь-яка клавіша. <b>Esc</b> завершує захоплення.<br>У меню: <b>Fire / OK</b> (Пробіл/Enter) <b>ОБИРАЄ</b> пункт. Софткей <b>Select</b> = меню / назад / <b>вихід</b> (у головному меню виходить із гри -> до бібліотеки). <b>Fire2</b> = 2-га дія.</html>","<html>Нажмите кнопку (станет <b>жёлтой</b>) и нажмите клавишу, чтобы <b>добавить</b> (нажмите снова, чтобы убрать). Работает с <b>Пробелом, Enter, цифровым блоком, стрелками</b> — любая клавиша. <b>Esc</b> завершает захват.<br>В меню: <b>Fire / OK</b> (Пробел/Enter) <b>ВЫБИРАЕТ</b> пункт. Софткей <b>Select</b> = меню / назад / <b>выход</b> (в главном меню выходит из игры -> в библиотеку). <b>Fire2</b> = 2-е действие.</html>");
        p("st.error","error","error","Fehler","erro","помилка","ошибка");
        p("st.statesaved","state saved","estado guardado","Zustand gespeichert","estado salvo","стан збережено","состояние сохранено");
        p("st.stateloaded","state loaded","estado cargado","Zustand geladen","estado carregado","стан завантажено","состояние загружено");
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
    public static void setByCode(String code){ if(code!=null) for(int i=0;i<CODES.length;i++) if(CODES[i].equals(code)){ cur=i; break; } applyLocale(); }
    public static String code(){ return CODES[cur]; }
    public static java.util.Locale locale(){ return new java.util.Locale(CODES[cur]); }
    /** Alinha o locale da JVM ao idioma escolhido, para que os botoes padrao do Swing
     *  (OK/Cancel) e os nomes de tecla (KeyEvent.getKeyText) sigam o idioma da UI,
     *  e nao o locale do sistema operacional (ex.: pt-BR). */
    public static void applyLocale(){
        java.util.Locale loc = locale();
        java.util.Locale.setDefault(loc);
        javax.swing.JComponent.setDefaultLocale(loc);
    }
}
