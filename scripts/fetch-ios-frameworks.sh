#!/usr/bin/env bash
# iOS のエンジンが使う推論ライブラリを engine/.cache/ios に取得する。すでにあれば何もしない。
# 配布物の SHA-256 を確かめてから展開する。版を変えるときは、URL と SHA-256 を一緒に変える
# - sherpa-onnx と ONNX Runtime の静的な xcframework(engine/ios/AmbientMemoEngine.podspec が使う)
# - LiteRT-LM の CLiteRTLM.xcframework(動的)と、固定した版の Swift のラッパーのソース。
#   CocoaPods の配布がないので、ここで LiteRTLM の podspec を作る(app.plugin.js が Podfile に足す)
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

litert_version=0.19.0
# Package.swift の binaryTarget の checksum と同じ値
litert_url=https://github.com/google-ai-edge/LiteRT-LM/releases/download/v$litert_version/CLiteRTLM.xcframework.zip
litert_sha256=b7b2450fc084058adc5185498f8b7810a9297e054a10cf55818b62e1d5f8d65d
litert_source=https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/v$litert_version/swift
litert_dir=litert-lm
# Package.swift の LiteRTLM のターゲットのソース(テストと apple_fm を除く)と、その SHA-256
litert_sources="
Benchmark.swift 554a192f071b791f72a3f8599285b89a5538f32cc06d67a503e1a8bf9c4c6bb7
Config.swift 8fb601890d23d957dba70ebce449ac9df5ff612c0d9474db1aa54b644debd52c
Conversation.swift ad4a051b2fd7780ab1bf8862dad6f2ebfd03e20fcd7287c54bd7eb3e24606617
EmbeddingEngine.swift de17d18e248b5b0d8a89ca70bef0fd39a81b5d05f4dc9732388d7cb35e1dc502
EmbeddingEngineConfig.swift 541dd557b1a4ca189668dbcec2b83290e7f2b7fdec1dc93c0079ec65a2a97c38
Engine.swift 6449e9b8c6b80655ef92abba8e940fc17b903bf5d69656d11d1d69b1026bb017
ExperimentalFlags.swift 5a68f4662a0b8b9e6ff07bebce882f7f72ed9251193c0ceab3f03eb981d11d46
LiteRTLMError.swift 86de95aaa32e753291a25429281ecb044bcb12741df076f9b6dd8b9aa5ea2bf0
Message.swift 8564f90fbaa62b295473a26569cce1afef7e16a66365b53429d5a98578a36b19
ModelInfo.swift a203bab31b403df442888dcfc4385d6a97d85550e7cdddb7e5425032414a97c7
ResponseFormat.swift 071705a8d9e9916a83b2197514d58bdbd3a30e643903a9e826a500fb67772067
Tool.swift d9698c6c7f8425cfa1f479cec5070fe94d9ca732b3e20b171e3dd90ca1545422
ToolManager.swift 2199ee34981755f2cc36a2947efb0d65bcf873df0cdd8683e1c82cdff92e9e86
"

# LiteRT-LM の xcframework とソースを取得し、LiteRTLM の podspec を書く。全部揃ってから置く
install_litert() {
  # podspec の形を変えたら作り直す
  if [ -f "$root/$litert_dir/LiteRTLM.podspec" ] && grep -q EXCLUDED_ARCHS "$root/$litert_dir/LiteRTLM.podspec"; then
    return
  fi
  local out=$work/$litert_dir
  mkdir -p "$out/Sources"
  local zip=$work/CLiteRTLM.xcframework.zip
  download "$litert_url" "$litert_sha256" "$zip"
  unzip -q "$zip" -d "$work/litert-zip"
  mv "$work/litert-zip/CLiteRTLM.xcframework" "$out/CLiteRTLM.xcframework"
  echo "$litert_sources" | while read -r name sha256; do
    [ -z "$name" ] && continue
    download "$litert_source/$name" "$sha256" "$out/Sources/$name"
  done
  cat > "$out/LiteRTLM.podspec" <<PODSPEC
# scripts/fetch-ios-frameworks.sh が作る。手で編集しない
Pod::Spec.new do |s|
  s.name             = 'LiteRTLM'
  s.version          = '$litert_version'
  s.summary          = 'LiteRT-LM の Swift のラッパー(v$litert_version)'
  s.license          = { type: 'Apache-2.0' }
  s.author           = 'Google'
  s.homepage         = 'https://github.com/google-ai-edge/LiteRT-LM'
  s.source           = { git: '' }
  s.platforms        = { :ios => '15.0' }
  s.swift_version    = '5.9'
  s.source_files     = 'Sources/*.swift'
  s.vendored_frameworks = 'CLiteRTLM.xcframework'
  # Xcode の依存の走査は xcframework の展開より先に動くので、スライスの場所を直接渡す
  s.pod_target_xcconfig = {
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]' => '\$(inherited) "\$(PODS_TARGET_SRCROOT)/CLiteRTLM.xcframework/ios-arm64"',
    'FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]' => '\$(inherited) "\$(PODS_TARGET_SRCROOT)/CLiteRTLM.xcframework/ios-arm64-simulator"',
    # シミュレーター向けは arm64 しかない
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64',
  }
  s.user_target_xcconfig = { 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
end
PODSPEC
  rm -rf "$root/$litert_dir"
  mv "$out" "$root/$litert_dir"
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$root"
install_xcframework "$sherpa_url" "$sherpa_sha256" "$sherpa_dir"
install_xcframework "$onnxruntime_url" "$onnxruntime_sha256" "$onnxruntime_dir"
install_litert
