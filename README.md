# Voice Studio

A simple, fully local voice cloning app, a bit like ElevenLabs but running on your own machine.
It uses the open-source [OmniVoice](https://github.com/k2-fsa/OmniVoice) model (Apache-2.0) from k2-fsa.

**Feature 1: voice cloning**

1. Clone a voice by either **recording live** with the record button or **uploading** an existing sample (WAV, MP3, M4A, OGG, WEBM).
2. Pick **English** or **हिन्दी (Hindi)**, edit the sample text, and hear it spoken in the cloned voice. Download the result as WAV.

Nothing leaves your computer: the model runs locally and cloned voices are saved in `voices/`.

## Setup

Requires Python 3.10+ and a few GB of free disk for the model weights.

```bash
git clone https://github.com/rahul576/ai-voice-over-voice.git
cd ai-voice-over-voice
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
```

**Install PyTorch for your hardware** (pick one):

```bash
# NVIDIA GPU (CUDA 12.8)
pip install torch==2.8.0+cu128 torchaudio==2.8.0+cu128 --extra-index-url https://download.pytorch.org/whl/cu128

# Apple Silicon (M1/M2/M3/M4) or CPU-only
pip install torch==2.8.0 torchaudio==2.8.0
```

**Install the app:**

```bash
pip install -r requirements.txt
```

## Run

```bash
python -m app.server
```

Open http://127.0.0.1:7860 in your browser. The first start downloads the OmniVoice model from Hugging Face; the status pill in the top right turns green when it is ready.

Options:

| Flag | Default | Meaning |
| --- | --- | --- |
| `--port` | `7860` | Port to serve on |
| `--host` | `127.0.0.1` | Use `0.0.0.0` to open it from other devices on your network |
| `--device` | auto | `cuda`, `mps`, `xpu` or `cpu` |
| `--model` | `k2-fsa/OmniVoice` | Hugging Face id or a local folder with the weights |

If Hugging Face is slow or blocked for you, run `export HF_ENDPOINT=https://hf-mirror.com` first.

## Tips for a good clone

- Use **3 to 10 seconds** of clean speech with no music or background noise. Clips longer than 15 seconds are trimmed.
- Record in the **same language** you want to generate. A Hindi sample gives the most natural Hindi; an English sample speaking Hindi will carry an English accent (and vice versa).
- Fill in **"What is said in the sample?"** when you know it. When recording, it is pre-filled with the script you read. If you leave it empty, the app transcribes the sample automatically with Whisper (an extra ~1.5 GB download the first time).
- The microphone works on `localhost`. If you open the app from another device, the browser requires HTTPS for recording, so use the upload option there.

## Project layout

```
app/server.py        FastAPI backend wrapping OmniVoice (clone + speak endpoints)
app/static/          The web UI (plain HTML, CSS and JavaScript, no build step)
tests/               API tests that run without downloading the model
voices/              Saved cloned voices (created at runtime, git-ignored)
```

Run the tests with `pip install pytest httpx && python -m pytest`.
