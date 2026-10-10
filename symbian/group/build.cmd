@echo off
REM build.cmd - compila o emulador para o aparelho (GCCE/ARM) no S60 3rd FP1.
REM Uso:  build.cmd         -> bldmake + abld build gcce urel
REM       build.cmd clean   -> limpa
REM
REM NOTA 1: o EPOCROOT do Symbian NAO leva letra de drive, entao o projeto tem de estar
REM no MESMO drive do SDK. Os arquivos vivem em D:\symbian\wolphun-s60 (compartilhado com
REM a VM do VirtualBox) e C:\Symbian\wolphun-s60 e uma JUNCAO para la, entao da para
REM compilar "em C:" ou "em D:": cada alvo abaixo diz em qual drive esta o seu SDK.
REM NOTA 2: o set TEM de ser na forma  set "VAR=valor"  -- sem as aspas, a barra final do
REM EPOCROOT gruda no fim de linha e o caminho sai corrompido.
REM NOTA 3: ambiente ja preparado nesta maquina: Strawberry Perl nativo + o cpp do MinGW
REM no lugar do cpp cygwin do SDK (que trava no Windows 11).

REM ---------------------------------------------------------------------------
REM ALVO. Sem argumento compila para o S60 3rd FP1 (9.2), que e o alvo de sempre.
REM   build.cmd fp1      -> S60 3rd FP1  (9.2)  Nokia E63/E71/N95 e tudo mais NOVO
REM   build.cmd 3rd      -> S60 3rd Ed   (9.1)  Nokia N73/N80/N91/N92/N93/E60/E61/E70
REM   build.cmd 5th      -> S60 5th Ed   (9.4)  N97/5800/X6 (toque)
REM O codigo e o MESMO nos tres: a auditoria de API nao achou nada posterior ao 3rd Ed
REM inicial (AknCommonDialogs e @since 1.2; o resto e anterior as anotacoes). O que muda
REM e so contra qual SDK se liga, porque os ORDINAIS das DLLs mudam entre versoes -- e e
REM exatamente por isso que nao basta um binario so.
REM Cada alvo exige o seu SDK instalado, e o DRV do alvo tem de ser o drive onde ele esta
REM (o EPOCROOT do Symbian nao leva letra de drive, entao SDK e projeto no mesmo drive).
REM NOTA 4: o gcce.mk do 3rd Ed inicial (9.1) vem com REL_OPTIMISATION VAZIO -- o urel
REM sairia em -O0, varias vezes mais lento no rasterizador. Foi preenchido com
REM -O2 -fno-unit-at-a-time, igual ao do FP1; o original ficou em
REM epoc32\tools\compilation_config\gcce.mk.orig. Se o SDK for reinstalado, refazer.
REM Confere-se pelo tamanho do .exe: ~99 KB com -O2, ~128 KB com -O0.
REM ---------------------------------------------------------------------------
set "ALVO=%1"
if "%ALVO%"=="" set "ALVO=fp1"
if /i "%ALVO%"=="clean" set "ALVO=fp1"

REM Cada alvo diz: o DRIVE (o EPOCROOT nao leva letra, entao o projeto tem de estar no
REM mesmo drive do SDK), o caminho do SDK nesse drive e onde esta o group do projeto.
set "SDKDIR="
set "PLAT=gcce"
set "GRP=group"
if /i "%ALVO%"=="fp1" set "DRV=C:" & set "SDKDIR=\Symbian\9.2\S60_3rd_FP1" & set "PROJ=\Symbian\wolphun-s60"
if /i "%ALVO%"=="3rd" set "DRV=D:" & set "SDKDIR=\S60\devices"             & set "PROJ=\symbian\wolphun-s60"
if /i "%ALVO%"=="5th" set "DRV=D:" & set "SDKDIR=\S60\devices\S60_5th_Edition_SDK_v1.0" & set "PROJ=\symbian\wolphun-s60"
if /i "%ALVO%"=="n97" set "DRV=D:" & set "SDKDIR=\S60\devices\Nokia_N97_SDK_v1.0"       & set "PROJ=\symbian\wolphun-s60"
REM 2nd Edition FP3 (Symbian 8.1, EKA1): a plataforma e ARMI, nao GCCE, e o app e uma
REM DLL polimorfica .app -- precisa de um .mmp e de um bld.inf proprios, em group2nd.
REM BLOQUEIO CONHECIDO: o compilador do ARMI e o arm-epoc-pe-gcc 2.9 que vem DENTRO do
REM SDK (epoc32\gccin), e ele e um binario cygwin de 2003 que da SEGMENTATION FAULT
REM neste Windows 11 -- gcc, g++ e cpp, todos, mesmo com --version. Nao ha troca possivel
REM como a do cpp nos alvos 9.x: ali o compilador de verdade e o CSL ARM Toolchain, que e
REM moderno e gera ELF; aqui o formato e o PE do arm-epoc-pe, que nenhuma toolchain atual
REM produz. O caminho pratico e compilar este alvo DENTRO da VM do VirtualBox, que ja
REM compartilha D:\symbian -- la o toolchain do SDK roda nativamente.
if /i "%ALVO%"=="2nd" set "DRV=D:" & set "SDKDIR=\S60" & set "PROJ=\symbian\wolphun-s60" & set "PLAT=armi" & set "GRP=group2nd"
if not defined SDKDIR goto badtarget

REM SDK sem barra no fim (o resto do script faz %SDK%\epoc32);
REM EPOCROOT COM barra e SEM letra de drive -- as duas exigencias do Symbian.
set "SDK=%DRV%%SDKDIR%"
set "EPOCROOT=%SDKDIR%\"
if /i "%PLAT%"=="armi" goto chk1
if not exist "%SDK%\epoc32\release\armv5\lib\euser.dso" goto nosdk
goto chkok
:chk1
if not exist "%SDK%\epoc32\release\armi\urel\euser.lib" goto nosdk
:chkok
echo === alvo %ALVO%  SDK %SDK%  plataforma %PLAT%  grupo %GRP% ===

REM ORDEM DO PATH IMPORTA: o Checkgcc.pm do SDK exige que o PRIMEIRO cpp.exe do PATH
REM esteja num diretorio terminado em \GCC\BIN\. Por isso o epoc32\gcc\bin do SDK (onde
REM o cpp cygwin foi trocado pelo do MinGW) vem ANTES do c\bin do Strawberry, que tambem
REM tem um cpp.exe e sequestrava a checagem.
set "PATH=C:\Symbian\Tools\StrawberryPerl\perl\bin;C:\Symbian\Tools\StrawberryPerl\perl\site\bin;%SDK%\epoc32\gcc\bin;%SDK%\epoc32\tools;C:\Program Files (x86)\CSL Arm Toolchain\bin;%PATH%;C:\Symbian\Tools\StrawberryPerl\c\bin"

%DRV%
cd %PROJ%\%GRP%

if /i "%1"=="clean" goto doclean
if /i "%2"=="clean" goto doclean

echo === bldmake bldfiles ===
call bldmake.bat bldfiles
if errorlevel 1 goto fail

REM O abld.bat NAO propaga a falha do make: um erro de compilacao deixava o
REM errorlevel em 0 e este script imprimia "OK" com o binario VELHO no lugar.
REM Por isso o alvo e apagado antes: se nao reaparecer, o build falhou de verdade.
set "SAIDA=%SDK%\epoc32\release\%PLAT%\urel\wolphun.exe"
if /i "%PLAT%"=="armi" set "SAIDA=%SDK%\epoc32\release\%PLAT%\urel\wolphun.app"
if exist "%SAIDA%" del "%SAIDA%"

echo === abld build %PLAT% urel ===
call "%CD%\ABLD.BAT" build %PLAT% urel
if errorlevel 1 goto fail
if not exist "%SAIDA%" goto fail

echo.
echo === OK ===
dir "%SDK%\epoc32\release\%PLAT%\urel\wolphun.*"
goto end

:doclean
call "%CD%\ABLD.BAT" clean
goto end

:badtarget
echo *** alvo desconhecido: %ALVO%
echo *** use:  build.cmd fp1   ^(S60 3rd FP1 9.2 - padrao^)
echo ***       build.cmd 3rd   ^(S60 3rd Ed 9.1 - N73/N80/E61/E70^)
echo ***       build.cmd 2nd   ^(S60 2nd Ed FP3 8.1 - N70/N90 - EKA1, incompleto^)
echo ***       build.cmd 5th   ^(S60 5th Ed 9.4 - N97/5800^)
exit /b 1

:nosdk
echo *** o SDK do alvo "%ALVO%" nao esta instalado em %SDK%
echo *** instale-o nesse caminho e rode de novo; o codigo nao precisa mudar.
exit /b 1

:fail
echo.
echo *** FALHOU ***
exit /b 1

:end
