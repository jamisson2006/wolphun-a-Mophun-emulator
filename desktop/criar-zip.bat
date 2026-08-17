@echo off
REM ============================================================================
REM  Monta um ZIP distribuivel do wolphun emulator (so o executavel + runtime +
REM  um .bat de atalho + LEIA-ME). Saida na raiz: wolphun-emulator-0.1.0-beta.zip
REM  Se o .exe ainda nao existir, chama empacotar-exe.bat automaticamente.
REM ============================================================================
setlocal
pushd "%~dp0"

set "APPDIR=dist\wolphun emulator"
if not exist "%APPDIR%\wolphun emulator.exe" (
  echo Executavel nao encontrado. Rodando empacotar-exe.bat...
  call "%~dp0empacotar-exe.bat"
  if errorlevel 1 goto :err
)

echo === Montando pasta de staging ===
if exist "build\zip" rmdir /s /q "build\zip"
mkdir "build\zip\wolphun emulator"
xcopy /e /i /q "%APPDIR%" "build\zip\wolphun emulator" >nul
copy /y "dist-extras\LEIA-ME.txt" "build\zip\wolphun emulator\LEIA-ME.txt" >nul
copy /y "dist-extras\Iniciar wolphun emulator.bat" "build\zip\wolphun emulator\Iniciar wolphun emulator.bat" >nul

echo === Compactando ===
set "ZIP=wolphun-emulator-0.1.0-beta.zip"
if exist "%ZIP%" del "%ZIP%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path 'build\zip\wolphun emulator' -DestinationPath '%ZIP%' -CompressionLevel Optimal"
if errorlevel 1 goto :err

echo.
echo PRONTO -^> %ZIP%
goto :eof

:err
echo.
echo *** ERRO ao gerar o ZIP. ***
exit /b 1
