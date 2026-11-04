#!/usr/bin/env bash
# iOS のエンジンが使う推論ライブラリ(sherpa-onnx と ONNX Runtime の静的な xcframework)を
# engine/.cache/ios に取得する。すでにあれば何もしない。配布物の SHA-256 を確かめてから展開する。
# 版を変えるときは、URL と SHA-256 を一緒に変える
set -euo pipefail

root=engine/.cache/ios

sherpa_version=2.0.1
sherpa_url=https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v$sherpa_version-ios-static.xcframework.zip
sherpa_sha256=88aa569f71b7903e0bbdfbe6e2e114b3b95862b1ef0759eea3024f82926d7374
sherpa_dir=sherpa-onnx.xcframework

onnxruntime_version=1.28.2
onnxruntime_url=https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$onnxruntime_version/onnxruntime-ios-static-xcframework-$onnxruntime_version.xcframework.zip
onnxruntime_sha256=2c2299acbb461d26d4bac4bc85985d40e7c7177ed6072703ae0846d88b0b4599
onnxruntime_dir=onnxruntime.xcframework

download() {
  local url=$1 sha256=$2 path=$3
  curl --fail --location --retry 3 --output "$path" "$url"
  if ! echo "$sha256  $path" | shasum -a 256 --check --status; then
    rm -f "$path"
    echo "SHA-256 が合いません: $url" >&2
    exit 1
  fi
}

# zip を作業場所に展開し、中の xcframework を 1 つだけ取り出す
install_xcframework() {
  local url=$1 sha256=$2 name=$3
  if [ -d "$root/$name" ]; then
    return
  fi
  local zip=$work/$name.zip
  local out=$work/$name.out
  download "$url" "$sha256" "$zip"
  mkdir -p "$out"
  unzip -q "$zip" -d "$out"
  local found
  found=$(find "$out" -type d -name "$name" -prune | head -n 1)
  if [ -z "$found" ]; then
    echo "配布物に $name がありません: $url" >&2
    exit 1
  fi
  mv "$found" "$root/$name"
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$root"
install_xcframework "$sherpa_url" "$sherpa_sha256" "$sherpa_dir"
install_xcframework "$onnxruntime_url" "$onnxruntime_sha256" "$onnxruntime_dir"
