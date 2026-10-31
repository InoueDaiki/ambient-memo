#!/usr/bin/env bash
# 開発で使うモデルを engine/.cache/models に取得する。すでにあれば何もしない。
# 配布物の SHA-256 を確かめてから、使うファイルだけを取り出す。ファイルごとの値は ModelCatalog にあり、
# 端末(ModelStore.verify)が確かめる。JVM のテストと adb で入れる開発用で、アプリは自分で取得する(ModelDownloader)。
# 配布物の URL と SHA-256 は ModelCatalog にもあるので、モデルを変えたら一緒に変える
set -euo pipefail

releases=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
root=engine/.cache/models

vad_url=$releases/silero_vad.onnx
vad_sha256=9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6

asr_id=sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01
asr_sha256=e0981d0d5d7b446d41010831b59091ebf57d2aa7b79980f67ca37af460b5842d
asr_files=(
  encoder-epoch-99-avg-1.int8.onnx
  decoder-epoch-99-avg-1.int8.onnx
  joiner-epoch-99-avg-1.int8.onnx
  tokens.txt
  test_wavs
)

download() {
  local url=$1 sha256=$2 path=$3
  curl --fail --location --retry 3 --output "$path" "$url"
  if ! echo "$sha256  $path" | shasum -a 256 --check --status; then
    rm -f "$path"
    echo "SHA-256 が合いません: $url" >&2
    exit 1
  fi
}

speaker_id=3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced
# 配布元のタグの綴りは recongition のまま
speaker_url=https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$speaker_id.onnx
speaker_sha256=aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$root/silero-vad"
if [ ! -f "$root/silero-vad/silero_vad.onnx" ]; then
  download "$vad_url" "$vad_sha256" "$work/silero_vad.onnx"
  mv "$work/silero_vad.onnx" "$root/silero-vad/"
fi

if [ ! -f "$root/$asr_id/tokens.txt" ]; then
  download "$releases/$asr_id.tar.bz2" "$asr_sha256" "$work/asr.tar.bz2"
  mkdir -p "$root/$asr_id"
  tar -xjf "$work/asr.tar.bz2" -C "$work" "${asr_files[@]/#/$asr_id/}"
  for file in "${asr_files[@]}"; do
    mv "$work/$asr_id/$file" "$root/$asr_id/"
  done
fi

mkdir -p "$root/$speaker_id"
if [ ! -f "$root/$speaker_id/$speaker_id.onnx" ]; then
  download "$speaker_url" "$speaker_sha256" "$work/$speaker_id.onnx"
  mv "$work/$speaker_id.onnx" "$root/$speaker_id/"
fi
