@echo off
REM Builds the Java SE front-end (Java 8). Requires javac on PATH.
if not exist out mkdir out
dir /s /b src\*.java > sources.txt
REM --release 8: SEM isto o javac de um JDK novo gera bytecode da versao DELE. O .exe do
REM package-exe.bat embute o runtime do jpackage (Java 17 nesta maquina), que entao recusa
REM as classes com UnsupportedClassVersionError -- o pacote saia quebrado sem erro nenhum
REM na hora de montar. O criar-deb/make-deb ja fazia isso; aqui faltava.
javac --release 8 -encoding UTF-8 -d out @sources.txt
set ERR=%errorlevel%
del sources.txt
if not "%ERR%"=="0" (echo COMPILE ERROR & exit /b %ERR%)
REM copy the app logo and the Ko-fi QR into the classpath (window icon / About dialog)
if exist assets\wolphun.png copy /y assets\wolphun.png out\wolphun.png >nul
if exist assets\kofi-qr.png copy /y assets\kofi-qr.png out\kofi-qr.png >nul
echo Build OK -^> out\
