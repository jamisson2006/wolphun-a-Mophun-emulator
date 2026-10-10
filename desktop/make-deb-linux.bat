@echo off
REM ============================================================================
REM  Builds the Debian/Ubuntu package of wolphun emulator FROM WINDOWS.
REM  Output: wolphun-emulator_0.3.0~beta-1_all.deb
REM
REM  Install on Linux:    sudo apt install ./wolphun-emulator_0.3.0~beta-1_all.deb
REM  Run:                 wolphun          (or from the applications menu)
REM  Uninstall:           sudo apt remove wolphun-emulator
REM
REM  Requires: just a JDK on PATH (javac/jar). No WSL, no dpkg-deb, no ar needed:
REM  tools\DebGen.java builds the .deb's ar + tar.gz archives in pure Java.
REM
REM  NOTE: the .deb does NOT bundle a Java runtime (jpackage on Windows only
REM  produces Windows packages). It relies on the system java -> Depends: default-jre.
REM ============================================================================
setlocal
pushd "%~dp0"

set "VER=0.3.0"
set "DEBVER=0.3.0~beta-1"
set "PKG=wolphun-emulator"
set "OUT=%PKG%_%DEBVER%_all.deb"
set "STG=build\deb"

echo === 1/5 Compiling for Java 8 (widest JRE compatibility) ===
REM --release 8: the default-jre on Ubuntu 22.04 is still Java 11; building with
REM javac 21 and no --release would emit class version 65 and it would not even start.
if exist "build\linux-classes" rmdir /s /q "build\linux-classes"
mkdir "build\linux-classes"
dir /s /b src\*.java > sources-linux.txt
javac --release 8 -nowarn -encoding UTF-8 -d build\linux-classes @sources-linux.txt
set "RC=%errorlevel%"
del sources-linux.txt
if not "%RC%"=="0" goto :err
if exist assets\wolphun.png copy /y assets\wolphun.png build\linux-classes\wolphun.png >nul

echo === 2/5 Building the executable .jar ===
if not exist build\linux mkdir build\linux
if exist "build\linux\wolphun.jar" del "build\linux\wolphun.jar"
jar cfe build\linux\wolphun.jar mophun.Emulator -C build\linux-classes .
if errorlevel 1 goto :err

echo === 3/5 Building the package tree ===
if exist "%STG%" rmdir /s /q "%STG%"
mkdir "%STG%\DEBIAN"
mkdir "%STG%\usr\bin"
mkdir "%STG%\usr\lib\wolphun"
mkdir "%STG%\usr\share\applications"
mkdir "%STG%\usr\share\pixmaps"
mkdir "%STG%\usr\share\doc\%PKG%"

copy /y "build\linux\wolphun.jar"            "%STG%\usr\lib\wolphun\wolphun.jar"         >nul
copy /y "dist-extras\linux\wolphun"          "%STG%\usr\bin\wolphun"                     >nul
copy /y "dist-extras\linux\wolphun.desktop"  "%STG%\usr\share\applications\wolphun.desktop" >nul
copy /y "dist-extras\linux\copyright"        "%STG%\usr\share\doc\%PKG%\copyright"       >nul
if exist "dist-extras\linux\LEIA-ME.txt" copy /y "dist-extras\linux\LEIA-ME.txt" "%STG%\usr\share\doc\%PKG%\LEIA-ME.txt" >nul
if exist assets\wolphun.png copy /y assets\wolphun.png "%STG%\usr\share\pixmaps\wolphun.png" >nul

REM The control file is generated here so the VERSION lives in one place (this .bat).
REM DebGen normalizes CRLF -> LF and appends Installed-Size.
> "%STG%\DEBIAN\control" (
  echo Package: %PKG%
  echo Version: %DEBVER%
  echo Section: games
  echo Priority: optional
  echo Architecture: all
  echo Depends: default-jre ^| java8-runtime
  echo Maintainer: Maned Software ^<tavaresjamisson@gmail.com^>
  echo Homepage: https://youtube.com/@jamissontavares7671
  echo Description: Emulator for Mophun ^(PIP2^) mobile games
  echo  wolphun runs Mophun games ^(.mpn^) from Sony Ericsson, Symbian S60 and
  echo  Pocket PC phones: PIP2 virtual machine, 2D and 3D software renderer,
  echo  MIDI/PCM sound, save states, key remapping and a game library browser.
  echo  .
  echo  Needs a system Java runtime ^(Swing^); no game ROMs are included.
)

echo === 4/5 Compiling the DebGen packager ===
if not exist build\tools mkdir build\tools
javac --release 8 -nowarn -encoding UTF-8 -d build\tools tools\DebGen.java
if errorlevel 1 goto :err

echo === 5/5 Generating the .deb ===
if exist "%OUT%" del "%OUT%"
java -cp build\tools DebGen "%STG%" "%OUT%"
if errorlevel 1 goto :err

echo.
echo DONE -^> %OUT%
echo.
echo On Debian/Ubuntu:
echo     sudo apt install ./%OUT%
echo     wolphun
goto :eof

:err
echo.
echo *** FAILED to build the .deb. Check that javac/jar ^(JDK^) are on PATH. ***
popd
exit /b 1
