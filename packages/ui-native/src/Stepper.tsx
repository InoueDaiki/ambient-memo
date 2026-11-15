import { hairline, minTouchSize, radius, spacing, typography } from "@ambient-memo/design-tokens";
import { Pressable, Text as RNText, View } from "react-native";

import { Text } from "./Text.tsx";
import { useColors } from "./theme.ts";

export interface StepperProps {
  // 何の値か
  label: string;
  // 値の意味や、上げ下げしたときの変わり方
  description?: string;
  // 表示する値。単位も含める
  valueText: string;
  // 値の下に小さく添える補足(既定値など)
  hint?: string;
  canDecrement: boolean;
  canIncrement: boolean;
  onDecrement: () => void;
  onIncrement: () => void;
}

function StepButton({
  symbol,
  accessibilityLabel,
  disabled,
  onPress,
}: {
  symbol: string;
  accessibilityLabel: string;
  disabled: boolean;
  onPress: () => void;
}) {
  const palette = useColors();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      hitSlop={spacing.xs}
      style={({ pressed }) => ({
        width: minTouchSize,
        height: minTouchSize,
        alignItems: "center",
        justifyContent: "center",
        borderRadius: radius.md,
        borderWidth: hairline,
        borderColor: palette.borderStrong,
        backgroundColor: pressed ? palette.surfacePressed : "transparent",
        opacity: disabled ? 0.4 : 1,
      })}
    >
      <RNText style={[typography.title, { color: palette.fg }]}>{symbol}</RNText>
    </Pressable>
  );
}

// 刻みのある数を、1 刻みずつ上げ下げする行。ボタンの読み上げに何の値かを含める
export function Stepper({
  label,
  description,
  valueText,
  hint,
  canDecrement,
  canIncrement,
  onDecrement,
  onIncrement,
}: StepperProps) {
  return (
    <View style={{ gap: spacing.sm }}>
      <View style={{ gap: spacing.xs }}>
        <Text variant="label">{label}</Text>
        {description !== undefined && (
          <Text variant="caption" color="fgMuted">
            {description}
          </Text>
        )}
      </View>
      <View style={{ flexDirection: "row", alignItems: "center", gap: spacing.md }}>
        <StepButton
          symbol="−"
          accessibilityLabel={`${label}を下げる`}
          disabled={!canDecrement}
          onPress={onDecrement}
        />
        <View style={{ flex: 1, alignItems: "center" }}>
          <Text
            variant="body"
            accessibilityLabel={`${label} ${valueText}`}
            accessibilityLiveRegion="polite"
          >
            {valueText}
          </Text>
          {hint !== undefined && (
            <Text variant="caption" color="fgMuted">
              {hint}
            </Text>
          )}
        </View>
        <StepButton
          symbol="+"
          accessibilityLabel={`${label}を上げる`}
          disabled={!canIncrement}
          onPress={onIncrement}
        />
      </View>
    </View>
  );
}
