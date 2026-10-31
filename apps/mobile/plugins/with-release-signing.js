// Release の署名を環境変数の鍵だけで行う。Expo の雛形は Release を debug の鍵で署名するので置き換える。
// 鍵が無いときに debug の鍵へ黙って落ちないよう、Release のビルドを失敗させる
const { withAppBuildGradle } = require("expo/config-plugins");

const envCheck = `// with-release-signing.js が足す
def missingSigningEnv = ["ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"].findAll { !System.getenv(it) }
gradle.taskGraph.whenReady { graph ->
    if (graph.hasTask(":app:packageRelease") && !missingSigningEnv.isEmpty()) {
        throw new GradleException("Release の署名に必要な環境変数がありません: " + missingSigningEnv.join(", ") + "。README の「署名鍵」を参照")
    }
}

android {`;

const releaseConfig = `signingConfigs {
        release {
            if (missingSigningEnv.isEmpty()) {
                storeFile file(System.getenv("ANDROID_KEYSTORE_PATH"))
                storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias System.getenv("ANDROID_KEY_ALIAS")
                keyPassword System.getenv("ANDROID_KEY_PASSWORD")
            }
        }`;

// 雛形の該当箇所。Expo の更新で変わったら黙って素通りせず、prebuild を失敗させる
const edits = [
  [/^android \{/m, envCheck],
  [/signingConfigs \{/, releaseConfig],
  [
    /(release \{[^}]*?)signingConfig signingConfigs\.debug/,
    "$1signingConfig signingConfigs.release",
  ],
];

module.exports = function withReleaseSigning(config) {
  return withAppBuildGradle(config, (mod) => {
    for (const [pattern, replacement] of edits) {
      if (!pattern.test(mod.modResults.contents)) {
        throw new Error(`app/build.gradle に ${pattern} が見つからない`);
      }
      mod.modResults.contents = mod.modResults.contents.replace(pattern, replacement);
    }
    return mod;
  });
};
