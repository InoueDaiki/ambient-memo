import {
  type ColorName,
  hairline,
  minTouchSize,
  radius,
  spacing,
  typography,
} from "@ambient-memo/design-tokens";
import { Pressable, Text as RNText } from "react-native";

import { useColors } from "./theme.ts";

export type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";

export interface ButtonProps {
  label: string;
  onPress: () => void;
  // primary は画面の主な操作で、画面に1つだけ。secondary は普通の操作。
  // danger は止める、統合するなど、動いているものや記録を変える操作。ghost は戻る、やめるなどの離れる操作
  variant?: ButtonVariant;
  disabled?: boolean;
}

const looks: Record<
  ButtonVariant,
  { fill?: ColorName; pressedFill: ColorName; text: ColorName; outline?: ColorName }
> = {
  primary: { fill: "primary", pressedFill: "primaryPressed", text: "onPrimary" },
  secondary: { pressedFill: "surfacePressed", text: "fg", outline: "borderStrong" },
  danger: { fill: "dangerSoft", pressedFill: "dangerSoftPressed", text: "danger" },
  ghost: { pressedFill: "surfacePressed", text: "fgMuted" },
};

export function Button({ label, onPress, variant = "primary", disabled = false }: ButtonProps) {
  const palette = useColors();
  const look = looks[variant];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => {
        const fill = pressed ? look.pressedFill : look.fill;
        return {
          minHeight: minTouchSize + spacing.xs,
          alignItems: "center",
          justifyContent: "center",
          paddingHorizontal: spacing.lg,
          borderRadius: radius.md,
          backgroundColor: fill === undefined ? "transparent" : palette[fill],
          borderWidth: look.outline === undefined ? 0 : hairline,
          borderColor: look.outline === undefined ? "transparent" : palette[look.outline],
          opacity: disabled ? 0.5 : 1,
        };
      }}
    >
      <RNText style={[typography.label, { color: palette[look.text] }]}>{label}</RNText>
    </Pressable>
  );
}
