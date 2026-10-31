// android/ は prebuild の生成物なので、engine/ の読み込みを settings.gradle に足す
const path = require("node:path");
const { withSettingsGradle } = require("expo/config-plugins");

module.exports = function withEngineBridge(config) {
  return withSettingsGradle(config, (mod) => {
    const androidDir = path.join(mod.modRequest.projectRoot, "android");
    const engineDir = path.relative(androidDir, path.join(__dirname, "..", "..", "engine"));
    const line = `includeBuild("${engineDir}")`;
    if (!mod.modResults.contents.includes(line)) {
      mod.modResults.contents += `\n${line}\n`;
    }
    return mod;
  });
};
