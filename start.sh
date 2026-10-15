#!/usr/bin/env bash
# One-command start for Mac and Linux: sets up a virtual env on first run,
# installs PyTorch + OmniVoice, then starts Voice Studio and opens the browser.
set -euo pipefail
cd "$(dirname "$0")"

VENV=.venv
TORCH_VERSION=2.8.0

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }

# 1. Find a Python 3.10 - 3.13 interpreter.
PY=""
for cand in python3.12 python3.11 python3.13 python3.10 python3; do
  if command -v "$cand" >/dev/null 2>&1 &&
     "$cand" -c 'import sys; sys.exit(0 if (3,10) <= sys.version_info[:2] <= (3,13) else 1)' 2>/dev/null; then
    PY="$cand"; break
  fi
done
[ -n "$PY" ] || fail "Python 3.10 to 3.13 is required. On a Mac: 'brew install python@3.12', or download it from https://www.python.org/downloads/"

# 2. Create the virtual environment once.
if [ ! -x "$VENV/bin/python" ]; then
  say "Creating a virtual environment with $($PY --version) ..."
  "$PY" -m venv "$VENV" || fail "Could not create a virtual environment. On Ubuntu/Debian run: sudo apt install python3-venv"
fi
VPY="$VENV/bin/python"

# 3. Install dependencies once (and again whenever requirements.txt changes).
STAMP="$VENV/.installed"
REQ_HASH=$("$VPY" -c 'import hashlib,sys; print(hashlib.sha1(open("requirements.txt","rb").read()).hexdigest())')
if [ ! -f "$STAMP" ] || [ "$(cat "$STAMP")" != "$REQ_HASH" ]; then
  say "Installing PyTorch and OmniVoice (first run only, this can take several minutes) ..."
  "$VPY" -m pip install --upgrade pip >/dev/null
  if ! "$VPY" -c 'import torch' 2>/dev/null; then
    if [ "$(uname -s)" = "Linux" ] && command -v nvidia-smi >/dev/null 2>&1; then
      "$VPY" -m pip install "torch==$TORCH_VERSION+cu128" "torchaudio==$TORCH_VERSION+cu128" \
        --extra-index-url https://download.pytorch.org/whl/cu128
    elif [ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "x86_64" ]; then
      fail "PyTorch $TORCH_VERSION has no build for Intel Macs. Please run this on an Apple Silicon Mac, Linux or Windows."
    else
      "$VPY" -m pip install "torch==$TORCH_VERSION" "torchaudio==$TORCH_VERSION"
    fi
  fi
  "$VPY" -m pip install -r requirements.txt
  echo "$REQ_HASH" > "$STAMP"
fi

# 4. Start the app (extra arguments are passed through, e.g. ./start.sh --port 8000).
say "Starting Voice Studio ..."
exec "$VPY" -m app.server --open "$@"
