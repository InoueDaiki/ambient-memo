import { radius, spacing } from "@ambient-memo/design-tokens";
import { View } from "react-native";

import { useColors } from "./theme.ts";

export interface ProgressBarProps {
  // 0 から 1 まで
  value: number;
  // 読み上げで何の進み具合かを伝える
  label: string;
}

const PERCENT = 100;

export function ProgressBar({ value, label }: ProgressBarProps) {
  const palette = useColors();
  const percent = Math.round(Math.min(Math.max(value, 0), 1) * PERCENT);

  return (
    <View
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel={label}
      accessibilityValue={{ min: 0, max: PERCENT, now: percent }}
      style={{
        height: spacing.sm,
        borderRadius: radius.full,
        overflow: "hidden",
        backgroundColor: palette.surface,
      }}
    >
      <View style={{ height: "100%", width: `${percent}%`, backgroundColor: palette.primary }} />
    </View>
  );
}
