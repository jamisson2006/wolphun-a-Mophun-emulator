<p align="center">
  <img src="desktop/assets/wolphun.png" width="140" alt="wolphun logo">
</p>

<h1 align="center">wolphun — a Mophun emulator</h1>

<p align="center">
  An open-source emulator for <b>Mophun</b> games — the early-2000s mobile
  game platform (Synergenix / Sony Ericsson). Desktop &amp; Android.
</p>

---

**wolphun** runs the original Mophun game bytecode on a from-scratch **PIP2 virtual
machine** with a software SDK layer, so menus, physics, collisions and enemies behave
exactly like on the real hardware — instead of re-implementing each game.

Two front-ends, one core:

- 🖥️ **Desktop** (Java SE) — GUI with a ROM library, save states, video/sound settings, and a one-click packaged `.exe`.
- 📱 **Android** (7.0+) — touch UI with an on-screen keypad you can **move and resize per game**, and a multi-language interface.

## ✨ Features

- **Faithful PIP2 VM** — interprets the original game bytecode (116 opcodes); the game logic is the *real* one.
- **2D graphics** — sprites (multiple pixel formats), animated tilemaps, fills, lines, flat polygons, palette handling, transparency.
- **3D graphics** — a software rasterizer (matrix stack, z-buffer, perspective-correct textures, billboards, back-face culling) for the 3D racing / sports titles.
- **Sound** — MIDI and PCM playback.
- **Save states** — snapshot & restore any game (desktop **F5/F8**; Android in-game menu).
- **Device profiles** — emulates different phone models & resolutions so games pass their terminal checks.
- **Desktop GUI** — ROM library with search + screenshots, per-game native saves, live key remapping, brightness/scale settings.
- **Android** — customizable on-screen controls (drag & pinch-to-resize, saved per game, à la J2ME-Loader / PPSSPP), a **6-language UI** (EN/ES/DE/PT/UK/RU), and shared-folder ROM support.

## 🚀 Build & run

### Desktop (Java 8+)
```bat
cd desktop
build.bat        REM compiles to out\
run_emu.bat      REM opens the emulator (ROM library)
```
You can also package a **standalone `.exe`** with an embedded Java runtime:
```bat
empacotar-exe.bat    REM uses jpackage (JDK 14+)
```

### Android (Android Studio)
Open the **`android/`** folder in Android Studio and press **Run**. `minSdk 24` (Android 7.0).
Add your `.mpn` files to the app's ROMs folder or `/sdcard/mophun`, or import them with the **“+”** button.

> ℹ️ You provide your own game files — **this project does not include any game ROMs.**

## 📁 Repository layout
```
desktop/    Java SE version  (PIP2 VM + software SDK + Swing GUI)
android/    Android version  (same VM core + SurfaceView + touch UI)
```
The emulation core (`wolphun.vm.*`) is shared between both front-ends; only the
platform layer (display, audio, input) differs.

## 🙏 Credits & references

wolphun was written by studying open-source Mophun preservation projects. Huge thanks to:

- **[nofun](https://github.com/RadratSoftworks/nofun)** by *RadratSoftworks* — the C# Mophun emulator used as the **authoritative reference** for SDK / VMGP behaviour (graphics, input, sound, device caps, and the 3D pipeline). A large part of wolphun's SDK layer follows nofun's semantics.
- **[MoRePhun](https://github.com/Luca1991/MoRePhun)** — reference for the PIP2 opcodes.

wolphun is an **independent reimplementation in Java** — no code was copied from the
projects above; they were used to understand the platform's behaviour.

## 📜 License

Released under the **GNU GPL-3.0** — see [LICENSE](LICENSE).

---

<p align="center">Made by <b>Jamisson Tavares</b> · Maned Software 🐺</p>
