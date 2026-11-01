#!/usr/bin/env bash
# Release APK の dex に、ネイティブのコードが名前で探すものが R8 に消されず残っていることを確かめる。
#
# sherpa-onnx と LiteRT-LM のネイティブは、JNI でクラス、フィールド、メソッドを名前で探す。Kotlin から触っていない
# 設定クラスも探し、見つからないと例外にならずプロセスごと落ちる。Debug は R8 を通らないので気づけない。
#
# AAR のクラス、インスタンスフィールド、ネイティブメソッドが、同じ名前で dex にあることを確かめる。
# .so の文字列は比べる元にしない。Kotlin のクラスがない機能の名前も入っていて、呼ばない限り探さないため。
#
# 使い方: scripts/check-release-native-classes.sh [APK のパス]
# 先に Gradle の assembleRelease を実行しておく。AAR は Gradle のキャッシュから読む
set -euo pipefail

apk=${1:-apps/mobile/android/app/build/outputs/apk/release/app-release.apk}
build_tools=$ANDROID_HOME/build-tools/37.0.0
versions=engine/gradle/libs.versions.toml
gradle_files=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1

# 列は Maven のグループ、アーティファクト、libs.versions.toml のキー
libraries=(
  "com.k2fsa sherpa-onnx sherpa-onnx"
  "com.google.ai.edge.litertlm litertlm-android litertlm"
)

if [ ! -f "$apk" ]; then
  echo "$apk がありません" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 1 行に 1 つ。C はクラス、F はインスタンスフィールド、N はネイティブメソッド
unzip -q "$apk" 'classes*.dex' -d "$work/apk"
for dex in "$work"/apk/classes*.dex; do
  "$build_tools/dexdump" "$dex" 2> /dev/null
done | awk '
  /^  Class descriptor/ { split($0, q, "\047"); class = q[2]; print "C " class; section = ""; next }
  /^  (Static fields|Instance fields|Direct methods|Virtual methods)/ { section = $1; next }
  /^      name / { split($0, q, "\047"); name = q[2]; if (section == "Instance") print "F " class "." name; next }
  /^      access / { if (index($0, "NATIVE")) print "N " class "." name }
' | LC_ALL=C sort -u > "$work/dex.txt"

failed=0
for library in "${libraries[@]}"; do
  read -r group artifact key <<< "$library"
  version=$(sed -nE "s/^$key = \"(.*)\"$/\1/p" "$versions")
  aar=$(find "$gradle_files/$group/$artifact/$version" -name "$artifact-$version.aar" 2> /dev/null | head -n 1)
  if [ -z "$aar" ]; then
    echo "$artifact $version の AAR が Gradle のキャッシュにありません" >&2
    exit 1
  fi

  jar=$work/$artifact/classes.jar
  unzip -q -o "$aar" classes.jar -d "$work/$artifact"
  unzip -Z1 "$jar" '*.class' | sed 's/\.class$//' | xargs javap -p -cp "$jar" | awk '
    /^[^ ].*(class|interface) / {
      class = $0
      sub(/.*(class|interface) /, "", class)
      sub(/[ <].*/, "", class)
      gsub(/\./, "/", class)
      class = "L" class ";"
      print "C " class
      next
    }
    /^  / && / native / { name = $0; sub(/\(.*/, "", name); sub(/.* /, "", name); print "N " class "." name; next }
    /^  / && !/\(/ && !/ static / { name = $NF; sub(/;$/, "", name); print "F " class "." name }
  ' | LC_ALL=C sort -u > "$work/$artifact/expected.txt"

  # 読めないまま通らないようにする
  if ! grep -q '^N ' "$work/$artifact/expected.txt"; then
    echo "$artifact の AAR からネイティブメソッドを読めません" >&2
    exit 1
  fi

  missing=$(LC_ALL=C comm -23 "$work/$artifact/expected.txt" "$work/dex.txt")
  if [ -n "$missing" ]; then
    echo "$artifact のクラス(C)、フィールド(F)、ネイティブメソッド(N)が dex にありません:" >&2
    echo "$missing" >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "ネイティブが名前で探せません。keep のルール(consumer-rules.pro)を確かめてください" >&2
  exit 1
fi
