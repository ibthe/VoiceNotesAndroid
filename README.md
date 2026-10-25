# VoiceNotesAndroid

Android note-taking app with local Room storage and offline speech transcription.

See [README-GITHUB-RU.md](README-GITHUB-RU.md) for setup, GitHub Actions build instructions, model packaging, privacy, and limitations.

Speech engine: sherpa-onnx + multilingual Whisper tiny INT8. The GitHub Actions workflow stages the model into app assets before assembling the APK. The source archive does not contain the model weights; the generated APK does.
