@echo off
REM Creates a wolphun emulator shortcut on the Desktop (uses the .exe's own icon).
setlocal
set "EXE=%~dp0dist\wolphun emulator\wolphun emulator.exe"
if not exist "%EXE%" (
  echo The executable does not exist yet.
  echo Run this first:  package-exe.bat
  pause
  goto :eof
)
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ws=New-Object -ComObject WScript.Shell; $p=[Environment]::GetFolderPath('Desktop')+'\wolphun emulator.lnk'; $l=$ws.CreateShortcut($p); $l.TargetPath='%EXE%'; $l.WorkingDirectory=(Split-Path '%EXE%'); $l.IconLocation='%EXE%,0'; $l.Description='wolphun emulator'; $l.Save()"
echo Shortcut 'wolphun emulator' created on the Desktop.
pause
