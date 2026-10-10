@echo off
REM Starts the emulator GUI (ROM library, settings, controls, saves).
REM Build first with build.bat.
java -cp out mophun.Emulator %*
