@echo off
rem One-command start for Windows: sets up a virtual env on first run,
rem installs PyTorch + OmniVoice, then starts Voice Studio and opens the browser.
setlocal
cd /d "%~dp0"
set TORCH_VERSION=2.8.0

where py >nul 2>nul
if %errorlevel%==0 (set PY=py -3) else (set PY=python)
%PY% -c "import sys; sys.exit(0 if (3,10) <= sys.version_info[:2] <= (3,13) else 1)" 2>nul
if errorlevel 1 (
  echo.
  echo Error: Python 3.10 to 3.13 is required. Install it from https://www.python.org/downloads/
  echo and tick "Add python.exe to PATH" during setup.
  pause
  exit /b 1
)

if not exist .venv\Scripts\python.exe (
  echo Creating a virtual environment ...
  %PY% -m venv .venv || goto :error
)
set VPY=.venv\Scripts\python.exe

if not exist .venv\.installed (
  echo Installing PyTorch and OmniVoice ^(first run only, this can take several minutes^) ...
  %VPY% -m pip install --upgrade pip >nul
  %VPY% -c "import torch" 2>nul
  if errorlevel 1 (
    where nvidia-smi >nul 2>nul
    if not errorlevel 1 (
      %VPY% -m pip install torch==%TORCH_VERSION%+cu128 torchaudio==%TORCH_VERSION%+cu128 --extra-index-url https://download.pytorch.org/whl/cu128 || goto :error
    ) else (
      %VPY% -m pip install torch==%TORCH_VERSION% torchaudio==%TORCH_VERSION% || goto :error
    )
  )
  %VPY% -m pip install -r requirements.txt || goto :error
  echo done> .venv\.installed
)

echo Starting Voice Studio ...
%VPY% -m app.server --open %*
goto :eof

:error
echo.
echo Setup failed. Scroll up to see the error, fix it, then run start.bat again.
pause
exit /b 1
