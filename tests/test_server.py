"""API tests with a fake engine, so they run without downloading the model."""

import io

import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient

from app import server


class FakePrompt:
    def __init__(self, ref_text):
        self.ref_text = ref_text

    def save(self, path):
        open(path, "wb").close()


class FakeEngine:
    state = "ready"
    device = "cpu"
    error = None

    def clone(self, wav, sr, ref_text):
        self.cloned = (wav.shape, sr)
        return FakePrompt(ref_text or "auto transcript")

    def speak(self, prompt, text, language, speed):
        self.spoken = (prompt.ref_text, text, language, speed)
        return np.zeros(2400, dtype=np.float32), 24000


def wav_bytes(seconds, sr=24000):
    buf = io.BytesIO()
    sf.write(buf, np.zeros(int(seconds * sr), dtype=np.float32), sr, format="WAV")
    return buf.getvalue()


def make_client(tmp_path, monkeypatch):
    monkeypatch.setattr(server, "VOICES_DIR", tmp_path)
    engine = FakeEngine()
    return TestClient(server.create_app(engine)), engine


def test_index_and_status(tmp_path, monkeypatch):
    client, _ = make_client(tmp_path, monkeypatch)
    assert "Voice Studio" in client.get("/").text
    status = client.get("/api/status").json()
    assert status["state"] == "ready"
    assert status["languages"] == {"en": "English", "hi": "Hindi"}


def test_clone_then_speak_hindi(tmp_path, monkeypatch):
    client, engine = make_client(tmp_path, monkeypatch)
    res = client.post(
        "/api/clone",
        files={"audio": ("s.wav", wav_bytes(5), "audio/wav")},
        data={"transcript": "  hello there "},
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["transcript"] == "hello there"
    assert body["duration"] == 5.0
    assert (tmp_path / f"{body['voice_id']}.pt").exists()

    res = client.post(
        "/api/speak",
        json={"voice_id": body["voice_id"], "text": "नमस्ते दुनिया", "language": "hi", "speed": 5},
    )
    assert res.status_code == 200
    assert res.headers["content-type"] == "audio/wav"
    assert engine.spoken == ("hello there", "नमस्ते दुनिया", "hi", 2.0)
    audio, sr = sf.read(io.BytesIO(res.content))
    assert sr == 24000 and len(audio) == 2400


def test_rejects_bad_input(tmp_path, monkeypatch):
    client, _ = make_client(tmp_path, monkeypatch)
    short = client.post("/api/clone", files={"audio": ("s.wav", wav_bytes(1), "audio/wav")})
    assert short.status_code == 400
    garbage = client.post("/api/clone", files={"audio": ("s.wav", b"not audio", "audio/wav")})
    assert garbage.status_code == 400
    missing = client.post("/api/speak", json={"voice_id": "nope", "text": "hi"})
    assert missing.status_code == 404
    lang = client.post("/api/speak", json={"voice_id": "x", "text": "hi", "language": "fr"})
    assert lang.status_code == 400
