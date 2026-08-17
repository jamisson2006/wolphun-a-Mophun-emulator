@echo off
REM Cria um atalho do wolphun emulator na Area de Trabalho (usa o icone do proprio .exe).
setlocal
set "EXE=%~dp0dist\wolphun emulator\wolphun emulator.exe"
if not exist "%EXE%" (
  echo O executavel ainda nao existe.
  echo Rode primeiro:  empacotar-exe.bat
  pause
  goto :eof
)
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ws=New-Object -ComObject WScript.Shell; $p=[Environment]::GetFolderPath('Desktop')+'\wolphun emulator.lnk'; $l=$ws.CreateShortcut($p); $l.TargetPath='%EXE%'; $l.WorkingDirectory=(Split-Path '%EXE%'); $l.IconLocation='%EXE%,0'; $l.Description='wolphun emulator'; $l.Save()"
echo Atalho 'wolphun emulator' criado na Area de Trabalho.
pause
