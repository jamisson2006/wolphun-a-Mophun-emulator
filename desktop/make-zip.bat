@echo off
REM ============================================================================
REM  Builds a distributable ZIP of wolphun emulator (executable + runtime +
REM  a launcher .bat + README). Output in the project root: wolphun-emulator-0.3.0-beta.zip
REM  If the .exe does not exist yet, package-exe.bat is called automatically.
REM ============================================================================
setlocal
pushd "%~dp0"

set "APPDIR=dist\wolphun emulator"
if not exist "%APPDIR%\wolphun emulator.exe" (
  echo Executable not found. Running package-exe.bat...
  call "%~dp0package-exe.bat"
  if errorlevel 1 goto :err
)

echo === Building staging folder ===
if exist "build\zip" rmdir /s /q "build\zip"
mkdir "build\zip\wolphun emulator"
xcopy /e /i /q "%APPDIR%" "build\zip\wolphun emulator" >nul
copy /y "dist-extras\README.txt" "build\zip\wolphun emulator\README.txt" >nul
copy /y "dist-extras\Start wolphun emulator.bat" "build\zip\wolphun emulator\Start wolphun emulator.bat" >nul

echo === Compressing ===
set "ZIP=wolphun-emulator-0.3.0-beta.zip"
if exist "%ZIP%" del "%ZIP%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path 'build\zip\wolphun emulator' -DestinationPath '%ZIP%' -CompressionLevel Optimal"
if errorlevel 1 goto :err

echo.
echo DONE -^> %ZIP%
goto :eof

:err
echo.
echo *** FAILED to build the ZIP. ***
exit /b 1
