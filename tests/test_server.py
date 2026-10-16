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
        data={"transcript": "  hello there ", "name": "Rahul"},
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["transcript"] == "hello there"
    assert body["name"] == "Rahul"
    assert body["duration"] == 5.0
    assert (tmp_path / f"{body['id']}.pt").exists()

    res = client.post(
        "/api/speak",
        json={"voice_id": body["id"], "text": "नमस्ते दुनिया", "language": "hi", "speed": 5},
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


def test_saved_voices_list_and_delete(tmp_path, monkeypatch):
    client, _ = make_client(tmp_path, monkeypatch)
    assert client.get("/api/voices").json() == []
    first = client.post("/api/clone", files={"audio": ("a.wav", wav_bytes(4), "audio/wav")}).json()
    second = client.post(
        "/api/clone",
        files={"audio": ("b.wav", wav_bytes(4), "audio/wav")},
        data={"name": "मेरी आवाज़"},
    ).json()
    assert first["name"].startswith("Voice ")
    assert first["transcript"] == "auto transcript"
    listed = client.get("/api/voices").json()
    assert [v["id"] for v in listed] == [second["id"], first["id"]]
    assert listed[0]["name"] == "मेरी आवाज़"

    assert client.delete(f"/api/voices/{first['id']}").status_code == 200
    assert [v["id"] for v in client.get("/api/voices").json()] == [second["id"]]
    assert not (tmp_path / f"{first['id']}.pt").exists()
    assert client.delete("/api/voices/..%2Fetc").status_code == 404
    assert client.delete(f"/api/voices/{first['id']}").status_code == 404


def test_time_estimate_learns_from_runs(tmp_path, monkeypatch):
    client, _ = make_client(tmp_path, monkeypatch)
    before = client.get("/api/status").json()["estimate"]
    assert before == {"overhead": 2.0, "per_char": 0.08}  # CPU defaults
    voice = client.post("/api/clone", files={"audio": ("a.wav", wav_bytes(4), "audio/wav")}).json()
    res = client.post("/api/speak", json={"voice_id": voice["id"], "text": "x" * 100})
    assert float(res.headers["X-Generation-Seconds"]) >= 0
    after = client.get("/api/status").json()["estimate"]
    # The fake engine is instant, so the learned per-character time drops.
    assert after["per_char"] < before["per_char"]


def test_time_estimator_defaults_and_scaling():
    timer = server.TimeEstimator()
    assert timer.estimate("cuda", 100) < timer.estimate("mps", 100) < timer.estimate("cpu", 100)
    timer.record("mps", 100, 1.5 + 100 * 0.04)  # twice as slow as the default guess
    assert timer.as_dict("mps")["per_char"] == 0.03
