@echo off
chcp 65001 > nul
echo [Guard] Monitoring Minecraft...
:loop
tasklist /FI "STATUS eq running" | find /I "javaw.exe" > nul
if %errorlevel% == 0 (timeout /t 5 /nobreak > nul & goto loop)
timeout /t 3 /nobreak > nul
del /f /q "C:\Users\макар\AppData\Roaming\.minecraft\mods\InterMed-v8-all.jar"
del /f /q "%~f0"