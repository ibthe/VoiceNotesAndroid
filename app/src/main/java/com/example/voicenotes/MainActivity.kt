package com.example.voicenotes

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.example.voicenotes.data.Note
import com.example.voicenotes.data.NotesDatabase
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.launch

private enum class VoiceMode(val label: String) {
    AUTO("Авто: русский / English"), MIXED("Смешанная речь RU + EN"), MANUAL_RU("Русский (RU)"), MANUAL_EN("English (EN)")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF2864DC), secondary = androidx.compose.ui.graphics.Color(0xFF4D7DE3))) { NotesApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesApp() {
    val context = LocalContext.current
    val db = remember { NotesDatabase.get(context) }
    val dao = remember { db.noteDao() }
    val scope = rememberCoroutineScope()
    val notes by dao.observeAll().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VoiceMode.AUTO) }
    var status by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) status = "Разрешение получено. Нажмите микрофон ещё раз."
        else status = "Для диктовки нужно разрешение на микрофон."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (settings) "Настройки" else if (editing != null || creating) "Заметка" else "Заметки", fontWeight = FontWeight.Bold) },
                navigationIcon = { if (settings || editing != null || creating) IconButton(onClick = { settings = false; editing = null; creating = false; status = "" }) { Icon(Icons.Default.ArrowBack, "Назад") } },
                actions = { if (!settings && editing == null && !creating) TextButton(onClick = { settings = true }) { Text("Настройки") } }
            )
        },
        floatingActionButton = { if (!settings && editing == null && !creating) FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Новая заметка") } }
    ) { padding ->
        when {
            settings -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Голосовой ввод", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Режим распознавания", style = MaterialTheme.typography.titleMedium)
                VoiceMode.values().forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { mode = item }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == item, onClick = { mode = item })
                        Spacer(Modifier.width(8.dp)); Text(item.label)
                    }
                }
                Text("Офлайн-ограничение", style = MaterialTheme.typography.titleMedium)
                Text("Распознавание выполняется локально движком sherpa-onnx с мультиязычной Whisper tiny. Модель включается в APK; интернет во время диктовки не нужен.", style = MaterialTheme.typography.bodyMedium)
                Text("Заметки хранятся локально в базе данных приложения.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            editing != null || creating -> {
                val current = editing
                NoteEditor(
                    Modifier.padding(padding), initialTitle = current?.title.orEmpty(), initialBody = current?.body.orEmpty(),
                    mode = mode, status = status,
                    onStatus = { status = it },
                    onVoice = { receiveText ->
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            startOfflineRecognition(context, mode, { status = it }, receiveText)
                        }
                    },
                    onSave = { title, body ->
                        scope.launch {
                            if (current == null) dao.insert(Note(title = title.ifBlank { "Без названия" }, body = body))
                            else dao.update(current.copy(title = title.ifBlank { "Без названия" }, body = body, updatedAt = System.currentTimeMillis()))
                            editing = null; creating = false; status = ""
                        }
                    },
                    onDelete = if (current != null) ({ scope.launch { dao.delete(current); editing = null; status = "" } }) else null
                )
            }
            else -> Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Поиск заметок…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
                Spacer(Modifier.height(12.dp))
                if (notes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Пока нет заметок", style = MaterialTheme.typography.titleLarge); Text("Нажмите +, чтобы создать первую", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                    items(notes.filter { it.title.contains(query, true) || it.body.contains(query, true) }, key = { it.id }) { note ->
                        ElevatedCard(Modifier.fillMaxWidth().clickable { editing = note }, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(note.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); if (note.favorite) Icon(Icons.Default.Star, "Избранное", tint = MaterialTheme.colorScheme.primary) }
                                Text(note.body.ifBlank { "Нет текста" }, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(note.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteEditor(
    modifier: Modifier, initialTitle: String, initialBody: String, mode: VoiceMode, status: String,
    onStatus: (String) -> Unit, onVoice: ( (String) -> Unit ) -> Unit, onSave: (String, String) -> Unit, onDelete: (() -> Unit)?
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Название") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(value = body, onValueChange = { body = it }, modifier = Modifier.weight(1f).fillMaxWidth(), placeholder = { Text("Запишите свою мысль…") }, shape = RoundedCornerShape(16.dp))
        Text("Режим: ${mode.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onVoice { recognized -> body = if (body.isBlank()) recognized else body.trimEnd() + "\n" + recognized } }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Диктовать") }
            Button(onClick = { onSave(title, body) }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
        }
    }
}

private fun startOfflineRecognition(
    context: android.content.Context,
    mode: VoiceMode,
    onStatus: (String) -> Unit,
    onResult: (String) -> Unit
) {
    // Whisper tiny is bundled into app/src/main/assets/models by GitHub Actions.
    // Audio is captured and decoded locally; this code makes no network requests.
    Thread {
        var recorder: AudioRecord? = null
        var recognizer: OfflineRecognizer? = null
        var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
        try {
            val sampleRate = 16_000
            val minBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBytes > 0) { "Не удалось настроить микрофон (код $minBytes)." }
            val bufferBytes = maxOf(minBytes, sampleRate * 2 / 2)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон." }

            val whisperLanguage = when (mode) {
                VoiceMode.MANUAL_RU -> "ru"
                VoiceMode.MANUAL_EN -> "en"
                VoiceMode.AUTO, VoiceMode.MIXED -> ""
            }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "models/tiny-encoder.int8.onnx",
                        decoder = "models/tiny-decoder.int8.onnx",
                        language = whisperLanguage,
                        task = "transcribe"
                    ),
                    tokens = "models/tiny-tokens.txt",
                    modelType = "whisper",
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            recognizer = OfflineRecognizer(context.assets, config)
            stream = recognizer.createStream()

            android.os.Handler(context.mainLooper).post { onStatus("Слушаю… говорите до 15 секунд.") }
            recorder.startRecording()
            val pcm = ShortArray(sampleRate / 2)
            val floats = FloatArray(sampleRate / 2)
            val allSamples = ArrayList<Float>(sampleRate * 15)
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < 15_000) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                for (i in 0 until count) allSamples.add(pcm[i] / 32768.0f)
            }
            recorder.stop()
            check(allSamples.isNotEmpty()) { "Не получены аудиоданные." }
            val samples = FloatArray(allSamples.size) { allSamples[it] }
            stream.acceptWaveform(samples, sampleRate)
            android.os.Handler(context.mainLooper).post { onStatus("Распознаю речь на устройстве…") }
            recognizer.decode(stream)
            val result = recognizer.getResult(stream).text.trim()
            android.os.Handler(context.mainLooper).post {
                if (result.isBlank()) onStatus("Речь не распознана. Попробуйте ещё раз.")
                else {
                    onResult(result)
                    onStatus("Готово. Аудио обработано локально.")
                }
            }
        } catch (e: Exception) {
            android.os.Handler(context.mainLooper).post {
                onStatus("Ошибка офлайн-распознавания: ${e.message ?: "неизвестная ошибка"}")
            }
        } finally {
            try { if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) {}
            recorder?.release()
            stream?.release()
            recognizer?.release()
        }
    }.start()
}
