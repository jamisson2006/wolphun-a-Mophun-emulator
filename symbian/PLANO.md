# wolphun-s60 — port do emulador Mophun para Symbian S60 3rd (Nokia N/E)

Port do **wolphun emulator** (Java SE, por Jamisson Tavares) para **Symbian S60 3rd
Edition (9.x)**, alvo Nokia linha **N** e **E** (telas 320×240, QWERTY).
Origem: `C:\Users\tavar\Downloads\HoneyCave2-JavaSE\src`.

## Princípio do port (honesto, um módulo de cada vez)

- **Núcleo da VM** → **C++ portável** (C++98, sem STL, sem API Symbian). Compila no
  GCCE do device **e** num g++ de desktop (teste sem aparelho). `inc/` + `src/vm/`.
- **Casca** (app S60, tela, menus, teclado) → **Symbian C++ nativo** (Avkon). `src/`.
- Cada módulo é **compilado e testado** antes de seguir. O que ainda não existe fica
  dito em voz alta na seção "Pendente".

## Decisão de plataforma

Alvo = **SDK S60 3rd FP1 (9.2) com Avkon nativo**. Binário roda em 3rd FP1, FP2, 5th
e Symbian^3 → compatibilidade máxima, zero dependência extra no aparelho.
O **Qt SDK** (`D:\QtSdk`) foi avaliado: traz um **GCCE 4.4.1** útil e o Qt Creator, mas
o Qt 4.7.3 dele **não suporta S60 3rd FP1** e exigiria instalar o runtime Qt no celular
— por isso **não** é o caminho para os E-series.

## Mapa Java → C++

| Java (origem) | C++ (destino) | Estado |
|---|---|---|
| `vm/Vmgp.java` (loader .mpn) | `inc/Vmgp.h` · `src/vm/Vmgp.cpp` | ✅ portado e testado |
| `vm/Pip2.java` (interpretador PIP2) | `inc/Pip2.h` · `src/vm/Pip2.cpp` | ✅ portado e testado |
| `Screen.java` (framebuffer/paleta/fonte) | `inc/Screen.h` · `src/vm/Screen.cpp` | ✅ portado e testado |
| `vm/Sdk.java` (201 APIs mophun) | `inc/Sdk.h` · `src/vm/Sdk*.cpp` | ✅ portado e testado (2D completo) |
| — fonte bitmap embutida | `inc/sysfont.h` | ✅ gerada da mesma fonte do emulador PC |
| `vm/Renderer3D.java` (raster 3D) | `inc/Renderer3D.h` · `src/vm/Renderer3D*.cpp` | ✅ portado e testado |
| `vm/VSound.java` (PCM) | backend `SdkAudio` na casca | ✅ `CMdaAudioOutputStream` |
| `Sequencer` do `javax.sound.midi` | `inc/MidiSeq.h` · `src/vm/MidiSeq.cpp` | ✅ sequenciador de SMF (sem síntese) |
| `mophun/MophunCrypt.java` (DRM) | — | ⬜ pendente (precisa bignum próprio) |
| `mophun/*` (GUI Swing, biblioteca) | casca S60 Avkon (`src/Wolphun*.cpp`) | ✅ compila e empacota |

Divisão do Sdk em 4 arquivos (compila mais rápido, mesmo objeto `Sdk`):
`Sdk.cpp` (infra, IntMap, tabela nome→id, heap) · `SdkDraw.cpp` (2D, sprites, tilemap) ·
`SdkIO.cpp` (entrada, texto, strings/printf, caps, streams, LZ) · `SdkApi.cpp` (despacho).

## O que já roda (verificado 2026-10-01)

Compilado com `g++ (GCC) 4.4.3` (bundle do Strawberry Perl) — mesma era do GCCE do device:

```
g++ -fwrapv -O2 -Wall -Iinc -o test/vmtest.exe test/vmtest.cpp src/vm/*.cpp
```

`test/vmtest.exe honeycave2.mpn 20000000 hc2.ppm 320 240` →

```
VMGP ok: code=32408 data=3672 bss=44688 res=23472 pool=556 recursos=18
tipo: 2D
instrucoes executadas : 20000000
frames (vFlipScreen)  : 2
pixels escritos (2D)  : 20809
tela nao-preta        : 20747 de 76800 (27%)
APIs nao implementadas: 0
```

**O menu do Honey Cave 2 renderiza de verdade** (`test/hc2.png`): céu, teto da caverna
com estalactites (tilemap com plano de atributos), sprite de ampulheta e o menu
"New game / Highscore / Help / Quit" com a fonte bitmap embutida.
**Zero APIs não implementadas** — as 201 entradas do SDK estão mapeadas.

## 3D verificado (2026-10-01)

O rasterizador foi portado inteiro: pilha de matrizes, recorte no near-plane
(Sutherland-Hodgman), z-buffer com ZFUNCTION, backface culling, gouraud, textura
**perspective-correct** (14 formatos de pixel, wrap/clamp/mirror, bilinear), billboards,
fog, blending (ALPHA/ALPHAADD/MODULATE) e T&L (8 luzes, Blinn-Phong).

Sem `libm`: `inc/fastmath.h` implementa sqrt/sin/cos/tan/pow/exp/floor em float
(o GCCE do Symbian não garante `math.h` sem OpenC).

**Teste com Rally Pro Contest** (`...decrypted_decompressed.mpn`, o 3D real — o V-Rally 2
é pseudo-3D e nem importa `vInit3D`):

```
tipo: 3D (importa vInit3D)
frames (vFlipScreen)  : 249
tela nao-preta        : 36589 de 36608 (99%)
APIs nao implementadas: 0
3D: triangulos=61993  pixels=2827516
```

A corrida renderiza: pista texturizada em perspectiva, árvores como billboards,
montanhas, o carro 3D texturizado e o HUD (`test/rpc4.png`).

### Validação em massa do loader
As **244 ROMs** de `~/.mophunemu/decrypted/` foram parseadas — **244 OK, 0 falhas**.
ROMs S60/PPC "packed" (LZ) continuam recusadas com erro claro: use a versão
`_decompressed` (igual ao emulador de PC).

## Pendente (NÃO é mágica — falta portar)

- **VFS/ZIP embutido** (`Vmgp::scanEmbeddedZip`): no-op. Só jogos 3D que guardam assets
  num ZIP interno (ex.: Da Vinci Code) precisam.
- **DRM (`MophunCrypt`)**: ROMs criptografadas ainda precisam ser descriptografadas no PC.
- **Save states** (snapshot da VM): não portados; o **save nativo do jogo** (resume,
  highscore) funciona via `SdkFileSystem`.

## Semântica Java→C++ cuidada

- `>>>` (shift lógico) → cast `u32` + `>>`; compilar com **`-fwrapv`** (overflow de int).
- `(int)(byte)` / `(int)(short)` → `(i32)(i8)` / `(i32)(i16)`; comparações sem sinal → `(u32)`.
- Sem exceções (`Vmgp::Create` devolve NULL+erro); sem STL (IntMap/arrays próprios).
- **Sem arrays grandes na pilha**: a pilha de uma thread Symbian é pequena (~8 KB).
- Despacho do SDK por **id resolvido uma vez por entrada de pool** (`Pip2::poolApiId`),
  não por `strcmp` a cada chamada — importa num ARM11 de ~369 MHz.
- `Math.round(x*255f/N)` → arredondamento inteiro `(v*N*2+den)/(den*2)`, sem float.

## Casca S60 construída (2026-10-01)

App Avkon nativo, **compilado para ARM e empacotado**:

```
groupuild.cmd    ->  wolphun.exe   161.928 bytes (GCCE/ARM, S60 3rd FP1)
sis\makesis.cmd    ->  wolphun_fp1.sisx  (auto-assinado)
```

- **Softkey esquerda = Opções**, com todas as configurações em **listas do próprio
  sistema**: menu `MENU_PANE` do Avkon + `CAknRadioButtonSettingPage` (radio-botões
  padrão do S60) + `CAknListQueryDialog` para escolher o jogo. Nada desenhado à mão.
- **Fundo = skin nativo da Nokia**: `CAknsBasicBackgroundControlContext` com
  `KAknsIIDQsnBgAreaMain` + `AknsDrawUtils::Background`, e `BaseConstructL(EAknEnableSkin)`.
  O emulador usa o tema que o usuário escolheu no aparelho.
- **Resolução por jogo**: `TWolphunConfig` é salva por ROM no diretório privado
  (`CWolphunConfigStore`). 7 presets. Como o jogo lê o tamanho em `vGetCaps` na init,
  trocar reinicia a sessão — com aviso antes.
- **Teclas**: joystick sempre ativo; perfis Automático / QWERTY (WASD+espaço) /
  numérico (2468/5/0/#) / só joystick. Os dígitos também vão **literalmente** ao jogo
  (`vTestKey`/`vScanKeys`). A softkey esquerda nunca é capturada pelo jogo.
- **Execução em fatias**: `CPeriodic` a ~50 Hz roda a VM até o `vFlipScreen` ou até
  gastar 400k instruções — a interface do Symbian nunca trava.
- **Saves nativos** do jogo via `RFs`, separados por ROM, gravados a cada escrita.

### Armadilhas do build (resolvidas, anotadas no build.cmd)
1. `EPOCROOT` não aceita letra de drive → o projeto tem de estar no **mesmo drive do
   SDK**. Solução: junção `C:\Symbian\wolphun-s60` → `D:\symbian\wolphun-s60`.
2. `set EPOCROOT=...\` sem aspas gruda o fim de linha na variável → use `set "VAR=..."`.
3. `Checkgcc.pm` exige que o **primeiro `cpp.exe` do PATH** esteja num diretório
   terminado em `\GCC\BIN\` → `epoc32\gccin` antes do `cin` do Strawberry.
4. O `_reg.rss` inclui `wolphun_loc.rsg` → o `_loc` tem de vir **antes** no `.mmp`.
5. `TDes16::Append` não aceita 8 bits (só `Copy` converte) → buffer temporário.

## Áudio MIDI: tocador genérico do sistema (2026-10-02) — FUNCIONANDO

Um **`CMdaAudioPlayerUtility` por SMF**, aberto na **carga da ROM** e nunca fechado — o
modelo do J2ME. O som sai pelo sintetizador e pelo banco General MIDI do próprio aparelho.

Três coisas, todas necessárias:

1. **Pré-carga.** `CWolphunAudio::Preload` varre a imagem do jogo atrás de blocos `MThd`,
   calcula o tamanho do SMF pelos chunks e abre todos de uma vez. Abrir um recurso sobe um
   processo controlador MMF inteiro: feito no primeiro tiro, o som saía *minutos* depois,
   e em rajada (medido no rastro do EKA2L1).
2. **Nada de reentrância.** `MapcInitComplete`/`MapcPlayComplete` só anotam flags; quem
   emite `Play`/`Stop` é um `CPeriodic` de 20 ms. Chamar o utilitário de dentro do callback
   dele é o defeito nº 7 do histórico, e ele voltou uma vez.
3. **Flag de laço + anti-repique.** `SetRepeats(KMdaRepeatForever)` quando o jogo pede
   `vPlayResource` com 0x100; e um intervalo mínimo de 80 ms entre dois `Play` na mesma voz,
   senão a música de "game over" reinicia a cada quadro e vira um laço eterno.

**Por que o tocador genérico e não `CMidiClientUtility`:** o `Stop()` daquele é ASSÍNCRONO
e o fim da reprodução só aparece como transição de estado — se o estado não voltava de
`EOpenPlaying`, a voz ficava presa e o efeito tocava uma única vez na partida inteira.
O `CMdaAudioPlayerUtility` tem `Stop()` síncrono e fim explícito por `MapcPlayComplete`.

A documentação da Symbian diz o contrário (ela aponta `CMidiClientUtility` para MIDI, e o
"Audio Client Overview" nem lista MIDI entre os formatos do MMF) — **e está errada na
prática**. Quem apontou o caminho foi desmontar um jogo comercial: o Asphalt 4 Elite
Racing HD (Gameloft, E71) embarca SMF de verdade e **não importa `midiclient.dll`**, só
`mediaclientaudio.dll`. Receita: `dumpsis.exe -x jogo.sisx` e `petran.exe -dump i app.exe`,
ambos na SDK.

Diagnóstico em Opções > Sobre: `som: pcm <err> midi err <e> vozes <n> pedidos <n> tocou <n>`.

### Tentativa descartada (não repetir)
Engatar um único `CMidiClientUtility` em `EClosedEngaged` e mandar `NoteOnL`/`NoteOffL` ao
vivo, espelhando o `Sequencer`+`Synthesizer` do emulador de PC. O motor **engata** (o log
confirmou em 5 sessões), mas nenhuma nota vira som: o BAE só toca um recurso aberto.
`inc/MidiSeq.*` ficou desse experimento — é um sequenciador de SMF portável, testado em
`test/midtest.cpp`, fora do `.mmp`.

## Próximos passos

1. Testar no aparelho/emulador e ajustar desempenho do 3D.
3. Se fizer sentido: DRM (`MophunCrypt`), save states, VFS/ZIP embutido.
