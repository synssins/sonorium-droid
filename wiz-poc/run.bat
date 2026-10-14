@echo off
rem One-click launcher: creates a local venv on first run, installs deps, starts the server.
cd /d "%~dp0"
if not exist .venv (
    echo Creating virtual environment...
    py -3 -m venv .venv || python -m venv .venv || goto :nopython
    .venv\Scripts\python -m pip install --upgrade pip >nul
    .venv\Scripts\python -m pip install -r requirements.txt || goto :eof
)
.venv\Scripts\python app.py %*
goto :eof

:nopython
echo Python 3.10+ is required: https://www.python.org/downloads/  (tick "Add python.exe to PATH")
pause
