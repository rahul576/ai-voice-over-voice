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
    "en" to "The quick brown fox jumps over the lazy dog. I love exploring new places and meeting new people every day. " +
        "On weekends I like to cook for my family, read a good book, or take a long walk in the park. " +
        "Sometimes it rains all afternoon, and that is the perfect time for a hot cup of tea and a quiet conversation.",
    "hi" to "नमस्ते, आज मौसम बहुत सुहाना है। मुझे नई जगहों पर घूमना और नए लोगों से मिलना बहुत पसंद है। " +
        "छुट्टी के दिन मैं परिवार के लिए खाना बनाता हूँ, कोई अच्छी किताब पढ़ता हूँ, या पार्क में लंबी सैर करता हूँ। " +
        "कभी-कभी पूरी दोपहर बारिश होती है, और तब गरम चाय के साथ आराम से बातें करने का मज़ा ही कुछ और है।",
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

    // Voices: [voices] are on the server, [localVoices] are samples kept on the phone.
    val voices = mutableStateListOf<Voice>()
    private val store = LocalVoices(app)
    val localVoices = mutableStateListOf<LocalVoice>().apply { addAll(store.all()) }
    var uploadingId by mutableStateOf<String?>(null)
        private set
    private var voicesLoaded = false
    var selectedVoiceId by mutableStateOf<String?>(prefs.getString("voice_id", null))

    // Sample to clone
    var sample by mutableStateOf<Sample?>(null)
        private set
    var sampleLabel by mutableStateOf("")
        private set
    /** Tapped Record but not started yet: shows the text to read and a Start button. */
    var readyToRecord by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    private var cancelled = false
    var recordSeconds by mutableStateOf(0.0)
        private set
    var recordLevel by mutableStateOf(0f)
        private set
    var cloning by mutableStateOf(false)
        private set
    var voiceName by mutableStateOf("")
    /** Language of the passage shown to read aloud while recording. */
    var readLanguage by mutableStateOf(prefs.getString("read_language", null) ?: prefs.getString("language", "en") ?: "en")
        private set
    var denoise by mutableStateOf(true)
    var cleaning by mutableStateOf(false)
        private set
    private var cleanedPreview: File? = null
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
    /** What is playing: [PLAY_RESULT], [PLAY_PREVIEW], or a voice id for its saved sample. */
    var playingId by mutableStateOf<String?>(null)
        private set
    var loadingSampleId by mutableStateOf<String?>(null)
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
                    else if (modelReady && uploadingId == null && localVoices.any { it.serverId == null }) uploadPending()
                } catch (e: Exception) {
                    status = null
                    voicesLoaded = false
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
        voicesLoaded = true
        if (voices.none { it.id == selectedVoiceId }) selectVoice(voices.firstOrNull()?.id)
        uploadPending()
    }

    /** One row per voice in My voices, merging what's on the phone with what's on the server. */
    val myVoices: List<VoiceItem>
        get() {
            val local = localVoices.map { l -> VoiceItem(l.localId, l.name, l.duration, voices.firstOrNull { it.id == l.serverId }, l) }
            val linked = localVoices.mapNotNull { it.serverId }.toSet()
            val remote = voices.filter { it.id !in linked }.map { VoiceItem(it.id, it.name, it.duration, it, null) }
            return local + remote
        }

    /** Sends samples that were saved while the server was unreachable. */
    private fun uploadPending() = viewModelScope.launch {
        for (l in localVoices.filter { it.serverId == null }) {
            if (!modelReady) break
            upload(l, quiet = true)
        }
    }

    fun upload(item: VoiceItem) {
        item.local?.let { viewModelScope.launch { upload(it, quiet = false) } }
    }

    private suspend fun upload(l: LocalVoice, quiet: Boolean) {
        val a = api ?: return
        if (uploadingId != null) return
        uploadingId = l.localId
        try {
            val wav = withContext(Dispatchers.IO) { store.wavFile(l.localId).readBytes() }
            val v = a.clone(wav, l.name, l.transcript, l.denoise)
            val updated = l.copy(serverId = v.id)
            withContext(Dispatchers.IO) { store.save(updated) }
            localVoices[localVoices.indexOfFirst { it.localId == l.localId }] = updated
            voices.add(0, v)
            if (selectedVoiceId == null) selectVoice(v.id)
            if (!quiet) message = "Uploaded \"${l.name}\". It's ready in the Generate tab."
        } catch (e: Exception) {
            if (!quiet) message = "Upload failed: ${e.message}"
        } finally {
            uploadingId = null
        }
    }

    fun selectVoice(id: String?) {
        selectedVoiceId = id
        prefs.edit().putString("voice_id", id).apply()
    }

    fun deleteVoice(item: VoiceItem) = viewModelScope.launch {
        try {
            item.server?.let { api?.deleteVoice(it.id) ?: throw ApiException("Not connected to the server") }
            if (playingId == item.key) stopPlayback()
            item.local?.let { store.delete(it); localVoices.remove(it) }
            item.server?.let { voices.remove(it) }
            if (voices.none { it.id == selectedVoiceId }) selectVoice(voices.firstOrNull()?.id)
        } catch (e: Exception) {
            message = "Could not delete: ${e.message}"
        }
    }

    // ---------- sample ----------

    fun prepareRecording() { readyToRecord = true }

    fun chooseReadLanguage(code: String) {
        readLanguage = code
        prefs.edit().putString("read_language", code).apply()
    }

    fun startRecording() {
        if (recording) return
        val r = Recorder().also { recorder = it }
        readyToRecord = false
        recording = true
        cancelled = false
        recordSeconds = 0.0
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching { r.record { secs, level -> recordSeconds = secs; recordLevel = level } }
            }
            recording = false
            recordLevel = 0f
            if (cancelled) return@launch
            s.onSuccess { setSample(it, "Recording") }.onFailure { message = "Recording failed: ${it.message}" }
        }
    }

    fun finishRecording() { recorder?.stop() }

    /** Leaves the ready screen, or stops a recording and throws it away. */
    fun cancelRecording() {
        readyToRecord = false
        cancelled = true
        recorder?.stop()
    }

    val selectedVoice get() = voices.firstOrNull { it.id == selectedVoiceId }

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
        cleanedPreview = null
        sampleLabel = "$label · ${"%.1f".format(s.seconds)}s"
        voiceName = randomVoiceName()
    }

    fun clearSample() {
        if (playingId == PLAY_PREVIEW) stopPlayback()
        sample = null
    }

    fun previewSample() {
        val s = sample ?: return
        val f = File(getApplication<Application>().cacheDir, "sample.wav")
        f.writeBytes(s.toWav())
        if (playingId == PLAY_PREVIEW) stopPlayback() else play(f, PLAY_PREVIEW)
    }

    /** Plays the sample with background noise removed, cleaned by the server. */
    fun previewCleaned() {
        if (playingId == PLAY_CLEANED) { stopPlayback(); return }
        val s = sample ?: return
        val a = api ?: return
        cleanedPreview?.let { play(it, PLAY_CLEANED); return }
        cleaning = true
        viewModelScope.launch {
            try {
                val f = File(getApplication<Application>().cacheDir, "sample-cleaned.wav")
                f.writeBytes(a.clean(withContext(Dispatchers.Default) { s.toWav() }))
                if (sample === s) { cleanedPreview = f; play(f, PLAY_CLEANED) }
            } catch (e: Exception) {
                message = "Could not clean the sample: ${e.message}"
            } finally {
                cleaning = false
            }
        }
    }

    fun toggleVoiceSample(item: VoiceItem) {
        if (playingId == item.key) { stopPlayback(); return }
        // The server's copy is the cleaned one; the phone's copy is the raw recording, used offline.
        val local = item.local?.let { store.wavFile(it.localId) }
        if (local != null && item.server?.hasSample != true) { play(local, item.key); return }
        val v = item.server ?: return
        val a = api ?: return
        val f = File(getApplication<Application>().cacheDir, "voice-${v.id}.wav")
        viewModelScope.launch {
            try {
                if (!f.isFile) {
                    loadingSampleId = item.key
                    f.writeBytes(a.sample(v.id))
                }
                play(f, item.key)
            } catch (e: Exception) {
                message = "Could not play this sample: ${e.message}"
            } finally {
                loadingSampleId = null
            }
        }
    }

    /** Saves the sample on the phone first, then uploads it if the server is reachable. */
    fun saveVoice() {
        val s = sample ?: return
        cloning = true
        viewModelScope.launch {
            val name = voiceName.trim().ifEmpty { randomVoiceName() }
            val l = withContext(Dispatchers.IO) { store.add(s, name, transcript.trim(), denoise) }
            localVoices.add(0, l)
            if (playingId == PLAY_PREVIEW) stopPlayback()
            sample = null
            voiceName = ""
            transcript = ""
            if (modelReady && voicesLoaded) {
                upload(l, quiet = true)
                val saved = localVoices.first { it.localId == l.localId }
                if (saved.serverId != null) {
                    selectVoice(saved.serverId)
                    message = "Saved \"$name\". It's ready in the Generate tab."
                } else {
                    message = "Saved \"$name\" on your phone. It will upload when the server is back."
                }
            } else {
                message = "Saved \"$name\" on your phone. It will upload when the server is back."
            }
            cloning = false
        }
    }

    // ---------- speech ----------

    fun chooseLanguage(code: String) {
        language = code
        prefs.edit().putString("language", code).apply()
    }

    fun setText(t: String) { texts[language] = t }

    /** Dictated text replaces the untouched sample text, otherwise it's added to the end. */
    fun addDictatedText(spoken: String) {
        val current = text.trim()
        texts[language] = if (current.isEmpty() || current == SAMPLE_TEXTS[language]) spoken else "$current $spoken"
    }

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
                play(f, PLAY_RESULT)
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
        if (playingId == PLAY_RESULT) stopPlayback() else play(f, PLAY_RESULT)
    }

    private fun play(f: File, id: String) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { playingId = null }
            prepare()
            start()
        }
        playingId = id
    }

    fun stopPlayback() {
        player?.release()
        player = null
        playingId = null
    }

    override fun onCleared() {
        stopPlayback()
        recorder?.stop()
    }
}

/** A row in My voices: kept on the phone ([local]), on the server ([server]), or both. */
data class VoiceItem(val key: String, val name: String, val duration: Double, val server: Voice?, val local: LocalVoice?) {
    val canPlay get() = local != null || server?.hasSample == true
}

const val PLAY_RESULT = "result"
const val PLAY_PREVIEW = "preview"
const val PLAY_CLEANED = "cleaned"

fun formatSeconds(s: Double): String =
    if (s < 60) "${s.toInt().coerceAtLeast(1)}s" else "${(s / 60).toInt()}m ${(s % 60).toInt()}s"
