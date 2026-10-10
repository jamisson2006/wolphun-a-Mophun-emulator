@echo off
REM ============================================================================
REM  Packages wolphun emulator as a portable .exe (jpackage app-image).
REM  Output: dist\wolphun emulator\wolphun emulator.exe  (+ bundled Java runtime)
REM  Requires: JDK 8 (javac/jar on PATH) + jpackage (JDK 14+; tested with JDK 17).
REM ============================================================================
setlocal
pushd "%~dp0"

echo === 1/4 Compiling (build.bat) ===
call "%~dp0build.bat"
if errorlevel 1 goto :err

echo === 2/4 Generating assets\wolphun.ico from assets\wolphun.png ===
if not exist build mkdir build
javac -d build tools\IcoGen.java
if errorlevel 1 goto :err
java -cp build IcoGen assets\wolphun.png assets\wolphun.ico
if errorlevel 1 goto :err

echo === 3/4 Building the executable .jar ===
if not exist build\app mkdir build\app
if exist "build\app\wolphun.jar" del "build\app\wolphun.jar"
jar cfe build\app\wolphun.jar mophun.Emulator -C out .
if errorlevel 1 goto :err

echo === 4/4 jpackage (app-image, with bundled runtime) ===
if exist "dist\wolphun emulator" rmdir /s /q "dist\wolphun emulator"
jpackage --type app-image --name "wolphun emulator" --input build\app ^
  --main-jar wolphun.jar --main-class mophun.Emulator ^
  --icon assets\wolphun.ico --app-version 0.3.0 ^
  --vendor "Maned Software" --description "Emulator for Mophun (PIP2) mobile games" ^
  --add-modules java.desktop,java.logging --dest dist
if errorlevel 1 goto :err

echo.
echo DONE -^> dist\wolphun emulator\wolphun emulator.exe
echo (tip: run create-desktop-shortcut.bat to put a shortcut on the Desktop)
goto :eof

:err
echo.
echo *** PACKAGING FAILED. Check that javac/jar (JDK 8) and jpackage (JDK 14+) are on PATH. ***
exit /b 1
