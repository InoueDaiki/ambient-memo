// Expo の設定。android/ と ios/ は生成物なので、ネイティブの設定はここと config plugin に書く。
// 環境変数(CI が渡す。手元では無くてよい):
// - APP_VARIANT: production で普段使いの版。それ以外は開発版(アプリ ID に .dev が付く)
// - APP_VERSION: 表示用の版(versionName)
// - ANDROID_VERSION_CODE: versionCode
// - IOS_BUILD_NUMBER: iOS のビルド番号(CFBundleVersion)

import type { ConfigContext, ExpoConfig } from "expo/config";

// 公開後は変えない。変えると別のアプリになり、上書きインストールできない
const baseId = "dev.ambientmemo.app";
const isProduction = process.env.APP_VARIANT === "production";
// Android の package と iOS の bundleIdentifier は同じにする
const appId = isProduction ? baseId : `${baseId}.dev`;

function versionCode(): number {
  const raw = process.env.ANDROID_VERSION_CODE ?? "1";
  const code = Number(raw);
  if (!Number.isInteger(code) || code < 1) {
    throw new Error(`ANDROID_VERSION_CODE は 1 以上の整数にする: ${raw}`);
  }
  return code;
}

function buildNumber(): string {
  const raw = process.env.IOS_BUILD_NUMBER ?? "1";
  const code = Number(raw);
  if (!Number.isInteger(code) || code < 1) {
    throw new Error(`IOS_BUILD_NUMBER は 1 以上の整数にする: ${raw}`);
  }
  return String(code);
}

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: isProduction ? "ambient-memo" : "ambient-memo dev",
  slug: "ambient-memo",
  version: process.env.APP_VERSION ?? "0.1.0",
  orientation: "portrait",
  // 画面の明暗を端末の設定に合わせる
  userInterfaceStyle: "automatic",
  // 開発版は色を変えて、普段使いの版と見分ける。作り直しは scripts/generate-app-icon.swift
  icon: isProduction ? "./assets/icon.png" : "./assets/icon-dev.png",
  android: {
    adaptiveIcon: {
      foregroundImage: isProduction
        ? "./assets/adaptive-foreground.png"
        : "./assets/adaptive-foreground-dev.png",
      monochromeImage: "./assets/adaptive-monochrome.png",
      backgroundColor: isProduction ? "#3F6B4F" : "#C2701E",
    },
    package: appId,
    versionCode: versionCode(),
    // Android の自動バックアップは端末内のデータを Google Drive へ送るので切る
    allowBackup: false,
    permissions: [],
    // Expo の雛形が入れる権限のうち、使わないもの
    blockedPermissions: [
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.VIBRATE",
      "android.permission.SYSTEM_ALERT_WINDOW",
    ],
  },
  ios: {
    bundleIdentifier: appId,
    buildNumber: buildNumber(),
    infoPlist: {
      NSMicrophoneUsageDescription:
        "身の回りの会話を聞き取って文字にするために、マイクを使います。音声は端末の中だけで文字にし、端末の外へは送りません。",
      // audio: 画面を閉じても聞き取りを続ける。processing: メモの整理を BGProcessingTask で動かす
      UIBackgroundModes: ["audio", "processing"],
      // 整理のジョブ。エンジンが BGTaskScheduler に登録する名前と揃える
      BGTaskSchedulerPermittedIdentifiers: [`${appId}.organize`],
      // 暗号は OS の標準のものしか使わない
      ITSAppUsesNonExemptEncryption: false,
    },
  },
  plugins: ["@ambient-memo/engine-bridge", "./plugins/with-release-signing.js"],
});
