import { colors, type ColorPalette } from "@ambient-memo/design-tokens";
import { useColorScheme } from "react-native";

// 端末の明暗の設定に合わせた色を返す
export function useColors(): ColorPalette {
  return useColorScheme() === "dark" ? colors.dark : colors.light;
}
