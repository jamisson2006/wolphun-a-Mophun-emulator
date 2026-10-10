@echo off
REM makesis.cmd - gera e ASSINA o pacote instalavel wolphun.sisx
REM Alvo igual ao do build.cmd:  makesis.cmd [fp1|3rd|5th]  (padrao fp1).
REM TEM de ser o mesmo com que se compilou, senao empacota o binario de outro SDK.
REM
REM Como no build.cmd, cada alvo diz em que DRIVE esta o seu SDK: o EPOCROOT do
REM Symbian nao leva letra de drive, entao as ferramentas tem de rodar com o drive
REM corrente igual ao do SDK. O projeto esta em D:\symbian\wolphun-s60 e C:\Symbian\
REM wolphun-s60 e uma juncao para la, por isso ha um PROJ por drive.
set "ALVO=%1"
if "%ALVO%"=="" set "ALVO=fp1"
set "SDKDIR="
if /i "%ALVO%"=="fp1" set "DRV=C:" & set "SDKDIR=\Symbian\9.2\S60_3rd_FP1" & set "PROJ=\Symbian\wolphun-s60"
if /i "%ALVO%"=="3rd" set "DRV=D:" & set "SDKDIR=\S60\devices"             & set "PROJ=\symbian\wolphun-s60"
if /i "%ALVO%"=="5th" set "DRV=D:" & set "SDKDIR=\S60\devices\S60_5th_Edition_SDK_v1.0" & set "PROJ=\symbian\wolphun-s60"
if /i "%ALVO%"=="n97" set "DRV=D:" & set "SDKDIR=\S60\devices\Nokia_N97_SDK_v1.0"       & set "PROJ=\symbian\wolphun-s60"
REM 8.1 (EKA1): o .pkg e outro (SIS velho, .app em vez de .exe) e NAO ha assinatura.
if /i "%ALVO%"=="2nd" set "DRV=D:" & set "SDKDIR=\S60" & set "PROJ=\symbian\wolphun-s60"
if not defined SDKDIR goto badtarget
set "SDK=%DRV%%SDKDIR%"
set "EPOCROOT=%SDKDIR%\"
if /i "%ALVO%"=="2nd" goto pkg2nd
if not exist "%SDK%\epoc32\release\gcce\urel\wolphun.exe" goto noexe
echo === empacotando o alvo %ALVO%  SDK %SDK% ===
set "PATH=C:\Symbian\Tools\StrawberryPerl\perl\bin;%SDK%\epoc32\tools;%SDK%\epoc32\gcc\bin;%PATH%"
%DRV%
cd %PROJ%\sis

echo === makesis ===
REM o makesis deste SDK nao expande $(EPOCROOT): o modelo e resolvido aqui, com o
REM perl que o proprio SDK ja exige no PATH.
REM o @ TEM de ser escapado: no padrao, perl interpola @SDK como ARRAY e o que
REM sobra e um "@" solto, que casa duas vezes e duplica o caminho.
perl -pe "BEGIN{$s=$ENV{SDK}} s/\@SDK\@/$s/g" wolphun_gcce.pkg > wolphun_build.pkg
if errorlevel 1 goto fail
makesis wolphun_build.pkg wolphun_%ALVO%.sis
if errorlevel 1 goto fail

if not exist wolphun.cer (
  echo === gerando certificado de teste ===
  makekeys -cert -password test -len 2048 -dname "CN=wolphun OU=emu OR=maned CO=BR" wolphun.key wolphun.cer
)

echo === signsis ===
signsis wolphun_%ALVO%.sis wolphun_%ALVO%.sisx wolphun.cer wolphun.key test
if errorlevel 1 goto fail

echo.
echo === OK ===
dir wolphun_%ALVO%.sisx
goto end
:pkg2nd
if not exist "%SDK%\epoc32\release\armi\urel\wolphun.app" goto noapp
echo === empacotando o alvo 2nd ^(SIS velho, sem assinatura^)  SDK %SDK% ===
set "PATH=C:\Symbian\Tools\StrawberryPerl\perl\bin;%SDK%\epoc32\tools;%SDK%\epoc32\gcc\bin;%PATH%"
%DRV%
cd %PROJ%\sis
perl -pe "BEGIN{$s=$ENV{SDK}} s/\@SDK\@/$s/g" wolphun_2nd.pkg > wolphun_build2nd.pkg
if errorlevel 1 goto fail
makesis wolphun_build2nd.pkg wolphun_2nd.sis
if errorlevel 1 goto fail
echo.
echo === OK ===
dir wolphun_2nd.sis
goto end

:noapp
echo *** nao ha wolphun.app compilado ^(alvo 2nd^)
echo *** veja a NOTA do alvo 2nd em group\build.cmd: o compilador ARMI do SDK 8.1
echo *** da segmentation fault neste Windows, esse alvo tem de ser compilado na VM.
exit /b 1

:badtarget
echo *** alvo desconhecido: %ALVO%   use fp1, 3rd, 5th, n97 ou 2nd
exit /b 1

:noexe
echo *** nao ha wolphun.exe compilado para o alvo "%ALVO%"
echo *** rode antes:  build.cmd %ALVO%
exit /b 1

:fail
echo *** FALHOU ***
exit /b 1
:end
