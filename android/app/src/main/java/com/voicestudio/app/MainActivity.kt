package com.voicestudio.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (vm.status == null) ConnectCard(vm)
            CloneCard(vm)
            SpeakCard(vm)
            Spacer(Modifier.height(24.dp))
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
fun CloneCard(vm: MainViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::pickFile)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording() else vm.message = "Voice Studio needs the microphone to record a sample."
    }
    fun record() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            vm.startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Section("1. Clone a voice") {
        if (vm.recording) {
            Text("Read this aloud:", style = MaterialTheme.typography.labelLarge)
            Text(READ_ALOUD[vm.language].orEmpty(), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(
                progress = { vm.recordLevel.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Recording ${"%.1f".format(vm.recordSeconds)}s / ${MAX_SAMPLE_SECONDS}s · 3 to 10 seconds works best",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { vm.stopRecording() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
        } else if (vm.sample == null) {
            Text("Record yourself for 3 to 10 seconds, or pick an existing voice sample.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::record) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Record") }
                OutlinedButton(onClick = { picker.launch("audio/*") }) {
                    Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Pick a file")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previewSample) { Icon(Icons.Default.PlayArrow, "Play sample") }
                Text(vm.sampleLabel, Modifier.weight(1f))
                IconButton(onClick = vm::clearSample) { Icon(Icons.Default.Close, "Discard sample") }
            }
            OutlinedTextField(
                vm.voiceName, { vm.voiceName = it }, Modifier.fillMaxWidth(),
                label = { Text("Voice name (optional)") }, singleLine = true,
            )
            OutlinedTextField(
                vm.transcript, { vm.transcript = it }, Modifier.fillMaxWidth(),
                label = { Text("What the sample says (optional)") },
                supportingText = { Text("Leave empty to transcribe it automatically.") },
            )
            Button(onClick = vm::cloneVoice, enabled = vm.modelReady && !vm.cloning, modifier = Modifier.fillMaxWidth()) {
                if (vm.cloning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp)); Text("Cloning…")
                } else Text("Clone this voice")
            }
        }

        if (vm.voices.isNotEmpty()) {
            HorizontalDivider()
            Text("My voices", style = MaterialTheme.typography.labelLarge)
            vm.voices.forEach { v ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { vm.selectVoice(v.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.selectedVoiceId == v.id, onClick = { vm.selectVoice(v.id) })
                    Column(Modifier.weight(1f)) {
                        Text(v.name)
                        Text("${"%.1f".format(v.duration)}s sample", style = MaterialTheme.typography.bodySmall)
                    }
                    var confirm by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete ${v.name}") }
                    if (confirm) AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Delete \"${v.name}\"?") },
                        confirmButton = { TextButton(onClick = { confirm = false; vm.deleteVoice(v) }) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakCard(vm: MainViewModel) {
    val context = LocalContext.current
    Section("2. Make it speak") {
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
                IconButton(onClick = vm::resetText) { Icon(Icons.Default.Refresh, "Reset to sample text") }
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
            Text("Clone a voice first.", style = MaterialTheme.typography.bodySmall)
        }

        vm.resultFile?.let { file ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = vm::togglePlay) {
                    Icon(if (vm.playing) Icons.Default.Stop else Icons.Default.PlayArrow, "Play result")
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
