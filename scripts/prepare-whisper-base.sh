#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER_TEMP="${RUNNER_TEMP:-$(mktemp -d)}"
ARCHIVE="$RUNNER_TEMP/sherpa-onnx-whisper-base.tar.bz2"
EXTRACT="$RUNNER_TEMP/sherpa-onnx-whisper-base-extracted"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
ASSET_ROOT="$ROOT/app/src/main/assets"
ASSET_DIR="$ASSET_ROOT/models"

fail() { echo "::error::$*"; exit 1; }

# Remove all stale model folders from every Android source set (main/debug/release/flavors).
# This is deliberately broader than deleting only app/src/main/assets/models.
find "$ROOT/app/src" -type d -path '*/assets/models' -prune -exec rm -rf {} +
# Also remove known tiny model files if any were committed outside the conventional models folder.
find "$ROOT/app/src" -type f \( \
  -name 'tiny-encoder.int8.onnx' -o -name 'tiny-encoder.onnx' -o \
  -name 'tiny-decoder.int8.onnx' -o -name 'tiny-decoder.onnx' -o \
  -name 'tiny-tokens.txt' -o -name 'tiny.en-encoder.int8.onnx' -o \
  -name 'tiny.en-decoder.int8.onnx' -o -name 'tiny.en-tokens.txt' \
\) -delete
rm -rf "$EXTRACT" "$ARCHIVE"
mkdir -p "$EXTRACT" "$ASSET_DIR"

echo "Downloading official multilingual Whisper BASE archive: $MODEL_URL"
curl --fail --show-error --location --retry 5 --retry-all-errors --retry-delay 3 \
  --connect-timeout 30 --max-time 1800 "$MODEL_URL" --output "$ARCHIVE"
ARCHIVE_BYTES="$(stat -c '%s' "$ARCHIVE")"
echo "Downloaded archive: $ARCHIVE_BYTES bytes"
# A tiny/wrong/error-page download must not continue into APK build.
(( ARCHIVE_BYTES > 50000000 )) || fail "Model archive is suspiciously small ($ARCHIVE_BYTES bytes). Download aborted."

# Print only the model candidates first, so CI logs expose what the archive actually contained.
echo 'Archive Whisper model entries:'
tar -tjf "$ARCHIVE" | grep -E '(^|/)(base|tiny)(-encoder|-decoder|-tokens)' | head -80 || true

tar -xjf "$ARCHIVE" -C "$EXTRACT"
find_one() { find "$EXTRACT" -type f -name "$1" -print -quit; }
ENCODER="$(find_one 'base-encoder.int8.onnx')"
DECODER="$(find_one 'base-decoder.int8.onnx')"
DECODER_NAME='base-decoder.int8.onnx'
if [[ -z "$DECODER" ]]; then
  DECODER="$(find_one 'base-decoder.onnx')"
  DECODER_NAME='base-decoder.onnx'
fi
TOKENS="$(find_one 'base-tokens.txt')"
[[ -n "$ENCODER" ]] || fail 'Official archive did not contain base-encoder.int8.onnx. Refusing to build.'
[[ -n "$DECODER" ]] || fail 'Official archive did not contain base-decoder.int8.onnx or base-decoder.onnx. Refusing to build.'
[[ -n "$TOKENS" ]] || fail 'Official archive did not contain base-tokens.txt. Refusing to build.'

ENC_BYTES="$(stat -c '%s' "$ENCODER")"
DEC_BYTES="$(stat -c '%s' "$DECODER")"
TOK_BYTES="$(stat -c '%s' "$TOKENS")"
TOTAL_BYTES=$((ENC_BYTES + DEC_BYTES + TOK_BYTES))
printf 'BASE source sizes: encoder=%s decoder=%s tokens=%s total=%s bytes\n' "$ENC_BYTES" "$DEC_BYTES" "$TOK_BYTES" "$TOTAL_BYTES"
# Official multilingual Whisper BASE INT8 files are roughly 29 MB + 131 MB + 0.8 MB.
# Tiny models are roughly 12 MB + 86 MB + 0.8 MB. Enforce dimensions to stop silently packaging tiny.
(( ENC_BYTES >= 25000000 )) || fail "Encoder is only $ENC_BYTES bytes; expected Whisper BASE INT8 (~29 MB), not tiny."
if [[ "$DECODER_NAME" == 'base-decoder.int8.onnx' ]]; then
  (( DEC_BYTES >= 120000000 )) || fail "INT8 decoder is only $DEC_BYTES bytes; expected Whisper BASE (~131 MB), not tiny."
else
  (( DEC_BYTES >= 180000000 )) || fail "Decoder is only $DEC_BYTES bytes; expected full-precision Whisper BASE (~197 MB), not tiny."
fi
(( TOK_BYTES >= 700000 )) || fail "Tokens file is only $TOK_BYTES bytes; expected base-tokens.txt (~817 KB)."
(( TOTAL_BYTES >= 150000000 )) || fail "Combined model payload is only $TOTAL_BYTES bytes; minimum for BASE is 150,000,000."

cp "$ENCODER" "$ASSET_DIR/base-encoder.int8.onnx"
cp "$DECODER" "$ASSET_DIR/$DECODER_NAME"
cp "$TOKENS" "$ASSET_DIR/base-tokens.txt"
printf '%s\n' 'whisper-base-int8-v0.2.2' > "$ASSET_DIR/model-version.txt"
{
  echo 'model=Whisper BASE multilingual INT8'
  echo 'model-version=whisper-base-int8-v0.2.2'
  echo "source-url=$MODEL_URL"
  echo "encoder-bytes=$ENC_BYTES"
  echo "decoder-file=$DECODER_NAME"
  echo "decoder-bytes=$DEC_BYTES"
  echo "tokens-bytes=$TOK_BYTES"
  echo "combined-model-bytes=$TOTAL_BYTES"
  echo "encoder-sha256=$(sha256sum "$ASSET_DIR/base-encoder.int8.onnx" | cut -d ' ' -f 1)"
  echo "decoder-sha256=$(sha256sum "$ASSET_DIR/$DECODER_NAME" | cut -d ' ' -f 1)"
  echo "tokens-sha256=$(sha256sum "$ASSET_DIR/base-tokens.txt" | cut -d ' ' -f 1)"
} > "$ASSET_DIR/model-build-info.txt"

# Last pre-build assertion: there can be no tiny model left in any Android source set.
if find "$ROOT/app/src" -type f \( -name 'tiny*.onnx' -o -name 'tiny*-tokens.txt' \) -print -quit | grep -q .; then
  find "$ROOT/app/src" -type f \( -name 'tiny*.onnx' -o -name 'tiny*-tokens.txt' \) -print
  fail 'Stale tiny model still exists in an Android source set.'
fi

echo 'Staged model assets:'
ls -lh "$ASSET_DIR"
echo "MODEL_ASSET_TOTAL_BYTES=$TOTAL_BYTES"
echo 'Whisper BASE pre-build validation passed.'
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo '## Whisper model selected'
    echo '- Model: multilingual Whisper BASE INT8'
    echo "- Total source model size: $TOTAL_BYTES bytes"
    echo "- Decoder: $DECODER_NAME ($DEC_BYTES bytes)"
  } >> "$GITHUB_STEP_SUMMARY"
fi
