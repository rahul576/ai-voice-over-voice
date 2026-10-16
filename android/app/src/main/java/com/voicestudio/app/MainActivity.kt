package com.voicestudio.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFFA99DFF))
            else lightColorScheme(primary = Color(0xFF5B4BDB))
            MaterialTheme(colorScheme = scheme) { VoiceStudioScreen(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceStudioScreen(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Studio", fontWeight = FontWeight.SemiBold) },
                actions = {
                    StatusPill(vm)
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Server settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Record voice") },
                    icon = { Icon(Icons.Default.Mic, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Generate") },
                    icon = { Icon(Icons.Default.GraphicEq, null) })
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (vm.status == null) ConnectCard(vm)
                if (tab == 0) {
                    RecordCard(vm)
                    MyVoicesCard(vm)
                } else {
                    SpeakCard(vm)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

@Composable
fun StatusPill(vm: MainViewModel) {
    val (label, color) = when {
        vm.status == null -> (if (vm.searching) "Searching…" else "Offline") to MaterialTheme.colorScheme.outline
        vm.status?.state == "ready" -> "Ready · ${vm.status?.device ?: ""}".trimEnd(' ', '·') to Color(0xFF2E9E5B)
        vm.status?.state == "error" -> "Model failed" to MaterialTheme.colorScheme.error
        else -> "Loading model…" to Color(0xFFD08A1E)
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun ConnectCard(vm: MainViewModel) {
    var url by remember(vm.serverUrl) { mutableStateOf(vm.serverUrl) }
    Section("Connect to your computer") {
        Text(
            "Voice Studio runs the voice model on your computer. Start it there with " +
                "./start.sh, keep the phone on the same Wi-Fi, and connect below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        vm.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        ServerField(url, { url = it }) { vm.connect(url) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connect(url) }, enabled = url.isNotBlank()) { Text("Connect") }
            OutlinedButton(onClick = vm::findServer, enabled = !vm.searching) {
                if (vm.searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.WifiFind, null)
                Spacer(Modifier.width(6.dp))
                Text("Find on Wi-Fi")
            }
        }
    }
}

@Composable
fun ServerField(url: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    OutlinedTextField(
        value = url, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Server address") }, placeholder = { Text("192.168.1.20:7860") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onDone() }),
    )
}

@Composable
fun SettingsDialog(vm: MainViewModel, onClose: () -> Unit) {
    var url by remember { mutableStateOf(vm.serverUrl) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField(url, { url = it }) { vm.connect(url); onClose() }
                vm.status?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { vm.findServer(); onClose() }) { Text("Find on Wi-Fi instead") }
            }
        },
        confirmButton = { TextButton(onClick = { vm.connect(url); onClose() }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
fun RecordCard(vm: MainViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::pickFile)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording() else vm.message = "Voice Studio needs the microphone to record a sample."
    }
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            vm.startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Section("Record a new voice") {
        if (vm.readyToRecord) {
            Text(
                if (vm.readLanguage == "hi") "शांत जगह चुनें। Start दबाकर इसे अपनी सामान्य आवाज़ में पढ़ें:"
                else "Find a quiet spot. When you tap Start, read this aloud in your normal voice:",
                style = MaterialTheme.typography.bodyMedium,
            )
            ReadAloudPassage(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::start) { Icon(Icons.Default.FiberManualRecord, null); Spacer(Modifier.width(6.dp)); Text("Start") }
                OutlinedButton(onClick = vm::cancelRecording) { Text("Cancel") }
            }
        } else if (vm.recording) {
            Text(if (vm.readLanguage == "hi") "इसे ज़ोर से पढ़ें:" else "Read this aloud:", style = MaterialTheme.typography.labelLarge)
            ReadAloudPassage(vm)
            LinearProgressIndicator(
                progress = { vm.recordLevel.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Recording ${"%.0f".format(vm.recordSeconds)}s / ${MAX_SAMPLE_SECONDS}s · 10 to 20 seconds of clear speech works best",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::finishRecording,
                    enabled = vm.recordSeconds >= MIN_SAMPLE_SECONDS,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Done") }
                OutlinedButton(onClick = vm::cancelRecording) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("Cancel")
                }
            }
        } else if (vm.sample == null) {
            Text(
                "Record yourself for 10 to 20 seconds (up to a minute), or pick an existing voice sample. " +
                    "Background noise is cleaned up before cloning.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::prepareRecording) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Record") }
                OutlinedButton(onClick = { picker.launch("audio/*") }) {
                    Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Pick a file")
                }
            }
        } else {
            Text("Listen back, give it a name, then save it.", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = vm::previewSample) {
                    Icon(if (vm.playingId == PLAY_PREVIEW) Icons.Default.Stop else Icons.Default.PlayArrow, "Play sample")
                }
                Text(vm.sampleLabel, Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = vm::clearSample, enabled = !vm.cloning) { Text("Discard") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remove background noise")
                    Text("Cleans fans, traffic and room hum", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vm.denoise, onCheckedChange = { vm.denoise = it })
            }
            if (vm.denoise) {
                OutlinedButton(onClick = vm::previewCleaned, enabled = vm.status != null && !vm.cleaning) {
                    when {
                        vm.cleaning -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        vm.playingId == PLAY_CLEANED -> Icon(Icons.Default.Stop, null)
                        else -> Icon(Icons.Default.AutoFixHigh, null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (vm.status == null) "Hear cleaned version (needs the server)" else "Hear cleaned version")
                }
            }
            OutlinedTextField(
                vm.voiceName, { vm.voiceName = it }, Modifier.fillMaxWidth(),
                label = { Text("Voice name") }, singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { vm.voiceName = randomVoiceName() }) { Icon(Icons.Default.Casino, "Pick another name") }
                },
            )
            OutlinedTextField(
                vm.transcript, { vm.transcript = it }, Modifier.fillMaxWidth(),
                label = { Text("What the sample says (optional)") },
                supportingText = { Text("Leave empty to transcribe it automatically.") },
            )
            Button(onClick = vm::saveVoice, enabled = !vm.cloning, modifier = Modifier.fillMaxWidth()) {
                if (vm.cloning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp)); Text("Saving…")
                } else {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save this voice")
                }
            }
        }
    }
}

/** The text to read while recording, in English or Hindi, so anyone can record a sample. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudPassage(vm: MainViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        LANGUAGES.entries.forEachIndexed { i, (code, label) ->
            SegmentedButton(
                selected = vm.readLanguage == code, onClick = { vm.chooseReadLanguage(code) },
                shape = SegmentedButtonDefaults.itemShape(i, LANGUAGES.size),
            ) { Text(label) }
        }
    }
    Text(READ_ALOUD[vm.readLanguage].orEmpty(), style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun MyVoicesCard(vm: MainViewModel) {
    Section("My voices") {
        val items = vm.myVoices
        if (items.isEmpty()) {
            Text("No saved voices yet. Record one above.", style = MaterialTheme.typography.bodyMedium)
        }
        items.forEachIndexed { i, v ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.bodyLarge)
                    val where = when {
                        v.local != null && vm.uploadingId == v.local.localId -> "Uploading…"
                        v.local != null && v.local.serverId == null -> "On this phone · not uploaded yet"
                        v.local == null && v.server?.hasSample != true -> "Sample not kept"
                        else -> "Ready"
                    }
                    Text("${"%.1f".format(v.duration)}s · $where", style = MaterialTheme.typography.bodySmall)
                }
                if (v.local != null && v.local.serverId == null && vm.uploadingId == null && vm.modelReady) {
                    IconButton(onClick = { vm.upload(v) }) { Icon(Icons.Default.CloudUpload, "Upload ${v.name}") }
                }
                if (v.canPlay) {
                    IconButton(onClick = { vm.toggleVoiceSample(v) }) {
                        when {
                            vm.loadingSampleId == v.key -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            vm.playingId == v.key -> Icon(Icons.Default.Stop, "Stop ${v.name}")
                            else -> Icon(Icons.Default.PlayArrow, "Play ${v.name}")
                        }
                    }
                }
                var confirm by remember { mutableStateOf(false) }
                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete ${v.name}") }
                if (confirm) AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Delete \"${v.name}\"?") },
                    text = { Text("This removes it from your phone and the server.") },
                    confirmButton = { TextButton(onClick = { confirm = false; vm.deleteVoice(v) }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePicker(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && vm.voices.isNotEmpty() }) {
        OutlinedTextField(
            value = vm.selectedVoice?.name ?: if (vm.voices.isEmpty()) "No voices yet" else "Choose a voice",
            onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("Voice") },
            leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.voices.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.name) },
                    trailingIcon = { Text("${"%.1f".format(v.duration)}s", style = MaterialTheme.typography.bodySmall) },
                    onClick = { vm.selectVoice(v.id); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakCard(vm: MainViewModel) {
    val context = LocalContext.current
    // Speech to text with the phone's recogniser (Google's on most phones), so long texts needn't be typed.
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::addDictatedText)
    }
    fun dictate() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (vm.language == "hi") "hi-IN" else "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (vm.language == "hi") "बोलिए…" else "Speak now…")
        try {
            dictation.launch(intent)
        } catch (_: ActivityNotFoundException) {
            vm.message = "Speech to text isn't available on this phone. Install or update the Google app."
        }
    }
    Section("Generate speech") {
        VoicePicker(vm)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LANGUAGES.entries.forEachIndexed { i, (code, label) ->
                SegmentedButton(
                    selected = vm.language == code, onClick = { vm.chooseLanguage(code) },
                    shape = SegmentedButtonDefaults.itemShape(i, LANGUAGES.size),
                ) { Text(label) }
            }
        }
        OutlinedTextField(
            vm.text, vm::setText, Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text("Text to speak") },
            supportingText = { Text("${vm.text.length} / 2000") },
            trailingIcon = {
                Row {
                    IconButton(onClick = ::dictate, enabled = !vm.generating) { Icon(Icons.Default.Mic, "Speak to type") }
                    IconButton(onClick = vm::resetText) { Icon(Icons.Default.Refresh, "Reset to sample text") }
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed ${"%.1f".format(vm.speed)}×", Modifier.width(88.dp))
            Slider(vm.speed, { vm.speed = (it * 10).toInt() / 10f }, valueRange = 0.5f..2f, modifier = Modifier.weight(1f))
        }

        val chars = vm.text.trim().length
        LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
        Text(
            when {
                vm.generating -> vm.progressLabel
                chars == 0 -> "Type some text to see the estimated time"
                else -> "Estimated time: about ${formatSeconds(maxOf(1.0, vm.estimateSeconds(chars)))}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val canGenerate = vm.modelReady && vm.selectedVoiceId != null && chars > 0 && !vm.generating
        Button(onClick = vm::generate, enabled = canGenerate, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.GraphicEq, null); Spacer(Modifier.width(6.dp))
            Text(if (vm.generating) "Generating…" else "Generate speech")
        }
        if (vm.selectedVoiceId == null && vm.status != null) {
            Text("Record a voice in the Record voice tab first.", style = MaterialTheme.typography.bodySmall)
        }

        vm.resultFile?.let { file ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = vm::togglePlay) {
                    Icon(if (vm.playingId == PLAY_RESULT) Icons.Default.Stop else Icons.Default.PlayArrow, "Play result")
                }
                Text(vm.resultInfo, Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Share speech",
                        ),
                    )
                }) { Icon(Icons.Default.Share, "Share") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    IconButton(onClick = {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                        }
                        val r = context.contentResolver
                        val out = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        out?.let { r.openOutputStream(it)?.use { s -> s.write(file.readBytes()) } }
                        vm.message = if (out != null) "Saved ${file.name} to Downloads" else "Could not save the file"
                    }) { Icon(Icons.Default.Download, "Save to Downloads") }
                }
            }
        }
    }
}
