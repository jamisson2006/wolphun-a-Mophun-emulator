@echo off
REM Runs a single ROM directly (opens the window). Keys: arrows/WASD move,
REM Space/Enter/Ctrl OK-fire, Q/E softkeys.
REM Useful args: --scale=3  --w=176 --h=220  --trace (log syscalls)  --headless (no window)
REM Pass the path to your own .mpn file -- no ROMs are included with this project.
java -cp out wolphun.Main %*
