@echo off
REM Roda um unico .mpn no runner CLI. Passe o caminho do arquivo como argumento.
REM Ex.:  run.bat caminho\do\jogo.mpn --scale=4
java -cp out wolphun.Main %*
