import { spacing } from "@ambient-memo/design-tokens";
import { View } from "react-native";

import { Text } from "./Text.tsx";

export interface ListItemProps {
  // 本文。一覧の主役
  text: string;
  // 本文の上に小さく添える補足(話者など)
  label: string;
  // label の右に並べる補足(時刻など)
  meta?: string;
}

// 読むための行。枠や線を持たず、余白で区切る
export function ListItem({ text, label, meta }: ListItemProps) {
  return (
    <View
      accessible
      style={{ paddingHorizontal: spacing.lg, paddingVertical: spacing.md - spacing.xs }}
    >
      <View style={{ flexDirection: "row", gap: spacing.sm, marginBottom: spacing.xs }}>
        <Text variant="caption" color="fgMuted">
          {label}
        </Text>
        {meta !== undefined && (
          <Text variant="caption" color="fgMuted">
            {meta}
          </Text>
        )}
      </View>
      <Text selectable>{text}</Text>
    </View>
  );
}
