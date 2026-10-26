# VoiceNotesAndroid — Whisper BASE v0.2.2 (жёсткая проверка модели)

## Важно: исправление повторяющейся ошибки tiny вместо base

Исходный ZIP **не содержит ONNX-модель** и поэтому сам по себе остаётся небольшим. Модель загружается во время GitHub Actions и встраивается в APK. Проверять нужно размер **собранного APK** и логи новой сборки, а не размер исходного ZIP.

Модель multilingual Whisper BASE имеет примерно такие размеры: encoder INT8 ~29 MB, decoder INT8 ~131 MB, tokens ~0.8 MB. Полный набор — около 160 MB; APK должен быть не меньше 150,000,000 bytes. Tiny-набор заметно меньше. Workflow ниже проверяет размеры исходных весов и фактические файлы внутри итогового APK; если что-то не совпадает, сборка завершается ошибкой и артефакт не публикуется.

## Обязательная установка в GitHub

1. Сначала скачайте/сохраните копию текущего репозитория, чтобы не потерять локальные изменения.
2. В GitHub откройте папку `.github/workflows/` и **удалите старые workflow-файлы**, которые собирали Whisper tiny или назывались иначе. Оставьте только новый `android.yml` из этого архива. Это важно: старый workflow может продолжать публиковать APK с tiny.
3. Загрузите содержимое папки `VoiceNotesAndroid-main` в **корень** репозитория, а не как дополнительную вложенную папку. Перезапишите `app/build.gradle.kts`, `app/src/main/java/com/example/voicenotes/MainActivity.kt` и `.github/workflows/android.yml`. Добавьте папку `scripts/` целиком.
4. После commit откройте **Actions**. Должен появиться workflow с точным названием:
   `VoiceNotes BASE v0.2.2 — FORCE BASE MODEL AND VERIFY APK SIZE`.
5. Откройте именно этот workflow и нажмите **Run workflow** на ветке `main` или `master`. Не запускайте старые workflow.
6. В успешных логах должны быть шаги **Download, validate, and stage Whisper BASE**, **Verify model inside the final APK and enforce expected size**, строки `VERIFIED_APK_BYTES=...` и `VERIFIED_MODEL_BYTES=...`, а также `Result: PASS` в summary.
7. Скачайте только артефакт **`VoiceNotes-BASE-v0.2.2-APK-VERIFIED`**. Внутри будет файл `VoiceNotes-Whisper-BASE-v0.2.2-VERIFIED.apk` и `SHA256SUMS.txt`.

## Что проверяет сборка

- Загружает официальный multilingual Whisper BASE архив sherpa-onnx.
- Удаляет старые папки `assets/models` из каждого Android source set и известные tiny-файлы перед подготовкой модели.
- Проверяет размер архива, encoder, decoder, tokens и общий объём модели.
- Пишет в assets маркер `whisper-base-int8-v0.2.2` и `model-build-info.txt` с размерами и SHA-256 файлов.
- Запускает `clean assembleDebug`.
- Проверяет *сам APK*: наличие base encoder/decoder/tokens и маркера, отсутствие tiny, размер файлов внутри APK и общий размер APK.
- Если модель tiny, не хватает файла или APK слишком мал, job завершается ошибкой и артефакт `...VERIFIED` не создаётся.

## Если workflow с точным названием не появился

Это означает, что обновлённый `.github/workflows/android.yml` не попал в корень репозитория либо commit сделан не в `main`/`master`. Не устанавливайте APK из старых запусков. Проверьте, что файл находится по пути `.github/workflows/android.yml` (именно с точкой в начале `.github`) и что его содержимое начинается с `name: VoiceNotes BASE v0.2.2`.

## О приложении

- Распознавание работает локально через sherpa-onnx и multilingual Whisper BASE.
- Модель включается в APK; после установки интернет для распознавания не нужен.
- Запись и заметки хранятся локально.
- `transcribe` сохраняет исходный язык речи; точность смешанного RU/EN всё равно зависит от записи и модели.

## Проверка в текущей среде

Архив и исходные shell-скрипты проверены статически (структура ZIP, синтаксис Bash и согласованность имён/маркерной версии). Полную сборку APK здесь выполнить не удалось: среда не содержит установленного Android SDK/Gradle и не может скачивать файлы с GitHub. Поэтому результат сборки должен быть подтверждён запуском указанного GitHub Actions workflow.
