"""Local voice cloning studio powered by OmniVoice (https://github.com/k2-fsa/OmniVoice).

Run with:  python -m app.server
"""

from __future__ import annotations

import argparse
import io
import json
import logging
import os
import socket
import threading
import time
import uuid
import webbrowser
from pathlib import Path

import numpy as np
import soundfile as sf
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

log = logging.getLogger("voice-studio")

ROOT = Path(__file__).resolve().parent
STATIC_DIR = ROOT / "static"
VOICES_DIR = Path(os.environ.get("VOICES_DIR", ROOT.parent / "voices"))

LANGUAGES = {"en": "English", "hi": "Hindi"}
MAX_TEXT_CHARS = 2000
MAX_UPLOAD_BYTES = 30 * 1024 * 1024


class SpeakRequest(BaseModel):
    voice_id: str
    text: str
    language: str = "en"
    speed: float = 1.0


class TimeEstimator:
    """Predicts how long speech generation takes: fixed overhead + seconds per character.

    Starts from rough per-device guesses and learns from each finished run,
    so estimates match the user's own machine after a generation or two.
    """

    # (overhead seconds, seconds per character) before anything is measured
    DEFAULTS = {"cuda": (0.5, 0.004), "xpu": (1.0, 0.01), "mps": (1.5, 0.02), "cpu": (2.0, 0.08)}

    def __init__(self):
        self.per_char: dict[str, float] = {}

    def _params(self, device: str | None) -> tuple[float, float]:
        overhead, per_char = self.DEFAULTS.get((device or "cpu").split(":")[0], self.DEFAULTS["cpu"])
        return overhead, self.per_char.get(device or "cpu", per_char)

    def estimate(self, device: str | None, chars: int) -> float:
        overhead, per_char = self._params(device)
        return overhead + per_char * chars

    def record(self, device: str | None, chars: int, seconds: float) -> None:
        overhead, per_char = self._params(device)
        observed = max(seconds - overhead, 0.0) / max(chars, 1)
        # Weighted average so one odd run doesn't swing the estimate too far.
        self.per_char[device or "cpu"] = 0.5 * per_char + 0.5 * observed

    def as_dict(self, device: str | None) -> dict:
        overhead, per_char = self._params(device)
        return {"overhead": round(overhead, 3), "per_char": round(per_char, 5)}


class TTSEngine:
    """Loads OmniVoice once in the background and serialises access to it."""

    def __init__(self, model_name: str, device: str | None):
        self.model_name = model_name
        self.requested_device = device
        self.device: str | None = None
        self.model = None
        self.error: str | None = None
        self._lock = threading.Lock()
        self._asr_loaded = False

    @property
    def state(self) -> str:
        if self.error:
            return "error"
        return "ready" if self.model is not None else "loading"

    def load(self) -> None:
        try:
            import torch
            from omnivoice import OmniVoice
            from omnivoice.utils.common import get_best_device

            device = self.requested_device or get_best_device()
            # float16 is fast on GPUs but poorly supported on CPU.
            dtype = torch.float32 if device == "cpu" else torch.float16
            log.info("Loading %s on %s (%s) ...", self.model_name, device, dtype)
            self.model = OmniVoice.from_pretrained(
                self.model_name, device_map=device, dtype=dtype
            )
            self.device = device
            log.info("Model ready.")
        except ImportError as exc:
            log.exception("Missing dependency")
            self.error = (
                f"A required package is missing ({exc.name}). "
                "Run start.sh (Mac/Linux) or start.bat (Windows) to install everything."
            )
        except Exception as exc:  # surfaced to the UI via /api/status
            log.exception("Failed to load OmniVoice")
            self.error = (
                f"Could not load the OmniVoice model ({exc}). The first start needs internet "
                "access to huggingface.co; see Troubleshooting in the README."
            )

    def _require_model(self):
        if self.model is None:
            raise HTTPException(
                503, self.error or "The voice model is still loading, please wait."
            )
        return self.model

    def clone(self, wav: np.ndarray, sr: int, ref_text: str | None):
        import torch

        model = self._require_model()
        with self._lock:
            if not ref_text and not self._asr_loaded:
                # Whisper is only needed when the user leaves the transcript empty.
                log.info("Loading Whisper ASR for auto-transcription ...")
                model.load_asr_model()
                self._asr_loaded = True
            waveform = torch.from_numpy(wav).float().unsqueeze(0)
            return model.create_voice_clone_prompt(
                ref_audio=(waveform, sr), ref_text=ref_text or None
            )

    def speak(self, prompt, text: str, language: str, speed: float) -> tuple[np.ndarray, int]:
        model = self._require_model()
        with self._lock:
            audio = model.generate(
                text=text,
                language=language,
                voice_clone_prompt=prompt,
                speed=speed,
            )
        return audio[0], model.sampling_rate


def read_audio(raw: bytes) -> tuple[np.ndarray, int]:
    """Decode an uploaded clip into a mono float32 waveform."""
    try:
        data, sr = sf.read(io.BytesIO(raw), dtype="float32", always_2d=True)
    except Exception:
        # Formats libsndfile can't read (mp3/m4a/webm) go through librosa/ffmpeg.
        try:
            import librosa

            data, sr = librosa.load(io.BytesIO(raw), sr=None, mono=False)
            data = np.atleast_2d(data).T
        except Exception as exc:
            raise HTTPException(400, f"Could not read this audio file: {exc}") from exc
    wav = data.mean(axis=1)
    if wav.size < sr * 2:
        raise HTTPException(400, "The voice sample is too short. Please use at least 3 seconds.")
    return wav, sr


def create_app(engine: TTSEngine) -> FastAPI:
    app = FastAPI(title="Voice Studio")

    @app.middleware("http")
    async def no_stale_ui(request, call_next):
        # Make browsers re-check the UI files so an update shows up on a normal reload.
        response = await call_next(request)
        if not request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-cache"
        return response

    timer = TimeEstimator()
    voices: dict[str, dict] = {}
    VOICES_DIR.mkdir(parents=True, exist_ok=True)

    def voice_meta_path(voice_id: str) -> Path:
        if not voice_id.isalnum():
            raise HTTPException(404, "Voice not found.")
        return VOICES_DIR / f"{voice_id}.json"

    def get_voice(voice_id: str) -> dict:
        if voice_id in voices:
            return voices[voice_id]
        path = VOICES_DIR / f"{voice_id}.pt"
        if voice_id.isalnum() and path.is_file():
            from omnivoice import VoiceClonePrompt

            voices[voice_id] = {"prompt": VoiceClonePrompt.load(str(path))}
            return voices[voice_id]
        raise HTTPException(404, "Voice not found. Please clone a voice first.")

    @app.get("/")
    def index():
        return FileResponse(STATIC_DIR / "index.html")

    @app.get("/api/status")
    def status():
        return {
            "state": engine.state,
            "device": engine.device,
            "error": engine.error,
            "languages": LANGUAGES,
            "estimate": timer.as_dict(engine.device),
        }

    @app.get("/api/voices")
    def list_voices():
        items = []
        for meta in VOICES_DIR.glob("*.json"):
            try:
                data = json.loads(meta.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if (VOICES_DIR / f"{data.get('id')}.pt").is_file():
                data["has_sample"] = (VOICES_DIR / f"{data['id']}.wav").is_file()
                items.append(data)
        items.sort(key=lambda v: v.get("created", 0), reverse=True)
        return items

    @app.post("/api/clone")
    async def clone(
        audio: UploadFile = File(...),
        transcript: str = Form(""),
        name: str = Form(""),
    ):
        raw = await audio.read()
        if len(raw) > MAX_UPLOAD_BYTES:
            raise HTTPException(413, "The audio file is too large (30 MB max).")
        wav, sr = read_audio(raw)
        duration = wav.size / sr
        prompt = engine.clone(wav, sr, transcript.strip())
        voice_id = uuid.uuid4().hex[:12]
        prompt.save(str(VOICES_DIR / f"{voice_id}.pt"))
        # Keep the reference clip so it can be played back from My voices.
        sf.write(str(VOICES_DIR / f"{voice_id}.wav"), wav, sr, subtype="PCM_16")
        meta = {
            "id": voice_id,
            "name": name.strip()[:40] or f"Voice {time.strftime('%d %b %H:%M')}",
            "transcript": prompt.ref_text,
            "duration": round(duration, 1),
            "created": time.time(),
        }
        voice_meta_path(voice_id).write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
        voices[voice_id] = {"prompt": prompt}
        return {**meta, "has_sample": True}

    @app.get("/api/voices/{voice_id}/sample")
    def voice_sample(voice_id: str):
        path = VOICES_DIR / f"{voice_id}.wav"
        if not voice_id.isalnum() or not path.is_file():
            raise HTTPException(404, "No sample saved for this voice.")
        return FileResponse(path, media_type="audio/wav")

    @app.delete("/api/voices/{voice_id}")
    def delete_voice(voice_id: str):
        meta = voice_meta_path(voice_id)
        if not meta.is_file():
            raise HTTPException(404, "Voice not found.")
        meta.unlink()
        (VOICES_DIR / f"{voice_id}.pt").unlink(missing_ok=True)
        (VOICES_DIR / f"{voice_id}.wav").unlink(missing_ok=True)
        voices.pop(voice_id, None)
        return {"ok": True}

    @app.post("/api/speak")
    def speak(req: SpeakRequest):
        text = req.text.strip()
        if not text:
            raise HTTPException(400, "Please type some text to speak.")
        if len(text) > MAX_TEXT_CHARS:
            raise HTTPException(400, f"Text is too long ({MAX_TEXT_CHARS} characters max).")
        if req.language not in LANGUAGES:
            raise HTTPException(400, "Language must be English or Hindi.")
        voice = get_voice(req.voice_id)
        speed = min(max(req.speed, 0.5), 2.0)
        started = time.monotonic()
        wav, sr = engine.speak(voice["prompt"], text, req.language, speed)
        elapsed = time.monotonic() - started
        # Slower speech means more audio to generate, so scale by speed.
        timer.record(engine.device, round(len(text) / speed), elapsed)
        buf = io.BytesIO()
        sf.write(buf, wav, sr, format="WAV", subtype="PCM_16")
        return Response(
            buf.getvalue(),
            media_type="audio/wav",
            headers={"X-Generation-Seconds": f"{elapsed:.2f}"},
        )

    @app.exception_handler(HTTPException)
    async def http_error(_, exc: HTTPException):
        return JSONResponse({"detail": exc.detail}, status_code=exc.status_code)

    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return app


def main() -> None:
    parser = argparse.ArgumentParser(description="Local OmniVoice voice studio")
    parser.add_argument(
        "--host", default="0.0.0.0", help="0.0.0.0 lets phones on your Wi-Fi connect; 127.0.0.1 keeps it on this computer"
    )
    parser.add_argument("--port", type=int, default=7860)
    parser.add_argument("--model", default="k2-fsa/OmniVoice", help="HF model id or local path")
    parser.add_argument("--device", default=None, help="cuda, mps, xpu or cpu (auto by default)")
    parser.add_argument("--open", action="store_true", help="Open the app in your browser")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    port = find_free_port(args.host, args.port)
    if port != args.port:
        print(f"  Port {args.port} is busy, using {port} instead.")

    engine = TTSEngine(args.model, args.device)
    threading.Thread(target=engine.load, daemon=True).start()

    import uvicorn

    url = f"http://{'127.0.0.1' if args.host == '0.0.0.0' else args.host}:{port}"
    print(f"\n  Voice Studio is running at {url}  (press Ctrl+C to stop)")
    if args.host == "0.0.0.0" and (lan := lan_address()):
        print(f"  Phones on the same Wi-Fi can connect to http://{lan}:{port}")
    print("  The first start downloads the OmniVoice model, which can take a few minutes.\n")
    if args.open:
        threading.Timer(1.5, webbrowser.open, args=(url,)).start()
    uvicorn.run(create_app(engine), host=args.host, port=port, log_level="warning")


def lan_address() -> str | None:
    """This computer's address on the local network, for the phone app to connect to."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(("192.168.0.1", 9))  # no packets are sent; this just picks the outgoing interface
            return sock.getsockname()[0]
        except OSError:
            return None


def find_free_port(host: str, start: int) -> int:
    for port in range(start, start + 20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            if sock.connect_ex(("127.0.0.1" if host == "0.0.0.0" else host, port)) != 0:
                return port
    return start


if __name__ == "__main__":
    main()
