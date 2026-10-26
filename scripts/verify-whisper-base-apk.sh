#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$(find "$ROOT/app/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk' -print -quit)"
fail() { echo "::error::$*"; exit 1; }
[[ -n "$APK" && -f "$APK" ]] || fail 'No debug APK found after assembleDebug.'
ENTRY_LIST="$(unzip -Z1 "$APK")"
require_entry() {
  local entry="$1"
  grep -Fxq "$entry" <<< "$ENTRY_LIST" || {
    echo 'APK assets actually found:'
    grep -E '^assets/models/' <<< "$ENTRY_LIST" || true
    fail "APK missing required entry: $entry"
  }
  echo "OK: $entry"
}
require_entry 'assets/models/base-encoder.int8.onnx'
require_entry 'assets/models/base-tokens.txt'
require_entry 'assets/models/model-version.txt'
require_entry 'assets/models/model-build-info.txt'
if grep -Eq '^assets/models/tiny([.-])' <<< "$ENTRY_LIST"; then
  grep -E '^assets/models/tiny([.-])' <<< "$ENTRY_LIST"
  fail 'APK contains TINY model files. Refusing to publish artifact.'
fi
if ! grep -Eq '^assets/models/base-decoder(\.int8)?\.onnx$' <<< "$ENTRY_LIST"; then
  echo 'APK assets actually found:'
  grep -E '^assets/models/' <<< "$ENTRY_LIST" || true
  fail 'APK missing BASE decoder.'
fi

read_asset() { unzip -p "$APK" "assets/models/$1"; }
MARKER="$(read_asset model-version.txt | tr -d '\r\n')"
[[ "$MARKER" == 'whisper-base-int8-v0.2.2' ]] || fail "Wrong model marker in APK: '$MARKER'"
INFO="$(read_asset model-build-info.txt)"
grep -Fxq 'model-version=whisper-base-int8-v0.2.2' <<< "$INFO" || fail 'Model build info does not identify BASE v0.2.2.'

# Verify uncompressed entry byte counts directly from the APK, not only source assets.
entry_bytes() { unzip -p "$APK" "assets/models/$1" | wc -c | tr -d ' '; }
ENC_BYTES="$(entry_bytes base-encoder.int8.onnx)"
TOK_BYTES="$(entry_bytes base-tokens.txt)"
DECODER_NAME='base-decoder.int8.onnx'
if ! grep -Fxq 'assets/models/base-decoder.int8.onnx' <<< "$ENTRY_LIST"; then DECODER_NAME='base-decoder.onnx'; fi
DEC_BYTES="$(entry_bytes "$DECODER_NAME")"
TOTAL_BYTES=$((ENC_BYTES + DEC_BYTES + TOK_BYTES))
printf 'MODEL BYTES INSIDE APK: encoder=%s decoder=%s tokens=%s total=%s\n' "$ENC_BYTES" "$DEC_BYTES" "$TOK_BYTES" "$TOTAL_BYTES"
(( ENC_BYTES >= 25000000 )) || fail "BASE encoder inside APK too small: $ENC_BYTES bytes."
if [[ "$DECODER_NAME" == 'base-decoder.int8.onnx' ]]; then
  (( DEC_BYTES >= 120000000 )) || fail "BASE INT8 decoder inside APK too small: $DEC_BYTES bytes."
else
  (( DEC_BYTES >= 180000000 )) || fail "BASE decoder inside APK too small: $DEC_BYTES bytes."
fi
(( TOK_BYTES >= 700000 )) || fail "BASE tokens inside APK too small: $TOK_BYTES bytes."
(( TOTAL_BYTES >= 150000000 )) || fail "Model data inside APK is only $TOTAL_BYTES bytes; expected BASE, not TINY."
APK_BYTES="$(stat -c '%s' "$APK")"
# Must be larger than the full ~150 MB model itself. This catches missing or compressed-away model entries.
(( APK_BYTES >= 150000000 )) || fail "APK is only $APK_BYTES bytes. This is not a complete Whisper BASE APK."

echo "VERIFIED APK: $APK"
echo "VERIFIED_APK_BYTES=$APK_BYTES"
echo "VERIFIED_MODEL_BYTES=$TOTAL_BYTES"
echo "VERIFIED_MODEL_MARKER=$MARKER"
sha256sum "$APK"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo '## Final APK verification'
    echo '- Result: PASS'
    echo "- APK bytes: $APK_BYTES"
    echo "- Model bytes inside APK: $TOTAL_BYTES"
    echo "- Model marker: $MARKER"
    echo '- Tiny model files inside APK: none'
  } >> "$GITHUB_STEP_SUMMARY"
fi
