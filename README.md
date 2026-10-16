# Voice Studio

A simple, fully local voice cloning app, a bit like ElevenLabs but running on your own machine.
It uses the open-source [OmniVoice](https://github.com/k2-fsa/OmniVoice) model (Apache-2.0) from k2-fsa.

**Feature 1: voice cloning**

1. Clone a voice by either **recording live** with the record button or **uploading** an existing sample (WAV, MP3, M4A, OGG, WEBM). Give it a name if you like. Cloned voices are saved under **My voices**, so you can reuse them later.
2. Pick **English** or **हिन्दी (Hindi)**, edit the sample text, and hear it spoken in the cloned voice. A progress bar shows the estimated time left, and the estimate learns from your machine after each run. Download the result as WAV.

Nothing leaves your computer: the model runs locally and cloned voices are saved in `voices/`.

## Quick start

You need Python 3.10 to 3.13 and a few GB of free disk for the model.

**Mac or Linux**

```bash
git clone https://github.com/rahul576/ai-voice-over-voice.git
cd ai-voice-over-voice
./start.sh
```

**Windows:** download or clone the repo, then double-click `start.bat`.

The first run creates a `.venv` folder, installs PyTorch and OmniVoice, and downloads the model from Hugging Face. That can take 5 to 15 minutes depending on your connection. After that, it starts in seconds. Your browser opens at http://127.0.0.1:7860 automatically. The pill in the top right says **Loading model…** and turns green when the model is ready.

On a Mac with Apple Silicon (M1 to M4), it runs on the GPU (`mps`). On Windows or Linux with an NVIDIA card, the script installs the CUDA build of PyTorch. Otherwise it runs on the CPU, which works but is slower.

### Options

Pass these to `start.sh` or `start.bat`, or to `python -m app.server` if you set things up yourself:

| Flag | Default | Meaning |
| --- | --- | --- |
| `--port` | `7860` | Port to serve on. If it is busy, the next free port is used. |
| `--host` | `127.0.0.1` | Use `0.0.0.0` to open it from other devices on your network. |
| `--device` | auto | `cuda`, `mps`, `xpu` or `cpu` |
| `--model` | `k2-fsa/OmniVoice` | Hugging Face id or a local folder with the weights |

### Manual setup

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install torch==2.8.0 torchaudio==2.8.0   # NVIDIA: add +cu128 and --extra-index-url https://download.pytorch.org/whl/cu128
pip install -r requirements.txt
python -m app.server --open
```

## Troubleshooting

- **"Can't reach 127.0.0.1:7860"**: the app runs only while the terminal window running `start.sh` or `start.bat` is open. Check that window for errors, and use the address it prints, since it may pick another port if 7860 is busy.
- **"Model failed to load"** in the top right: hover over it, or check the terminal, to see why. The usual cause is no internet access to Hugging Face on the first run. Try `export HF_ENDPOINT=https://hf-mirror.com` (Windows: `set HF_ENDPOINT=https://hf-mirror.com`), then start again.
- **"Python 3.10 to 3.13 is required"**: on a Mac, run `brew install python@3.12`. On Windows, install from python.org and tick "Add python.exe to PATH".
- **The microphone doesn't work**: allow microphone access when the browser asks. On a Mac, also check System Settings > Privacy & Security > Microphone for your browser. Recording only works on `127.0.0.1` or `localhost`, or over HTTPS.
- **Start fresh**: delete the `.venv` folder and run the start script again.

## Try it without recording

The `samples/` folder has two short test clips: `english_sample.mp3` and `hindi_sample.mp3`. Upload one on the **Upload** tab to check that everything works end to end. They are synthetic voices made with espeak-ng, so the clone will sound robotic. Your own recording will sound much better.

## Tips for a good clone

- Use **3 to 10 seconds** of clean speech with no music or background noise. Clips longer than 15 seconds are trimmed.
- Record in the **same language** you want to generate. A Hindi sample gives the most natural Hindi; an English sample speaking Hindi will carry an English accent (and vice versa).
- Fill in **"What is said in the sample?"** when you know it. When recording, it is pre-filled with the script you read. If you leave it empty, the app transcribes the sample automatically with Whisper (an extra ~1.5 GB download the first time).

## Project layout

```
start.sh / start.bat One-command setup and start
app/server.py        FastAPI backend wrapping OmniVoice (clone, speak and saved-voice endpoints)
app/static/          The web UI (plain HTML, CSS and JavaScript, no build step)
tests/               API tests that run without downloading the model
samples/             Two short English and Hindi test clips
voices/              Saved cloned voices (created at runtime, git-ignored)
```

Run the tests with `pip install pytest httpx && python -m pytest`.
