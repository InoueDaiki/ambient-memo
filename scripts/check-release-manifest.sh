#!/usr/bin/env bash
# Release のマニフェストに、入れないと決めた権限がないことを確かめる。
# INTERNET: アプリが端末の外へ通信しないことを、権限で保証する。Debug は Metro のために残る。
# ACCESS_LOCAL_NETWORK は react-android の debug の AAR が Metro のために宣言する。Release の AAR にはないが、
# React Native の更新で変わったときに気づけるようにする。
# 先に Gradle の processReleaseManifest を実行しておく
set -euo pipefail

manifest=apps/mobile/android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml

if [ ! -f "$manifest" ]; then
  echo "$manifest がありません" >&2
  exit 1
fi

if grep -n -E 'android\.permission\.(INTERNET|ACCESS_LOCAL_NETWORK|SYSTEM_ALERT_WINDOW)' "$manifest"; then
  echo "Release のマニフェストに入れない権限が入っています" >&2
  exit 1
fi
