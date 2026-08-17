@echo off
REM ============================================================================
REM  Empacota o wolphun emulator num .exe portatil (jpackage app-image).
REM  Saida: dist\wolphun emulator\wolphun emulator.exe  (+ runtime Java embutido)
REM  Requer: JDK 8 (javac/jar no PATH) + jpackage (JDK 14+; testado com JDK 17).
REM ============================================================================
setlocal
pushd "%~dp0"

echo === 1/4 Compilando (build.bat) ===
call "%~dp0build.bat"
if errorlevel 1 goto :err

echo === 2/4 Gerando o icone assets\wolphun.ico a partir de assets\wolphun.png ===
if not exist build mkdir build
javac -d build tools\IcoGen.java
if errorlevel 1 goto :err
java -cp build IcoGen assets\wolphun.png assets\wolphun.ico
if errorlevel 1 goto :err

echo === 3/4 Empacotando o .jar executavel ===
if not exist build\app mkdir build\app
if exist "build\app\wolphun.jar" del "build\app\wolphun.jar"
jar cfe build\app\wolphun.jar mophun.Emulator -C out .
if errorlevel 1 goto :err

echo === 4/4 jpackage (app-image, com runtime embutido) ===
if exist "dist\wolphun emulator" rmdir /s /q "dist\wolphun emulator"
jpackage --type app-image --name "wolphun emulator" --input build\app ^
  --main-jar wolphun.jar --main-class mophun.Emulator ^
  --icon assets\wolphun.ico --app-version 0.1.0 ^
  --vendor "Maned Software" --description "Emulator for Mophun (PIP2) mobile games" ^
  --add-modules java.desktop,java.logging --dest dist
if errorlevel 1 goto :err

echo.
echo PRONTO -^> dist\wolphun emulator\wolphun emulator.exe
echo (dica: rode criar-atalho-area-de-trabalho.bat para por um atalho na Area de Trabalho)
goto :eof

:err
echo.
echo *** ERRO no empacotamento. Verifique se javac/jar (JDK 8) e jpackage (JDK 14+) estao no PATH. ***
exit /b 1
