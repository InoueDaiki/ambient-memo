import { radius } from "@ambient-memo/design-tokens";
import { View } from "react-native";

import { Text } from "./Text.tsx";
import { useColors } from "./theme.ts";

// 一覧の文字と揃う大きさ。押す部品ではないので minTouchSize は要らない
const SIZE = 32;

export interface AvatarProps {
  // 1〜2 文字の印(「1」や名前の頭文字)
  label: string;
}

// 話者などを見分ける丸い印。名前は隣の文で伝えるので、読み上げからは外す
export function Avatar({ label }: AvatarProps) {
  const palette = useColors();
  return (
    <View
      accessible={false}
      importantForAccessibility="no-hide-descendants"
      accessibilityElementsHidden
      style={{
        width: SIZE,
        height: SIZE,
        borderRadius: radius.full,
        backgroundColor: palette.surface,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <Text variant="label" color="fg" numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}
