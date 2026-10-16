// Voice Studio front end: record or upload a sample, clone it, then speak text.

const SCRIPTS = {
  en: "The quick brown fox jumps over the lazy dog. I love exploring new places and meeting new people every day.",
  hi: "नमस्ते, आज मौसम बहुत सुहाना है। मुझे नई जगहों पर घूमना और नए लोगों से मिलना बहुत पसंद है।",
};

const SAMPLE_TEXTS = {
  en: "Hello! This is my cloned voice speaking. Isn't it amazing how natural it sounds? You can edit this text and try anything you like.",
  hi: "नमस्ते! यह मेरी क्लोन की हुई आवाज़ है। क्या यह सुनने में बिल्कुल असली नहीं लगती? आप इस टेक्स्ट को बदलकर कुछ भी बोलवा सकते हैं।",
};

const TARGET_RATE = 24000; // OmniVoice works at 24 kHz
const MAX_SECONDS = 60;     // the server cleans the noise and the model keeps the best 20s or less

const $ = (id) => document.getElementById(id);

const state = {
  source: "record",
  scriptLang: "en",
  speakLang: "en",
  texts: { ...SAMPLE_TEXTS },
  sampleBlob: null, // WAV blob of the reference sample
  voiceId: null,
  modelReady: false,
  device: null,
  estimate: { overhead: 2, per_char: 0.08 }, // refined by the server after each run
  busy: false,
};

// ---------- helpers ----------

function toast(msg) {
  const t = $("toast");
  t.textContent = msg;
  t.classList.remove("hidden");
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => t.classList.add("hidden"), 5000);
}

function setBusy(btn, busy, label) {
  btn.disabled = busy;
  btn.innerHTML = busy ? `<span class="spinner"></span>${label}` : label;
}

function activate(group, attr, value) {
  group.querySelectorAll(`[${attr}]`).forEach((b) => {
    b.classList.toggle("active", b.getAttribute(attr) === value);
  });
}

async function apiError(res) {
  try {
    const data = await res.json();
    return data.detail || res.statusText;
  } catch {
    return res.statusText;
  }
}

// Decode any browser-playable audio, downmix to mono, resample to 24 kHz,
// trim to MAX_SECONDS and encode as 16-bit WAV so the server always gets WAV.
async function toWav(blob) {
  const buf = await blob.arrayBuffer();
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  let decoded;
  try {
    decoded = await ctx.decodeAudioData(buf);
  } finally {
    ctx.close();
  }
  const seconds = Math.min(decoded.duration, MAX_SECONDS);
  const length = Math.ceil(seconds * TARGET_RATE);
  const offline = new OfflineAudioContext(1, length, TARGET_RATE);
  const src = offline.createBufferSource();
  src.buffer = decoded;
  src.connect(offline.destination);
  src.start();
  const rendered = await offline.startRendering();
  return {
    blob: encodeWav(rendered.getChannelData(0), TARGET_RATE),
    duration: decoded.duration,
    trimmed: decoded.duration > MAX_SECONDS,
  };
}

function encodeWav(samples, rate) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const write = (o, s) => [...s].forEach((c, i) => view.setUint8(o + i, c.charCodeAt(0)));
  write(0, "RIFF");
  view.setUint32(4, 36 + samples.length * 2, true);
  write(8, "WAVE");
  write(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  write(36, "data");
  view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Blob([view], { type: "audio/wav" });
}

// ---------- model status ----------

async function pollStatus() {
  try {
    const res = await fetch("/api/status");
    const s = await res.json();
    const pill = $("status");
    pill.className = "pill";
    if (s.state === "ready") {
      state.modelReady = true;
      state.device = s.device;
      if (s.estimate) state.estimate = s.estimate;
      updateCount();
      pill.classList.add("pill-ready");
      pill.textContent = `● Ready · ${s.device}`;
      refreshButtons();
      return;
    }
    if (s.state === "error") {
      pill.classList.add("pill-error");
      pill.textContent = "Model failed to load";
      pill.title = s.error || "";
      toast(`Model failed to load: ${s.error}`);
      return;
    }
    pill.classList.add("pill-loading");
    pill.textContent = "Loading model… (first run downloads it)";
  } catch {
    $("status").textContent = "Server offline";
  }
  setTimeout(pollStatus, 2000);
}

function refreshButtons() {
  $("clone-btn").disabled = !state.modelReady || !state.sampleBlob || state.busy;
  $("speak-btn").disabled = !state.modelReady || !state.voiceId || state.busy;
  $("step-speak").classList.toggle("disabled", !state.voiceId);
}

// ---------- source tabs ----------

document.querySelectorAll("[data-source]").forEach((btn) => {
  btn.addEventListener("click", () => {
    if (recorder.active) return;
    state.source = btn.dataset.source;
    activate(btn.parentElement, "data-source", state.source);
    $("pane-record").classList.toggle("hidden", state.source !== "record");
    $("pane-upload").classList.toggle("hidden", state.source !== "upload");
    $("pane-saved").classList.toggle("hidden", state.source !== "saved");
    $("sample-preview").classList.toggle("hidden", state.source === "saved" || !state.sampleBlob);
  });
});

function renderScript() {
  $("script-text").textContent = SCRIPTS[state.scriptLang];
  $("script-text").lang = state.scriptLang;
}

$("script-lang").addEventListener("click", (e) => {
  const lang = e.target.dataset.lang;
  if (!lang) return;
  state.scriptLang = lang;
  activate($("script-lang"), "data-lang", lang);
  renderScript();
});

// ---------- recording ----------

const recorder = { active: false, media: null, chunks: [], stream: null, start: 0, timer: null, raf: null };

async function startRecording() {
  try {
    recorder.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true },
    });
  } catch {
    toast("Microphone access was blocked. Allow it in your browser and try again.");
    return;
  }
  recorder.chunks = [];
  recorder.media = new MediaRecorder(recorder.stream);
  recorder.media.ondataavailable = (e) => e.data.size && recorder.chunks.push(e.data);
  recorder.media.onstop = onRecordingStopped;
  recorder.media.start();
  recorder.active = true;
  recorder.start = Date.now();

  $("rec-btn").classList.add("recording");
  $("rec-btn").setAttribute("aria-label", "Stop recording");
  $("rec-hint").textContent = "Recording… tap to stop";
  recorder.timer = setInterval(() => {
    const secs = Math.floor((Date.now() - recorder.start) / 1000);
    $("rec-time").textContent = `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, "0")}`;
    if (secs >= MAX_SECONDS) stopRecording();
  }, 200);
  startMeter(recorder.stream);
}

function startMeter(stream) {
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 512;
  ctx.createMediaStreamSource(stream).connect(analyser);
  const data = new Uint8Array(analyser.fftSize);
  const tick = () => {
    analyser.getByteTimeDomainData(data);
    let peak = 0;
    for (const v of data) peak = Math.max(peak, Math.abs(v - 128));
    $("meter-fill").style.width = `${Math.min(100, (peak / 128) * 160)}%`;
    recorder.raf = requestAnimationFrame(tick);
  };
  tick();
  recorder.meterCtx = ctx;
}

function stopRecording() {
  if (!recorder.active) return;
  recorder.active = false;
  clearInterval(recorder.timer);
  cancelAnimationFrame(recorder.raf);
  recorder.meterCtx?.close();
  $("meter-fill").style.width = "0";
  recorder.media.stop();
  recorder.stream.getTracks().forEach((t) => t.stop());
  $("rec-btn").classList.remove("recording");
  $("rec-btn").setAttribute("aria-label", "Start recording");
  $("rec-hint").textContent = "Tap to record again";
}

async function onRecordingStopped() {
  const blob = new Blob(recorder.chunks, { type: recorder.media.mimeType });
  // Pre-fill the transcript with the script the user was asked to read.
  await useSample(blob, "Your recording", SCRIPTS[state.scriptLang]);
}

$("rec-btn").addEventListener("click", () => (recorder.active ? stopRecording() : startRecording()));

// ---------- upload ----------

$("file-input").addEventListener("change", (e) => {
  const file = e.target.files[0];
  if (file) useSample(file, file.name, "");
  e.target.value = "";
});

const dz = $("dropzone");
dz.addEventListener("dragover", (e) => { e.preventDefault(); dz.classList.add("over"); });
dz.addEventListener("dragleave", () => dz.classList.remove("over"));
dz.addEventListener("drop", (e) => {
  e.preventDefault();
  dz.classList.remove("over");
  const file = e.dataTransfer.files[0];
  if (file) useSample(file, file.name, "");
});

// ---------- sample + clone ----------

async function useSample(blob, label, transcript) {
  let wav;
  try {
    wav = await toWav(blob);
  } catch {
    toast("Couldn't read that audio. Try a WAV or MP3 file.");
    return;
  }
  if (wav.duration < 3) {
    toast("That sample is shorter than 3 seconds. Please record a little longer.");
    return;
  }
  state.sampleBlob = wav.blob;
  state.voiceId = null;
  $("sample-audio").src = URL.createObjectURL(wav.blob);
  $("sample-label").textContent = `${label} · ${Math.min(wav.duration, MAX_SECONDS).toFixed(1)}s`;
  $("transcript").value = transcript;
  $("sample-preview").classList.remove("hidden");
  $("voice-ready").classList.add("hidden");
  $("output").classList.add("hidden");
  if (wav.trimmed) toast(`Using the first ${MAX_SECONDS} seconds. 10 to 20 seconds of clear speech works best.`);
  refreshButtons();
}

$("sample-clear").addEventListener("click", () => {
  state.sampleBlob = null;
  $("sample-preview").classList.add("hidden");
  refreshButtons();
});

$("clone-btn").addEventListener("click", async () => {
  const btn = $("clone-btn");
  state.busy = true;
  setBusy(btn, true, "Cloning voice…");
  try {
    const form = new FormData();
    form.append("audio", state.sampleBlob, "sample.wav");
    form.append("transcript", $("transcript").value.trim());
    form.append("name", $("voice-name").value.trim());
    const res = await fetch("/api/clone", { method: "POST", body: form });
    if (!res.ok) throw new Error(await apiError(res));
    const voice = await res.json();
    $("transcript").value = voice.transcript;
    $("voice-name").value = "";
    selectVoice(voice);
    loadVoices();
    $("step-speak").scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (err) {
    toast(err.message);
  } finally {
    state.busy = false;
    setBusy(btn, false, "Clone voice");
    refreshButtons();
  }
});

// ---------- saved voices ----------

function selectVoice(voice) {
  state.voiceId = voice.id;
  $("voice-title").textContent = `Using “${voice.name}”`;
  $("voice-meta").textContent = `“${voice.transcript}”`;
  $("voice-ready").classList.remove("hidden");
  $("output").classList.add("hidden");
  document.querySelectorAll(".saved-item").forEach((el) => {
    el.classList.toggle("active", el.dataset.id === voice.id);
  });
  refreshButtons();
}

async function loadVoices() {
  let list = [];
  try {
    list = await (await fetch("/api/voices")).json();
  } catch {
    return;
  }
  $("saved-count").textContent = list.length ? `(${list.length})` : "";
  $("saved-empty").classList.toggle("hidden", list.length > 0);
  const ul = $("saved-list");
  ul.innerHTML = "";
  for (const voice of list) {
    const li = document.createElement("li");
    li.className = "saved-item" + (voice.id === state.voiceId ? " active" : "");
    li.dataset.id = voice.id;
    li.innerHTML = `<span class="avatar"></span><div class="info"><strong></strong><div class="muted small"></div></div><button class="link">Delete</button>`;
    li.querySelector(".avatar").textContent = (voice.name.trim()[0] || "V").toUpperCase();
    li.querySelector("strong").textContent = voice.name;
    li.querySelector(".info .small").textContent = `${voice.duration}s · “${voice.transcript}”`;
    li.addEventListener("click", () => selectVoice(voice));
    li.querySelector("button").addEventListener("click", async (e) => {
      e.stopPropagation();
      if (!confirm(`Delete “${voice.name}”?`)) return;
      await fetch(`/api/voices/${voice.id}`, { method: "DELETE" });
      if (state.voiceId === voice.id) {
        state.voiceId = null;
        $("voice-ready").classList.add("hidden");
        refreshButtons();
      }
      loadVoices();
    });
    ul.appendChild(li);
  }
}

// ---------- speak ----------

function renderSpeakText() {
  const ta = $("speak-text");
  ta.value = state.texts[state.speakLang];
  ta.lang = state.speakLang;
  updateCount();
}

function estimateSeconds(chars) {
  const speed = Number($("speed").value) || 1;
  return state.estimate.overhead + (state.estimate.per_char * chars) / speed;
}

function formatSeconds(sec) {
  sec = Math.max(0, Math.round(sec));
  return sec < 60 ? `${sec}s` : `${Math.floor(sec / 60)}m ${String(sec % 60).padStart(2, "0")}s`;
}

function updateCount() {
  const chars = $("speak-text").value.trim().length;
  $("char-count").textContent = `${chars} / 2000`;
  if (progress.mode !== "running") progress.idle(chars);
}

async function refreshEstimate() {
  try {
    const s = await (await fetch("/api/status")).json();
    if (s.estimate) state.estimate = s.estimate;
  } catch {}
}

// The model gives no step-by-step progress, so the bar follows the time
// estimate: it fills to 95% at the expected finish, then creeps towards 99%.
const progress = {
  mode: "idle",
  // Before generating: an empty bar with the estimated time for the current text.
  idle(chars) {
    this.mode = "idle";
    $("progress").classList.remove("done");
    $("progress-fill").style.width = "0";
    $("progress-time").textContent = "";
    $("progress-label").textContent = !state.modelReady
      ? "Estimated time shows once the model is ready"
      : chars
        ? `Estimated time: about ${formatSeconds(Math.max(1, estimateSeconds(chars)))}`
        : "Type some text to see the estimated time";
  },
  start(expected) {
    this.mode = "running";
    this.expected = Math.max(1, expected);
    this.t0 = performance.now();
    $("progress").classList.remove("done");
    $("progress-fill").style.width = "0";
    $("progress-label").textContent = "Generating speech…";
    this.tick();
    this.timer = setInterval(() => this.tick(), 250);
  },
  tick() {
    const elapsed = (performance.now() - this.t0) / 1000;
    let pct, left;
    if (elapsed < this.expected) {
      pct = 95 * (elapsed / this.expected);
      left = `about ${formatSeconds(Math.max(1, this.expected - elapsed))} left`;
    } else {
      const over = elapsed - this.expected;
      pct = 95 + 4 * (1 - Math.exp(-over / this.expected));
      left = "almost done…";
      $("progress-label").textContent = "Taking a little longer than usual…";
    }
    $("progress-fill").style.width = `${pct.toFixed(1)}%`;
    $("progress-time").textContent = `${formatSeconds(elapsed)} elapsed · ${left}`;
  },
  finish(ok, seconds) {
    clearInterval(this.timer);
    if (!ok) {
      this.idle($("speak-text").value.trim().length);
      return;
    }
    this.mode = "done"; // stays until the text, language or speed changes
    $("progress").classList.add("done");
    $("progress-fill").style.width = "100%";
    $("progress-label").textContent = "Done";
    $("progress-time").textContent = `took ${formatSeconds(seconds)}`;
  },
};

$("speak-lang").addEventListener("click", (e) => {
  const lang = e.target.closest("[data-lang]")?.dataset.lang;
  if (!lang) return;
  state.speakLang = lang;
  activate($("speak-lang"), "data-lang", lang);
  renderSpeakText();
});

$("speak-text").addEventListener("input", (e) => {
  state.texts[state.speakLang] = e.target.value;
  updateCount();
});

$("speed").addEventListener("input", (e) => {
  $("speed-val").textContent = `${Number(e.target.value).toFixed(2)}×`;
  updateCount();
});

$("speak-btn").addEventListener("click", async () => {
  const btn = $("speak-btn");
  state.busy = true;
  setBusy(btn, true, "Generating…");
  $("output").classList.add("hidden");
  progress.start(estimateSeconds($("speak-text").value.trim().length));
  let ok = false;
  let took = 0;
  try {
    const res = await fetch("/api/speak", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        voice_id: state.voiceId,
        text: $("speak-text").value,
        language: state.speakLang,
        speed: Number($("speed").value),
      }),
    });
    if (!res.ok) throw new Error(await apiError(res));
    took = Number(res.headers.get("X-Generation-Seconds")) || (performance.now() - progress.t0) / 1000;
    ok = true;
    const url = URL.createObjectURL(await res.blob());
    $("output-audio").src = url;
    $("download").href = url;
    $("download").download = `voice-${state.speakLang}.wav`;
    $("output").classList.remove("hidden");
    $("output-audio").play().catch(() => {});
  } catch (err) {
    toast(err.message);
  } finally {
    progress.finish(ok, took);
    state.busy = false;
    setBusy(btn, false, "Generate speech");
    refreshButtons();
    if (ok) refreshEstimate();
  }
});

// ---------- init ----------

renderScript();
renderSpeakText();
refreshButtons();
pollStatus();
loadVoices();
