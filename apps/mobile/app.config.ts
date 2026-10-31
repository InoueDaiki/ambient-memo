// Expo の設定。android/ は生成物なので、ネイティブの設定はここと config plugin に書く。
// 環境変数(CI が渡す。手元では無くてよい):
// - APP_VARIANT: production で普段使いの版。それ以外は開発版(アプリ ID に .dev が付く)
// - APP_VERSION: 表示用の版(versionName)
// - ANDROID_VERSION_CODE: versionCode

import type { ConfigContext, ExpoConfig } from "expo/config";

// 公開後は変えない。変えると別のアプリになり、上書きインストールできない
const baseId = "dev.ambientmemo.app";
const isProduction = process.env.APP_VARIANT === "production";

function versionCode(): number {
  const raw = process.env.ANDROID_VERSION_CODE ?? "1";
  const code = Number(raw);
  if (!Number.isInteger(code) || code < 1) {
    throw new Error(`ANDROID_VERSION_CODE は 1 以上の整数にする: ${raw}`);
  }
  return code;
}

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: isProduction ? "ambient-memo" : "ambient-memo dev",
  slug: "ambient-memo",
  version: process.env.APP_VERSION ?? "0.1.0",
  orientation: "portrait",
  android: {
    package: isProduction ? baseId : `${baseId}.dev`,
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
  plugins: ["@ambient-memo/engine-bridge", "./plugins/with-release-signing.js"],
});
