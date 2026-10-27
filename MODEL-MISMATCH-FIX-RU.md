# Предотвращение ошибки «в APK осталась Whisper tiny»

В версии 0.2.2 workflow с именем `VoiceNotes Android — BASE v0.2.2 VERIFIED` очищает старые assets и заново загружает официальную мультиязычную Whisper base INT8. Он проверяет минимальные размеры исходных файлов, а затем проверяет уже собранный APK: base encoder, base decoder, tokens, маркер `whisper-base-int8-v0.2.2`, отсутствие tiny и размер decoder >100 MB. При любом несоответствии workflow падает и не публикует артефакт.

Скачивать следует только `VoiceNotes-APK-BASE-VERIFIED-v0.2.2` из нового успешного запуска. Старые артефакты `VoiceNotes-debug-apk-offline-whisper` и `VoiceNotes-APK-WHISPER-BASE-v0.2.1` относятся к предыдущим версиям.

Если workflow v0.2.2 не отображается в GitHub Actions, значит файл `.github/workflows/android.yml` не был заменён в нужном репозитории/ветке. Убедитесь, что commit попал в `main` или `master`.
