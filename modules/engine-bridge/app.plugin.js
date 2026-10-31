// android/ は prebuild の生成物なので、エンジンに要る設定をここで足す
// - settings.gradle: engine/ の読み込み
// - build.gradle: sherpa-onnx の AAR の取得元。engine/settings.gradle.kts と同じ指定
// INTERNET はモデルの取得に使うので、Release にも残す。モデルが確定したら、ここで Release の
// マニフェストから外し、scripts/check-release-manifest.sh も戻す
const path = require("node:path");
const { withProjectBuildGradle, withSettingsGradle } = require("expo/config-plugins");

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

module.exports = function withEngineBridge(config) {
  return withSherpaRepository(withEngineBuild(config));
};
