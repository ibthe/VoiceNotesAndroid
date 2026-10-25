package com.example.voicenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
                Text("Голос распознаётся только встроенным on-device распознавателем Android. Если на телефоне нет офлайн-пакета для выбранного языка, диктовка сообщит об этом и не переключится на облако.", style = MaterialTheme.typography.bodyMedium)
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
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        onStatus("Для строгого офлайн-режима требуется Android 12 или новее."); return
    }
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        onStatus("На этом телефоне нет доступного встроенного офлайн-распознавания. Облачный режим не используется."); return
    }
    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        when (mode) {
            VoiceMode.MANUAL_RU -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            VoiceMode.MANUAL_EN -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            VoiceMode.AUTO, VoiceMode.MIXED -> { /* let the on-device service choose its default language */ }
        }
    }
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onStatus("Слушаю… Говорите.") }
        override fun onBeginningOfSpeech() { onStatus("Речь обнаружена…") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onStatus("Обрабатываю речь офлайн…") }
        override fun onError(error: Int) { onStatus("Не удалось распознать речь офлайн (код $error). Проверьте языковые пакеты на устройстве."); recognizer.destroy() }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onStatus("Речь не распознана.") else { onResult(text); onStatus("Распознавание завершено. Вставьте текст в заметку.") }
            recognizer.destroy()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    recognizer.startListening(intent)
}
