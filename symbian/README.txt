========================================================================
 wolphun emulator - a Mophun emulator for Symbian S60 (2nd and 3rd Edition)
 Native C++ port of the Java SE emulator by Jamisson Tavares
========================================================================

INSTALL
-------
1. Pick the package for YOUR phone and copy it over:
     bin/wolphun_fp1.sisx   Symbian 9.2 and newer. This is the one most
                            people want.
                            E-series: E51, E63, E66, E71, E90 Communicator,
                            and the 9.3 models (E52, E55, E72, E73, E75,
                            E5-00).
                            N-series: N81, N82, N95, N78, N79, N85, N86,
                            N96, and the touch models (5800, N97).
     bin/wolphun_3rd.sisx   Symbian 9.1.
                            E-series: E50, E60, E61, E61i, E62, E65, E70.
                            N-series: N73, N75, N77, N80, N91, N92, N93.
                            The Avkon DLL ordinals changed in 9.2, so these
                            phones need their own package.
   Open the file on the phone to install it.
   (The package is self-signed, so the phone may ask you to allow software
    from an untrusted source - Menu > Tools > App. manager > Options >
    Settings > Software installation: "All".)

2. Copy your .mpn files wherever you like. On first run the emulator already
   has two folders registered:
       C:\Data\Mophun\        (phone memory - created automatically)
       E:\Mophun\             (memory card)
   You are NOT limited to those - see "Managing folders" below.

   IMPORTANT: ROMs must be DECRYPTED. "Packed" (compressed) S60/PPC ROMs do
   not work either - use the "_decompressed" version. The emulator tells you
   clearly if a ROM cannot be used.

USING IT
--------
Open "wolphun emu" from the phone's menu.

EVERY option lives in the LEFT SOFTKEY menu ("Options"), using the phone's
own native lists:

  Options
    Open game ............... lists the .mpn files in ALL registered folders
    Browse file... .......... the phone's OWN file browser (pick phone
                              memory / memory card and navigate; shows only
                              .mpn files)
    Game folders
        Add folder... ....... register one more folder (as many as you like)
        Remove folder... .... unregister one
    Restart game
    Close game
    Settings
        Game resolution ..... 101x80 / 128x160 / 176x208 / 176x220 /
                              208x320 / 240x320 / 320x240 / automatic
        Screen fit .......... centred 1:1 / scale keeping aspect / fill
        Controls ............ automatic / QWERTY / numeric / joystick only /
                              custom
        Edit keys ........... map any phone key to any game action
        Sound ............... on / off
        Texture filter ...... follow the game / bilinear / nearest  (3D only)
        Battery saving ...... CPU duty-cycle limit (see PERFORMANCE)
        Language ............ English / Spanish / German / Portuguese /
                              Ukrainian / Russian
        Fullscreen .......... on / off
        Fullscreen key ...... which key toggles it
        Vibration ........... on / off
    About
    Exit

MANAGING FOLDERS
----------------
"Open game" scans ALL registered folders at once and merges them into a
single list, so you can keep games spread around (some in phone memory, some
on the card, split by genre, and so on).

To register a folder: Options > Game folders > Add folder...
Navigate to the folder and pick ANY .mpn inside it; the emulator stores the
FOLDER that contains the file. (S60 3rd has no "select folder" dialog, so
this is the native way to do it.)

To open a one-off game without registering anything:
Options > Browse file...

The folder list is saved and survives reinstalling games.

RESOLUTION IS PER GAME: each ROM remembers its own. Mophun games were built
for different handsets (Sony Ericsson T300, T610, Nokia 7650...), so picking
the right resolution is what makes a game appear at the correct size.
The game reads the screen size at STARTUP (vGetCaps), so changing the
resolution restarts the session - the emulator warns you first.

CONTROLS
--------
  Joystick / arrows ... directions         (always active)
  Centre key .......... Fire
  QWERTY (E-series) ... W A S D = directions, Space/Enter = Fire,
                        Q = Select, E = Fire2
  Numeric keypad ...... 2 4 6 8 = directions, 5 = Fire, 0 = Select,
                        # = Fire2

Number keys are also delivered LITERALLY to the game: several Mophun titles
read digits directly (Bomb Jack, for instance, moves with 2 and 3).

The left softkey is NEVER captured by the game - it is always your way back
to the menu.

SAVES
-----
The game's native save (resume, high score) is written automatically to the
application's private directory, kept separate per game, and flushed on every
write - it does not depend on the game shutting down cleanly.

FULLSCREEN
----------
Options > Settings > Fullscreen gives the game the WHOLE display: the title
bar and the softkeys disappear. Nothing is stretched - geometry is still
decided by "Screen fit"; fullscreen only hands the game back the lines the
system was using (44 of them on an E63).

Press the same key again to leave. The default is LEFT SHIFT (the bottom-left
key on the QWERTY keyboard). You can change it in
Options > Settings > Fullscreen key: press whatever key you want and the
emulator confirms it was stored.

If that key does not work on your phone, there are two other ways out:
  - the LEFT SOFTKEY still opens the Options menu over the game, even with
    the softkeys hidden;
  - the RED KEY sends the emulator to the background, and returning to the
    application always restores the normal interface.

VIBRATION
---------
Some games ask for vibration (Honey Cave 2 vibrates when you take damage).
Turn it on or off in Options > Settings > Vibration; it ships ON, matching
the Android version.

If vibration is disabled in your phone's profile, it will not vibrate: that
choice is yours and outranks the game's. A phone with no vibration motor
simply does not vibrate, with no error.

PERFORMANCE
-----------
2D games run well. 3D games (Rally Pro Contest, Carmageddon 3D...) use a
software rasterizer: they work, but on a ~370 MHz ARM11 do not expect a high
frame rate. Lower resolutions help a lot.

"Battery saving" caps how long the virtual machine runs inside each tick.
This matters more than a frame cap: in a heavy scene the game is already
below any frame cap, so only limiting CPU time actually lowers the load. On a
phone with an aged battery, a sustained 3D load can otherwise brown out and
switch the phone off.

NOT THERE YET
-------------
  * Long music or effects with many instruments: the emulator plays ONE sound
    at a time (same as the PC version), so a new effect cuts off the previous
    one.
  * PCM sound module (vSound*): implemented, but no game in the archive uses
    it.
  * Encrypted ROMs: they must be decrypted on a PC first.
  * Save states (a VM snapshot): only the game's own native save works.
  * 3D games that keep their assets in an internal ZIP (The Da Vinci Code,
    for example).

BUILDING IT (on a PC)
---------------------
  group\build.cmd fp1      builds for Symbian 9.2+ (GCCE/ARM) - the default
  group\build.cmd 3rd      builds for Symbian 9.1
  sis\makesis.cmd fp1      signs and packages wolphun_fp1.sisx
  sis\makesis.cmd 3rd      signs and packages wolphun_3rd.sisx

  The target must be the SAME in both commands, otherwise the package ends up
  carrying a binary built against the other SDK.

  There is also a 2nd target (Symbian 8.1 / N70, N72, N90): the code and
  resources are ready in group2nd\, and the resources do compile, but the C++
  does NOT build on a modern PC - the ARMI compiler bundled with that SDK is
  a cygwin binary from 2003 that segfaults on Windows 11. That target has to
  be built inside a virtual machine running an older Windows.

  Symbian's EPOCROOT does not accept a drive letter, so the project has to
  live on the SAME drive as the SDK. If you keep the sources on another
  drive, make a junction to them on the SDK's drive and build from there.
  The paths for each target are set at the top of group\build.cmd.

  The VM core also builds on an ordinary PC, with no Symbian SDK at all:
      g++ -fwrapv -O2 -Wall -Iinc -o test/vmtest test/vmtest.cpp src/vm/*.cpp
========================================================================
