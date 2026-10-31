/* global jest, require */
// 端末がないので、安全領域は固定の値を返す実装に差し替える
jest.mock(
  "react-native-safe-area-context",
  () => require("react-native-safe-area-context/jest/mock").default,
);
