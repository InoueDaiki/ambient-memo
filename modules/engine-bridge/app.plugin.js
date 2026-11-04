// android/ と ios/ は prebuild の生成物なので、エンジンに要る設定をここで足す
// - settings.gradle: engine/ の読み込み
// - build.gradle: sherpa-onnx の AAR の取得元。engine/settings.gradle.kts と同じ指定
// - Podfile: engine/ios のエンジン(AmbientMemoEngine)と、scripts/fetch-ios-frameworks.sh が作る LiteRTLM の Pod。
//   Android の includeBuild と同じく、ソースのある場所を直接読む
// INTERNET はモデルの取得に使うので、Release にも残す。モデルが確定したら、ここで Release の
// マニフェストから外し、scripts/check-release-manifest.sh も戻す
const path = require("node:path");
const { withPodfile, withProjectBuildGradle, withSettingsGradle } = require("expo/config-plugins");

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

const podMarker = "# engine-bridge の app.plugin.js が足す";

function withEnginePods(config) {
  return withPodfile(config, (mod) => {
    if (mod.modResults.contents.includes(podMarker)) {
      return mod;
    }
    const iosDir = path.join(mod.modRequest.projectRoot, "ios");
    const engineDir = path.join(__dirname, "..", "..", "engine");
    const enginePath = path.relative(iosDir, path.join(engineDir, "ios"));
    const litertPath = path.relative(iosDir, path.join(engineDir, ".cache", "ios", "litert-lm"));
    const lines = [
      `  ${podMarker}`,
      `  pod 'AmbientMemoEngine', :path => '${enginePath}'`,
      `  pod 'LiteRTLM', :path => '${litertPath}'`,
    ].join("\n");
    const anchor = "use_expo_modules!";
    if (!mod.modResults.contents.includes(anchor)) {
      throw new Error("Podfile に use_expo_modules! がありません");
    }
    mod.modResults.contents = mod.modResults.contents.replace(anchor, `${anchor}\n${lines}`);
    return mod;
  });
}

module.exports = function withEngineBridge(config) {
  return withEnginePods(withSherpaRepository(withEngineBuild(config)));
};
