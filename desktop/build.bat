@echo off
REM Compila o port JavaSE (Java 8). Requer javac no PATH.
if not exist out mkdir out
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
del sources.txt
REM copia o logo do app para o classpath (icone da janela)
if exist assets\wolphun.png copy /y assets\wolphun.png out\wolphun.png >nul
if %errorlevel%==0 (echo Build OK -^> out\) else (echo ERRO de compilacao)
