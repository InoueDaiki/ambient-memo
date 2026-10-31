import { spacing } from "@ambient-memo/design-tokens";
import { View } from "react-native";

import { Text } from "./Text.tsx";

export interface EmptyStateProps {
  title: string;
  description: string;
}

// 中身がないときに、領域の中央に置く
export function EmptyState({ title, description }: EmptyStateProps) {
  return (
    <View
      style={{
        flex: 1,
        alignItems: "center",
        justifyContent: "center",
        gap: spacing.sm,
        paddingHorizontal: spacing.xl,
      }}
    >
      <Text variant="body">{title}</Text>
      <Text variant="caption" color="fgMuted" align="center">
        {description}
      </Text>
    </View>
  );
}
