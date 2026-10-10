====================================================================
  wolphun emulator  0.3.0 (beta)  -  Maned Software
  A Mophun (PIP2) mobile game emulator, by Jamisson Tavares.
  -- Debian/Ubuntu package --
====================================================================

INSTALL
  sudo apt install ./wolphun-emulator_0.3.0~beta-1_all.deb
  (apt pulls in Java by itself if it is missing)

RUN
  wolphun                        from a terminal
  or from the applications menu  ("wolphun emulator", Games category)
  or pass the ROM directly:      wolphun /path/to/game.mpn

UNINSTALL
  sudo apt remove wolphun-emulator

REQUIREMENT
  Java 8 or newer WITH a graphical toolkit (Swing):
      sudo apt install default-jre
  A "-headless" JRE will not work.

FIRST RUN
  Menu  File > Set ROM folder...  and point it at the folder with your
  games (one subfolder per game, with the .mpn inside). Encrypted ROMs
  (.mpn with DRM) are detected and decrypted automatically.

WHERE YOUR DATA LIVES
  ~/.mophunemu/
    +- config.properties     settings (video, sound, controls, language)
    +- saves/<game>/         the game's NATIVE save + save states (F5/F8)
    +- decrypted/            cached decrypted copies of the .mpn files

CONTROLS  (defaults; remappable in Settings > Controls)
  Move .............. arrows / WASD / numpad
  Fire / OK ......... Space, Enter, Ctrl
  Select (menu) ..... Q, key 0
  Fire2 ............. E, Backspace
  Back/quit ......... Esc        Clear (key C) ........ Delete
  Save state: F5     Load state: F8
  Pause: Ctrl+P      Maximize: F11      Close: Ctrl+W

INSTALLED FILES
  /usr/bin/wolphun                        launcher
  /usr/lib/wolphun/wolphun.jar            the emulator
  /usr/share/applications/wolphun.desktop menu entry
  /usr/share/pixmaps/wolphun.png          icon

SUPPORT THE PROJECT
  ko-fi.com/jamissontavares
  (the same link, with a QR code, is in Help > About)

(c) 2026 Maned Software  -  youtube.com/@jamissontavares7671
