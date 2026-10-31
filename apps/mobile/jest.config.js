// 画面の結合テスト。pnpm の配置では依存が node_modules/.pnpm の下に入るので、
// 変換の対象から外す範囲を jest-expo の既定より狭める
module.exports = {
  preset: "jest-expo",
  // 最初のテストは React Native の変換と読み込みを待つので、CI では 5 秒の既定を超える
  testTimeout: 30000,
  roots: ["<rootDir>/src"],
  setupFiles: ["<rootDir>/jest.setup.js"],
  transformIgnorePatterns: [
    "node_modules/(?!(?:\\.pnpm/[^/]+/node_modules/)?(?:(?:jest-)?react-native|@react-native(?:-community)?|expo(?:nent)?(?:-[^/]+)?|@expo(?:nent)?|@testing-library/react-native|test-renderer|universal-test-renderer|react-native-safe-area-context|react-native-screens|@react-navigation)(?:/|$))",
  ],
};
