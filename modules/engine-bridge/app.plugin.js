// android/ は prebuild の生成物なので、エンジンに要る設定をここで足す
// - settings.gradle: engine/ の読み込み
// - build.gradle: sherpa-onnx の AAR の取得元。engine/settings.gradle.kts と同じ指定
// - Release のマニフェスト: INTERNET を外す。Debug は Metro のために残す
const fs = require("node:fs");
const path = require("node:path");
const {
  withDangerousMod,
  withProjectBuildGradle,
  withSettingsGradle,
} = require("expo/config-plugins");

const sherpaRepository = `
// engine-bridge の app.plugin.js が足す
allprojects {
  repositories {
    ivy {
      url = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
      patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
      metadataSources { artifact() }
      content { includeGroup("com.k2fsa") }
    }
  }
}
`;

const releaseManifest = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
</manifest>
`;

function withEngineBuild(config) {
  return withSettingsGradle(config, (mod) => {
    const androidDir = path.join(mod.modRequest.projectRoot, "android");
    const engineDir = path.relative(androidDir, path.join(__dirname, "..", "..", "engine"));
    const line = `includeBuild("${engineDir}")`;
    if (!mod.modResults.contents.includes(line)) {
      mod.modResults.contents += `\n${line}\n`;
    }
    return mod;
  });
}

function withSherpaRepository(config) {
  return withProjectBuildGradle(config, (mod) => {
    if (!mod.modResults.contents.includes("k2-fsa/sherpa-onnx")) {
      mod.modResults.contents += sherpaRepository;
    }
    return mod;
  });
}

function withoutReleaseInternet(config) {
  return withDangerousMod(config, [
    "android",
    (mod) => {
      const directory = path.join(mod.modRequest.platformProjectRoot, "app/src/release");
      fs.mkdirSync(directory, { recursive: true });
      fs.writeFileSync(path.join(directory, "AndroidManifest.xml"), releaseManifest);
      return mod;
    },
  ]);
}

module.exports = function withEngineBridge(config) {
  return withoutReleaseInternet(withSherpaRepository(withEngineBuild(config)));
};
