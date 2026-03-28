@echo off
chcp 65001 > nul
echo [Guard] Monitoring Minecraft process...
:loop
tasklist /FI "IMAGENAME eq javaw.exe" | find "javaw.exe" > nul
if %errorlevel% == 0 (timeout /t 5 /nobreak > nul & goto loop)
timeout /t 2 /nobreak > nul
del /f /q "C:\Users\макар\AppData\Roaming\.minecraft\mods\InterMed-v8-all.jar"
echo [Success] Trace cleaned.
del /f /q "%~f0"