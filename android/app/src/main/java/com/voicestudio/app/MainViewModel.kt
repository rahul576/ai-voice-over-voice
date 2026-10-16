package com.voicestudio.app

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val LANGUAGES = linkedMapOf("en" to "English", "hi" to "हिन्दी")

val SAMPLE_TEXTS = mapOf(
    "en" to "Hello! This is my cloned voice speaking. Isn't it amazing how natural it sounds? You can edit this text and try anything you like.",
    "hi" to "नमस्ते! यह मेरी क्लोन की हुई आवाज़ है। क्या यह सुनने में बिल्कुल असली नहीं लगती? आप इस टेक्स्ट को बदलकर कुछ भी बोलवा सकते हैं।",
)

/** Something to read aloud while recording, so the sample has clear speech. */
val READ_ALOUD = mapOf(
    "en" to "The quick brown fox jumps over the lazy dog. I love exploring new places and meeting new people every day.",
    "hi" to "नमस्ते, आज मौसम बहुत सुहाना है। मुझे नई जगहों पर घूमना और नए लोगों से मिलना बहुत पसंद है।",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("voice_studio", Context.MODE_PRIVATE)

    // Connection
    var serverUrl by mutableStateOf(prefs.getString("server_url", "") ?: "")
        private set
    var status by mutableStateOf<ServerStatus?>(null)
        private set
    var connectionError by mutableStateOf<String?>(null)
        private set
    var searching by mutableStateOf(false)
        private set

    // Voices
    val voices = mutableStateListOf<Voice>()
    var selectedVoiceId by mutableStateOf<String?>(prefs.getString("voice_id", null))

    // Sample to clone
    var sample by mutableStateOf<Sample?>(null)
        private set
    var sampleLabel by mutableStateOf("")
        private set
    var recording by mutableStateOf(false)
        private set
    var recordSeconds by mutableStateOf(0.0)
        private set
    var recordLevel by mutableStateOf(0f)
        private set
    var cloning by mutableStateOf(false)
        private set
    var voiceName by mutableStateOf("")
    var transcript by mutableStateOf("")

    // Speech
    var language by mutableStateOf(prefs.getString("language", "en") ?: "en")
        private set
    val texts = mutableStateMapOf<String, String>().apply { putAll(SAMPLE_TEXTS) }
    var speed by mutableStateOf(1f)
    var generating by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var progressLabel by mutableStateOf("")
        private set
    var resultFile by mutableStateOf<File?>(null)
        private set
    var resultInfo by mutableStateOf("")
        private set
    var playing by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)

    private var api: Api? = null
    private var recorder: Recorder? = null
    private var player: MediaPlayer? = null
    private var pollJob: Job? = null

    val modelReady get() = status?.state == "ready"
    val text get() = texts[language].orEmpty()

    init {
        if (serverUrl.isNotEmpty()) connect(serverUrl) else findServer()
    }

    fun connect(url: String) {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!Regex(":\\d+$").containsMatchIn(u.substringAfter("://"))) u = "$u:${Api.DEFAULT_PORT}"
        serverUrl = u
        prefs.edit().putString("server_url", u).apply()
        api = Api(u)
        status = null
        connectionError = null
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var loadedVoices = false
            var failures = 0
            while (isActive) {
                try {
                    val s = api!!.status()
                    status = s
                    connectionError = null
                    failures = 0
                    if (!loadedVoices) { refreshVoices(); loadedVoices = true }
                } catch (e: Exception) {
                    status = null
                    connectionError = "Can't reach $u. Is Voice Studio running on your computer and on the same Wi-Fi?"
                    loadedVoices = false
                    // The computer may have a new Wi-Fi address, or the USB cable was unplugged: look again.
                    if (++failures % 15 == 2) findServer()
                }
                delay(if (modelReady) 10_000 else 2_000)
            }
        }
    }

    fun findServer() {
        if (searching) return
        searching = true
        connectionError = null
        viewModelScope.launch {
            val found = runCatching { Api.discover() }.getOrNull()
            searching = false
            if (found != null) connect(found)
            else connectionError = "No Voice Studio server found on this Wi-Fi. Type your computer's address below."
        }
    }

    private suspend fun refreshVoices() {
        val list = api?.voices() ?: return
        voices.clear()
        voices.addAll(list)
        if (voices.none { it.id == selectedVoiceId }) selectVoice(voices.firstOrNull()?.id)
    }

    fun selectVoice(id: String?) {
        selectedVoiceId = id
        prefs.edit().putString("voice_id", id).apply()
    }

    fun deleteVoice(v: Voice) = viewModelScope.launch {
        try {
            api?.deleteVoice(v.id)
            voices.remove(v)
            if (selectedVoiceId == v.id) selectVoice(voices.firstOrNull()?.id)
        } catch (e: Exception) {
            message = e.message
        }
    }

    // ---------- sample ----------

    fun startRecording() {
        if (recording) return
        val r = Recorder().also { recorder = it }
        recording = true
        recordSeconds = 0.0
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching { r.record { secs, level -> recordSeconds = secs; recordLevel = level } }
            }
            recording = false
            recordLevel = 0f
            s.onSuccess { setSample(it, "Recording") }.onFailure { message = "Recording failed: ${it.message}" }
        }
    }

    fun stopRecording() = recorder?.stop()

    fun pickFile(uri: Uri) = viewModelScope.launch {
        val s = withContext(Dispatchers.IO) { runCatching { decodeAudio(getApplication(), uri) } }
        s.onSuccess { setSample(it, "Uploaded file") }
            .onFailure { message = "Could not read this audio file: ${it.message}" }
    }

    private fun setSample(s: Sample, label: String) {
        if (s.seconds < MIN_SAMPLE_SECONDS) {
            message = "That sample is shorter than $MIN_SAMPLE_SECONDS seconds. Please record a little longer."
            return
        }
        sample = s
        sampleLabel = "$label · ${"%.1f".format(s.seconds)}s"
    }

    fun clearSample() { sample = null }

    fun previewSample() {
        val s = sample ?: return
        val f = File(getApplication<Application>().cacheDir, "sample.wav")
        f.writeBytes(s.toWav())
        play(f)
    }

    fun cloneVoice() {
        val s = sample ?: return
        val a = api ?: return
        cloning = true
        viewModelScope.launch {
            try {
                val v = a.clone(s.toWav(), voiceName.trim(), transcript.trim())
                voices.add(0, v)
                selectVoice(v.id)
                sample = null
                voiceName = ""
                transcript = ""
                message = "Voice \"${v.name}\" is ready."
            } catch (e: Exception) {
                message = "Cloning failed: ${e.message}"
            } finally {
                cloning = false
            }
        }
    }

    // ---------- speech ----------

    fun chooseLanguage(code: String) {
        language = code
        prefs.edit().putString("language", code).apply()
    }

    fun setText(t: String) { texts[language] = t }

    fun resetText() { texts[language] = SAMPLE_TEXTS[language].orEmpty() }

    fun estimateSeconds(chars: Int = text.trim().length): Double {
        val s = status ?: return 2.0 + 0.08 * chars
        return s.overhead + s.perChar * chars / speed
    }

    fun generate() {
        val a = api ?: return
        val voice = selectedVoiceId ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        stopPlayback()
        generating = true
        val expected = maxOf(1.0, estimateSeconds(t.length))
        val started = System.currentTimeMillis()
        // Same feel as the web app: fill to 95% at the expected finish, then creep towards 99%.
        val ticker = viewModelScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - started) / 1000.0
                val ratio = elapsed / expected
                progress = if (ratio < 1) (0.95 * ratio).toFloat()
                else (0.95 + 0.04 * (1 - 1 / (1 + (ratio - 1)))).toFloat()
                val left = expected - elapsed
                progressLabel = if (left > 0.5) "About ${formatSeconds(left)} left" else "Almost done…"
                delay(100)
            }
        }
        viewModelScope.launch {
            try {
                val wav = a.speak(voice, t, language, speed)
                val dir = File(getApplication<Application>().cacheDir, "speech").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "voice-studio-${System.currentTimeMillis()}.wav")
                f.writeBytes(wav)
                val took = (System.currentTimeMillis() - started) / 1000.0
                resultFile = f
                resultInfo = "Generated in ${formatSeconds(took)}"
                progress = 1f
                play(f)
                runCatching { status = a.status() } // picks up the refined time estimate
            } catch (e: Exception) {
                message = "Generation failed: ${e.message}"
                progress = 0f
            } finally {
                ticker.cancel()
                progressLabel = ""
                generating = false
            }
        }
    }

    fun togglePlay() {
        val f = resultFile ?: return
        if (playing) stopPlayback() else play(f)
    }

    private fun play(f: File) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { playing = false }
            prepare()
            start()
        }
        playing = true
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playing = false
    }

    override fun onCleared() {
        stopPlayback()
        recorder?.stop()
    }
}

fun formatSeconds(s: Double): String =
    if (s < 60) "${s.toInt().coerceAtLeast(1)}s" else "${(s / 60).toInt()}m ${(s % 60).toInt()}s"
