====================================================================
  wolphun emulator  0.3.0 (beta)  -  Maned Software
  A Mophun (PIP2) mobile game emulator, by Jamisson Tavares.
====================================================================

HOW TO USE
  Double-click  "wolphun emulator.exe".
  (or use the  "Start wolphun emulator.bat"  shortcut)
  You do not need Java installed: the runtime ships inside this folder.

FIRST RUN
  Menu  File > Set ROM folder...  and point it at the folder with your
  games (one subfolder per game, with the .mpn inside). Encrypted ROMs
  (.mpn with DRM) are detected and decrypted automatically on open.

WHERE YOUR DATA LIVES  (in your user account, OUTSIDE this folder):
  %USERPROFILE%\.mophunemu\
    +- config.properties     settings (video, sound, controls, language)
    +- saves\<game>\         the game's NATIVE save + save states (F5/F8)
       +- res_<n>.bin        progress for games that write inside their own
                             container (Rally/Golf/Football Pro Contest,
                             Carmageddon 3D...)
    +- decrypted\           cached decrypted copies of the .mpn files
    +- screenshots\         your screen captures (PNG)

  Because the data lives in %USERPROFILE%\.mophunemu, you can move or rename
  this emulator folder without losing saves or settings. And the saves made
  by the source (.bat) version and the packaged .exe are the SAME ones -
  they share the folder.

CONTROLS  (defaults; remappable in Settings > Controls)
  Move .............. arrows / WASD / numpad
  Fire / OK ......... Space, Enter, Ctrl
  Select (menu/back) ... Q, key 0
  Fire2 ............. E, Backspace
  Back/quit ......... Esc       Clear (key C) ........ Delete
  Save state: F5     Load state: F8
  Pause: Ctrl+P      Maximize the window: F11     Close: Ctrl+W
  Screenshot: F12 (the game only)   Shift+F12 (the whole window)
    The image comes out at WINDOW size, not at the game's 176x208.

SUPPORT THE PROJECT
  ko-fi.com/jamissontavares
  (the same link, with a QR code, is in Help > About)

(c) 2026 Maned Software  -  youtube.com/@jamissontavares7671
