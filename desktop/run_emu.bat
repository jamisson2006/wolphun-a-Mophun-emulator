@echo off
REM Inicia o Mophun Emulator (GUI: biblioteca de ROMs, settings, controles, saves).
REM Compile antes com build.bat (ele ja cobre src\mophun).
java -cp out mophun.Emulator %*
