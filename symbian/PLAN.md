# wolphun-s60 — the Mophun emulator ported to Symbian S60

A native port of the **wolphun emulator** (originally Java SE, by Jamisson Tavares) to
**Symbian S60**, targeting the Nokia **N** and **E** lines — 240×320 portrait and
320×240 landscape, numeric keypads and QWERTY.

This document is the architecture and status note for the port: what the design is, what
is verified, what is deliberately not done, and the traps that cost real time. For how to
*use* or *build* it, see [README.txt](README.txt).

## Porting principle: one module at a time

- **VM core** → **portable C++** (C++98, no STL, no libc, no Symbian API). It compiles
  both with the device's GCCE **and** with a desktop g++, so it can be tested without a
  phone. Lives in `inc/` + `src/vm/`.
- **Shell** (the S60 application, screen, menus, keyboard, sound) → **native Symbian C++**
  (Avkon). Lives in `src/`.
- Each module is compiled and tested before the next one starts. Whatever does not exist
  yet is stated out loud under "Not done".

## Platform decision

There are **two shipping targets**, not one:

| Target | SDK | Runs on |
|---|---|---|
| `fp1` | S60 3rd Edition FP1 (Symbian 9.2) | 9.2 and everything newer — FP2, 5th Edition, Symbian^3 |
| `3rd` | S60 3rd Edition (Symbian 9.1) | 9.1 only |

One binary cannot cover both: the **Avkon DLL ordinals changed in 9.2**, so a 9.2 build
does not load on a 9.1 phone. Forward compatibility holds, backward does not — hence the
second target rather than a second codebase. The sources are identical; only the SDK they
link against differs.

A third target, `2nd` (Symbian 8.1 / EKA1, for the N70, N72 and N90), is **written and its
resources compile**, but no binary ships. See "The 8.1 target" below.

Qt was evaluated and rejected: its toolchain is useful, but Qt 4.7 does not support S60 3rd
FP1 and would require installing the Qt runtime on the phone — unacceptable for the
E-series, which is the main target.

## Java → C++ map

| Java (source) | C++ (destination) | State |
|---|---|---|
| `vm/Vmgp.java` (.mpn loader) | `inc/Vmgp.h` · `src/vm/Vmgp.cpp` | done |
| `vm/Pip2.java` (PIP2 interpreter) | `inc/Pip2.h` · `src/vm/Pip2.cpp` | done |
| `Screen.java` (framebuffer/palette/font) | `inc/Screen.h` · `src/vm/Screen.cpp` | done |
| `vm/Sdk.java` (201 Mophun APIs) | `inc/Sdk.h` · `src/vm/Sdk*.cpp` | done, 0 missing |
| embedded bitmap font | `inc/sysfont.h` | generated from the PC emulator's font |
| `vm/Renderer3D.java` (3D raster) | `inc/Renderer3D.h` · `src/vm/Renderer3D*.cpp` | done, then rewritten in fixed point |
| `vm/VSound.java` (PCM) | `SdkAudio` backend in the shell | `CMdaAudioOutputStream` |
| `mophun/MophunCrypt.java` (DRM) | `inc/MophunCrypt.h` · `src/vm/MophunCrypt.cpp` | done — decrypts on the phone |
| `javax.sound.midi` Sequencer | `inc/MidiSeq.h` · `src/vm/MidiSeq.cpp` | SMF sequencer, kept out of the build (see below) |
| `mophun/*` (Swing GUI, library) | Avkon shell (`src/Wolphun*.cpp`) | done |

The SDK is split across four files so it compiles faster while still producing one `Sdk`
object: `Sdk.cpp` (infrastructure, IntMap, name→id table, heap), `SdkDraw.cpp` (2D,
sprites, tilemaps), `SdkIO.cpp` (input, text, strings/printf, caps, streams, LZ) and
`SdkApi.cpp` (dispatch).

## Verified

The core builds on an ordinary PC with no Symbian SDK, which is how it is regression
tested:

```
g++ -fwrapv -O2 -Wall -Iinc -o test/vmtest test/vmtest.cpp src/vm/*.cpp
```

- **Loader:** all 244 ROMs in the local archive parse, 0 failures.
- **2D:** Honey Cave 2 renders its menu and gameplay — tilemap with the attribute plane,
  sprites, the embedded bitmap font. **0 unimplemented SDK APIs** out of 201.
- **3D:** Rally Pro Contest (the genuine 3D title; V-Rally 2 is pseudo-3D and does not even
  import `vInit3D`) renders the textured track in perspective, billboard trees, mountains,
  the textured car and the HUD.
- **DRM:** encrypted ROMs are decrypted in `Vmgp::Create` on the device. If no key matches,
  the loader says so instead of running into a black screen.

The 3D rasterizer covers the matrix stack, near-plane clipping (Sutherland-Hodgman),
z-buffer with ZFUNCTION, backface culling, Gouraud shading, perspective-correct texturing
(14 pixel formats, wrap/clamp/mirror, bilinear), billboards, fog, blending
(ALPHA/ALPHAADD/MODULATE) and T&L with 8 lights and Blinn-Phong.

There is no `libm`: `inc/fastmath.h` implements sqrt/sin/cos/tan/pow/exp/floor, because
the Symbian GCCE does not guarantee `math.h` without OpenC.

## Making 3D fast enough

S60 3rd devices have **no FPU**. Every `float` operation is an `__aeabi_*` call — about 40
cycles for a multiply, around 200 for a division. The first port was a faithful translation
of the Java renderer and was far too slow. It was then rebuilt:

- **Exact per-scanline span bounds** from edge equations, replacing the bounding-box walk.
  Measured on the test set, the old loop spent about two thirds of its iterations outside
  the triangle.
- **8-pixel perspective subdivision**: one reciprocal per span, affine interpolation
  between. The span length is not a guess — it is the one found by reverse engineering the
  official Mophun VM for Symbian.
- **A fully fixed-point pixel loop**: 16.16 barycentrics and UVs, 10.22 depth, a
  per-triangle scale factor. No float survives in the inner loop.
- **Power-of-two texture wrap by bit mask** instead of a modulo per texel.

Verified **frame for frame against the float renderer on all 19 3D ROMs** in the test set,
so none of this traded accuracy for speed.

Two things were tried and **reverted**, and should not be retried without new evidence:
a 16-bit z-buffer (Carmageddon lost 7% of its pixels and mean colour deviation doubled, for
a gain too small to measure) and clamping the per-pixel z step (it truncated depth and the
z-test then rejected 45% of Carmageddon's pixels).

## The Avkon shell

- **Left softkey = Options**, with every setting rendered as a **native system list**: an
  Avkon `MENU_PANE`, `CAknListQueryDialog` for the choices and the system file browser for
  picking ROMs. Nothing is drawn by hand.
- **Background = the phone's own skin**: `CAknsBasicBackgroundControlContext` with
  `KAknsIIDQsnBgScreen` and the **screen** rectangle. Using `KAknsIIDQsnBgAreaMain` with the
  control's rectangle instead left a black band and smeared when the game did not cover the
  whole area.
- **Resolution is per game.** `TWolphunConfig` is stored per ROM in the private directory.
  8 presets plus "automatic". The game reads the screen size once at startup via
  `vGetCaps`, so changing it restarts the session, with a warning first.
- **Keys**: joystick always active; profiles automatic / QWERTY (WASD + space) / numeric
  (2468/5/0/#) / joystick only / custom, where custom is a free map of any scan code to any
  action. Digits are also delivered **literally** to the game (`vTestKey`/`vScanKeys`).
  The left softkey is never captured by the game — it is always the way back to the menu.
- **Sliced execution.** A `CPeriodic` runs the VM until `vFlipScreen` or until the slice
  budget expires, so the Symbian UI never locks up.
- **Native saves** through `RFs`, separate per ROM, flushed on every write.
- **Interface in 6 languages** (EN/ES/DE/PT/UK/RU) with English as the default, mirroring
  the desktop. The table is generated, with every non-ASCII character escaped — the 2007
  GCCE cannot be trusted with UTF-8 inside a wide literal.
- **Fullscreen**, with a configurable toggle key and three independent ways to leave it.
- **Vibration**, debounced: several games ask every frame, and passing that through makes
  the motor stutter and drains the battery.

### Battery safety

A phone with an aged battery can brown out and switch off under a sustained 3D load. A
frame cap does **not** fix this: in a heavy scene the game is already below the cap, so the
cap is inert — this was measured, capping Rally Pro Contest at 20 fps did not stop an E63
from shutting down.

What works is capping **how long the VM runs inside each tick**. That required fixing the
clock check as well: it only ran every 4096 instructions, and a single instruction can be
an entire `vRenderPrimitive`, so the budget was never enforced during rendering. It now
checks every 256.

## Java → C++ semantics that needed care

- `>>>` (logical shift) → cast to `u32` then `>>`; compile with **`-fwrapv`**, because the
  interpreter relies on signed integer overflow wrapping, as the Java original does.
- `(int)(byte)` / `(int)(short)` → `(i32)(i8)` / `(i32)(i16)`; unsigned comparisons via
  `(u32)`.
- No exceptions (`Vmgp::Create` returns NULL plus an error string) and no STL (own IntMap
  and arrays).
- **No large arrays on the stack**: a Symbian thread stack is small (~8 KB).
- SDK dispatch by an **id resolved once per pool entry** (`Pip2::poolApiId`), not a
  `strcmp` per call — it matters on a ~370 MHz ARM11.
- `Math.round(x*255f/N)` → integer rounding `(v*N*2+den)/(den*2)`, no float.
- **No mutable static data.** An EKA1 DLL cannot have any, and the 8.1 target is exactly
  that. The two cases in the project were removed: the language index moved into
  `WolphunWsd` (a plain static on 9.x, thread-local storage on 8.1) and a lazily built key
  name cache became constant literals. `WolphunWsd` is now the only place with mutable
  global state; anything new breaks the 8.1 target.

## MIDI audio: the system's generic player

One **`CMdaAudioPlayerUtility` per SMF**, opened when the ROM loads and never closed — the
J2ME model. Sound comes out through the phone's own synthesizer and General MIDI bank.

Three things, all of them necessary:

1. **Preloading.** `CWolphunAudio::Preload` scans the game image for `MThd` blocks,
   computes each SMF's size from its chunks, and opens them all at once. Opening a resource
   spins up a whole MMF controller process; done on the first shot instead, sound arrived
   *minutes* later and in a burst.
2. **No reentrancy.** `MapcInitComplete`/`MapcPlayComplete` only set flags; a 20 ms
   `CPeriodic` is what issues `Play`/`Stop`. Calling the utility from inside its own
   callback is defect number 7 in this project's history, and it came back once.
3. **Loop flag and debounce.** `SetRepeats(KMdaRepeatForever)` when the game asks for
   `vPlayResource` with 0x100, and a minimum 80 ms between two `Play` calls on the same
   voice — otherwise the "game over" music restarts every frame and becomes an endless
   loop.

**Why the generic player and not `CMidiClientUtility`:** the latter's `Stop()` is
asynchronous and the end of playback only shows up as a state transition. When the state
did not come back from `EOpenPlaying`, the voice stayed stuck and the effect played exactly
once per session. `CMdaAudioPlayerUtility` has a synchronous `Stop()` and an explicit end
through `MapcPlayComplete`.

Symbian's own documentation says the opposite — it points at `CMidiClientUtility` for MIDI,
and the "Audio Client Overview" does not even list MIDI among the MMF formats — **and in
practice it is wrong**. What settled it was taking apart a commercial game: Asphalt 4 Elite
Racing HD (Gameloft, E71) embeds real SMF files and does **not** import `midiclient.dll`,
only `mediaclientaudio.dll`. The recipe: `dumpsis.exe -x game.sisx` and
`petran.exe -dump i app.exe`, both shipped with the SDK.

Diagnostics live in Options > About: `sound: pcm <err> midi err <e> voices <n> ...`.

### A discarded attempt, not to be repeated

Engaging a single `CMidiClientUtility` in `EClosedEngaged` and sending `NoteOnL`/`NoteOffL`
live, mirroring the PC emulator's `Sequencer` + `Synthesizer`. The engine **does** engage
(confirmed across 5 sessions in the log), but no note produces sound: the BAE only plays an
opened resource. `inc/MidiSeq.*` is what survived that experiment — a portable SMF
sequencer, tested by `test/midtest.cpp`, deliberately kept out of the `.mmp`.

## The 8.1 target (EKA1)

Everything is written and in the tree under `group2nd/`: the EKA1 bootstrap
(`src/WolphunApp2nd.cpp`, exporting `NewApplication` with `E32Dll` instead of using
`eikstart.h`), a `.mmp` producing a polymorphic `wolphun.app`, the old-style `RESOURCE`
syntax, an `.aif` with icons in place of the 9.x registration resource, and an old-format
`.pkg`. **`abld resource armi` passes**, so the 9.x resources are compatible with 2nd
Edition Avkon.

What blocks it is not the code: the ARMI compiler bundled with that 2005 SDK is a cygwin
binary that **segfaults on modern Windows**, and no current toolchain produces its
`arm-epoc-pe` object format. The practical route is building that target inside a virtual
machine running an older Windows.

Two risks are already noted in `group2nd/wolphun2nd.mmp`: gcc 2.9 has no `-fwrapv`, which
the interpreter relies on (in practice that compiler does not perform the optimisations
that exploit the undefined behaviour, but it is the first thing to suspect if a game
diverges from the 9.x build); and on EKA1 the application runs inside `apprun.exe` and
inherits its heap, so `EPOCHEAPSIZE` does not apply — if a large game runs out of memory,
the fix is a dedicated `User::ChunkHeap` for the VM's big allocations.

## Not done

- **Embedded ZIP VFS** (`Vmgp::scanEmbeddedZip`) is a no-op. Only 3D games that keep their
  assets in an internal ZIP need it — The Da Vinci Code, for example.
- **Save states** (a VM snapshot). The game's own native save works.
- **Polyphony.** One sound plays at a time, as on the PC version, so a new effect cuts off
  the previous one.
- **The PCM sound module** (`vSound*`) is implemented but no game in the archive uses it.
- **Cyrillic text drawn by games.** `readStr` truncates UTF-16 to Latin-1, and the portable
  `Screen` font is ASCII 32–126. Fixing it needs the 16-bit character preserved plus a host
  glyph hook drawing through `CFbsBitGc`; Symbian has the Unicode fonts for it.

## Build traps, all of them paid for once

Recorded in `group/build.cmd` as well:

1. `EPOCROOT` does not accept a drive letter, so the project has to live on the **same
   drive as the SDK**. A junction solves it.
2. `set EPOCROOT=...\` without quotes glues the end of line onto the variable — always
   `set "VAR=..."`.
3. `Checkgcc.pm` requires the **first `cpp.exe` on PATH** to sit in a directory ending in
   `\GCC\BIN\`, so the SDK's `epoc32\gcc\bin` must come before Strawberry Perl's `c\bin`.
4. `_reg.rss` includes `wolphun_loc.rsg`, so `_loc` has to be compiled **first** in the
   `.mmp`.
5. `TDes16::Append` does not take 8-bit data — only `Copy` converts — so a temporary buffer
   is needed.
6. The `cpp.exe` shipped with these SDKs is a **cygwin binary that crashes on Windows 11**.
   It is replaced by a MinGW `cpp` together with its `libexec` backend; the original is kept
   alongside as `cpp.exe.cygwin.bak`.
7. The **9.1 SDK ships with `REL_OPTIMISATION` empty** in
   `epoc32/tools/compilation_config/gcce.mk`, so a release build comes out at `-O0` —
   ruinous for the rasterizer, and silent. It is filled in with the FP1 value. Tell them
   apart by size: roughly 99 KB with `-O2`, 128 KB with `-O0`.
8. `abld.bat` **does not propagate the make failure**: a compile error left errorlevel at 0
   and the script printed "OK" while the old binary stayed in place. `build.cmd` now deletes
   the target before building and fails if it does not reappear.
9. `<uikon.hrh>` already defines `KCopyright` as 169, so `_LIT(KCopyright, ...)` expands to
   `_LIT(169, ...)`. Project literals are prefixed `KWolphun` for this reason.
10. The 9.1 `elf2e32` **deflate-compresses** the E32Image. A 99 KB binary there is the same
    code as a 216 KB one from FP1 — not a smaller build.

## Next steps

1. Measure 3D on more devices and keep tuning; the per-triangle setup is still in float
   (about 200 operations and 7 divisions per triangle) and is the largest remaining float
   cost.
2. The Cyrillic glyph hook, which is the one visible correctness gap.
3. If it proves worthwhile: save states, the embedded ZIP VFS, and specialised rasterizer
   loops per render-state combination.
