@echo off
rem YTMusic-MP3 - Lanzador local
cd /d "%~dp0"
echo Iniciando YTMusic -> MP3 en http://127.0.0.1:5088 ...
echo (Cierra esta ventana para detener la app)
start "" http://127.0.0.1:5088
".venv\Scripts\python.exe" app.py
pause
