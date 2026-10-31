import { minTouchSize, radius, spacing, typography } from "@ambient-memo/design-tokens";
import { Pressable, Text as RNText } from "react-native";

import { useColors } from "./theme.ts";

export type ButtonVariant = "primary" | "secondary";

export interface ButtonProps {
  label: string;
  onPress: () => void;
  // primary は画面に1つだけ。secondary は地に馴染む控えめな操作
  variant?: ButtonVariant;
  disabled?: boolean;
}

export function Button({ label, onPress, variant = "primary", disabled = false }: ButtonProps) {
  const palette = useColors();
  const isPrimary = variant === "primary";
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => ({
        minHeight: minTouchSize + spacing.xs,
        alignItems: "center",
        justifyContent: "center",
        paddingHorizontal: spacing.lg,
        borderRadius: radius.md,
        backgroundColor: isPrimary
          ? palette[pressed ? "primaryPressed" : "primary"]
          : palette[pressed ? "surfacePressed" : "surface"],
        opacity: disabled ? 0.5 : 1,
      })}
    >
      <RNText style={[typography.label, { color: isPrimary ? palette.onPrimary : palette.fg }]}>
        {label}
      </RNText>
    </Pressable>
  );
}
