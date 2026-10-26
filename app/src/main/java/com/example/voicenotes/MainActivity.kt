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
import kotlin.math.sqrt

private enum class VoiceMode(val label: String) {
    AUTO("Авто: русский / English"), MIXED("RU + EN (экспериментально)"), MANUAL_RU("Русский (RU)"), MANUAL_EN("English (EN)")
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
    var pendingVoiceResult by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val receiver = pendingVoiceResult
            pendingVoiceResult = null
            if (receiver != null) {
                status = "Разрешение получено. Запускаю диктовку…"
                startOfflineRecognition(context, mode, { message -> status = message }, receiver)
            } else {
                status = "Разрешение на микрофон получено."
            }
        } else {
            pendingVoiceResult = null
            status = "Для диктовки нужно разрешение на микрофон. Разрешите доступ в настройках Android."
        }
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
                Text("Для русской речи с английскими терминами сначала попробуйте режим RU. Автоопределение языка не гарантирует точное распознавание каждого переключения между русским и английским.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VoiceMode.values().forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { mode = item }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == item, onClick = { mode = item })
                        Spacer(Modifier.width(8.dp)); Text(item.label)
                    }
                }
                Text("Офлайн-ограничение", style = MaterialTheme.typography.titleMedium)
                Text("Распознавание выполняется локально движком sherpa-onnx с мультиязычной Whisper base INT8. Эта модель больше и обычно точнее tiny, но может работать медленнее на слабых телефонах. Модель включается в APK; интернет во время диктовки не нужен.", style = MaterialTheme.typography.bodyMedium)
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
                            pendingVoiceResult = receiveText
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
    // Multilingual Whisper base INT8 is bundled into app/src/main/assets/models by GitHub Actions.
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
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
            // Assets may come from either the newer INT8 workflow or an earlier workflow
            // that packaged the full-precision decoder. Accept both filenames to avoid
            // a runtime failure when the workflow and app source were updated separately.
            val availableAssets = context.assets.list("models")?.toSet().orEmpty()
            val requiredAssets = setOf("base-encoder.int8.onnx", "base-tokens.txt")
            val missingAssets = requiredAssets - availableAssets
            check(missingAssets.isEmpty()) {
                val listed = if (availableAssets.isEmpty()) "папка assets/models пуста или отсутствует" else availableAssets.sorted().joinToString()
                "В APK не найдены файлы модели: ${missingAssets.joinToString()}. Обнаружены: $listed. Пересоберите APK последней версией GitHub Actions."
            }
            val decoderFile = when {
                "base-decoder.int8.onnx" in availableAssets -> "base-decoder.int8.onnx"
                "base-decoder.onnx" in availableAssets -> "base-decoder.onnx"
                else -> null
            }
            check(decoderFile != null) {
                "В APK не найден декодер Whisper (base-decoder.int8.onnx или base-decoder.onnx). Обнаружены: ${availableAssets.sorted().joinToString()}. Пересоберите APK последней версией GitHub Actions."
            }

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "models/base-encoder.int8.onnx",
                        decoder = "models/$decoderFile",
                        language = whisperLanguage,
                        task = "transcribe"
                    ),
                    tokens = "models/base-tokens.txt",
                    modelType = "whisper",
                    numThreads = 4,
                    provider = "cpu"
                )
            )

            android.os.Handler(context.mainLooper).post { onStatus("Загружаю локальную модель…") }
            recognizer = OfflineRecognizer(context.assets, config)
            stream = recognizer.createStream()

            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Микрофон не начал запись. Проверьте разрешение на микрофон в настройках Android."
            }
            val pcm = ShortArray(sampleRate / 2) // 0.5-second chunks at 16 kHz
            val allSamples = ArrayList<Float>(sampleRate * 10)
            val startedAt = System.currentTimeMillis()
            var lastStatusAt = 0L
            var speechDetected = false
            var silenceMs = 0L
            val maxDurationMs = 45_000L
            val silenceToStopMs = 2_200L
            // A lower threshold helps avoid cutting off quieter speech on phone microphones.
            val speechThreshold = 0.006

            android.os.Handler(context.mainLooper).post { onStatus("Слушаю… говорите. Запись завершится после паузы или через 45 секунд.") }
            while (System.currentTimeMillis() - startedAt < maxDurationMs) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    throw IllegalStateException("Ошибка чтения микрофона (код $count). Попробуйте перезапустить диктовку.")
                }
                if (count == 0) continue

                var energy = 0.0
                for (i in 0 until count) {
                    val normalized = pcm[i] / 32768.0f
                    allSamples.add(normalized)
                    energy += normalized * normalized
                }
                val rms = sqrt(energy / count)
                val chunkDurationMs = count * 1000L / sampleRate
                if (rms >= speechThreshold) {
                    speechDetected = true
                    silenceMs = 0L
                } else if (speechDetected) {
                    silenceMs += chunkDurationMs
                }

                val elapsedMs = System.currentTimeMillis() - startedAt
                if (elapsedMs - lastStatusAt >= 1_000L) {
                    val remainingSeconds = ((maxDurationMs - elapsedMs).coerceAtLeast(0L) / 1000L)
                    val message = if (speechDetected) {
                        "Запись… осталось максимум ${remainingSeconds} с. После паузы начнётся распознавание."
                    } else {
                        "Слушаю… скажите фразу (максимум 45 секунд)."
                    }
                    android.os.Handler(context.mainLooper).post { onStatus(message) }
                    lastStatusAt = elapsedMs
                }

                if (speechDetected && elapsedMs >= 1_500L && silenceMs >= silenceToStopMs) break
            }
            recorder.stop()
            check(speechDetected) {
                "Речь не обнаружена. Попробуйте говорить ближе к микрофону, уменьшить шум или выбрать режим RU/EN вручную."
            }
            check(allSamples.size >= sampleRate / 2) {
                "Микрофон не передал достаточно аудио. Проверьте разрешение на микрофон и попробуйте ещё раз."
            }
            val samples = FloatArray(allSamples.size) { allSamples[it] }
            android.os.Handler(context.mainLooper).post { onStatus("Распознаю речь локально… это может занять несколько секунд.") }
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream).text.trim()
            android.os.Handler(context.mainLooper).post {
                if (result.isBlank()) onStatus("Текст не распознан. Говорите ближе к микрофону и попробуйте ещё раз.")
                else {
                    onResult(result)
                    onStatus("Текст добавлен в заметку. Распознавание выполнено на устройстве.")
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
