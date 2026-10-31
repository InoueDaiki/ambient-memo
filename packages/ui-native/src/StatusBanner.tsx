import { radius, spacing } from "@ambient-memo/design-tokens";
import { View } from "react-native";

import { Text } from "./Text.tsx";
import { useColors } from "./theme.ts";

export type StatusBannerTone = "neutral" | "listening" | "danger";

export interface StatusBannerProps {
  tone: StatusBannerTone;
  message: string;
}

const DOT_SIZE = 8;

const tones = {
  neutral: { background: "surface", foreground: "fgMuted" },
  listening: { background: "listeningSoft", foreground: "listening" },
  danger: { background: "dangerSoft", foreground: "danger" },
} as const;

// 状態を1行で伝える帯。色だけに頼らず、必ず文で伝える
export function StatusBanner({ tone, message }: StatusBannerProps) {
  const palette = useColors();
  const { background, foreground } = tones[tone];

  return (
    <View
      accessibilityLiveRegion="polite"
      style={{
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.sm,
        paddingHorizontal: spacing.md,
        paddingVertical: spacing.sm,
        borderRadius: radius.md,
        backgroundColor: palette[background],
      }}
    >
      <View
        style={{
          width: DOT_SIZE,
          height: DOT_SIZE,
          borderRadius: radius.full,
          backgroundColor: palette[foreground],
        }}
      />
      <View style={{ flex: 1 }}>
        <Text variant="label" color={foreground}>
          {message}
        </Text>
      </View>
    </View>
  );
}
