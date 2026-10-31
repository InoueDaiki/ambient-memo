import { radius, spacing } from "@ambient-memo/design-tokens";
import { View } from "react-native";

import { Text } from "./Text.tsx";
import { useColors } from "./theme.ts";

export interface TagProps {
  label: string;
}

// 行の状態を伝える小さな札。色は持たず、文で伝える
export function Tag({ label }: TagProps) {
  const palette = useColors();
  return (
    <View
      style={{
        paddingHorizontal: spacing.sm,
        borderRadius: radius.sm,
        backgroundColor: palette.surface,
      }}
    >
      <Text variant="caption" color="fg">
        {label}
      </Text>
    </View>
  );
}
