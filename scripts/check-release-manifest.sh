#!/usr/bin/env bash
# Release のマニフェストの権限を確かめる。
#
# INTERNET: 今はアプリがモデルを取得するので、Release にも要る。なければモデルを入れられず、聞き取りを始められない。
#   モデルが確定したら方針を戻す。INTERNET を forbidden に足し、required の確認を消して、
#   modules/engine-bridge/app.plugin.js で Release のマニフェストから外す。
#   アプリが端末の外へ通信しないことを、権限で保証するため(docs/設計メモ.md)
# ACCESS_LOCAL_NETWORK は react-android の debug の AAR が Metro のために宣言する。Release の AAR にはないが、
# React Native の更新で変わったときに気づけるようにする。
# 先に Gradle の processReleaseManifest を実行しておく
set -euo pipefail

manifest=apps/mobile/android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
forbidden='ACCESS_LOCAL_NETWORK|SYSTEM_ALERT_WINDOW'
required='INTERNET'

if [ ! -f "$manifest" ]; then
  echo "$manifest がありません" >&2
  exit 1
fi

if grep -n -E "android\.permission\.($forbidden)\"" "$manifest"; then
  echo "Release のマニフェストに入れない権限が入っています" >&2
  exit 1
fi

if ! grep -q -E "android\.permission\.($required)\"" "$manifest"; then
  echo "Release のマニフェストに $required がありません。モデルを取得できなくなります" >&2
  exit 1
fi
