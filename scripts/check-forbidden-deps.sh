#!/usr/bin/env bash
# 解析やクラッシュレポートの SDK が依存関係に入っていないことを確かめる(CLAUDE.md の原則)。
# 見る先: pnpm のロックファイル、Gradle の版カタログ、engine-bridge の build.gradle
set -euo pipefail

pattern='sentry|bugsnag|crashlytics|firebase|amplitude|mixpanel|appsflyer|adjust-|datadog|newrelic|segment-analytics|@segment/|posthog|expo-insights|expo-updates'

if grep -n -i -E "$pattern" pnpm-lock.yaml engine/gradle/libs.versions.toml modules/engine-bridge/android/build.gradle; then
  echo "禁止する依存関係が入っています" >&2
  exit 1
fi
