import { minTouchSize, radius, spacing, typography } from "@ambient-memo/design-tokens";
import { TextInput } from "react-native";

import { useColors } from "./theme.ts";

export interface TextFieldProps {
  value: string;
  onChangeText: (value: string) => void;
  // 読み上げに使う名前
  label: string;
  placeholder?: string;
}

// 1 行の入力欄
export function TextField({ value, onChangeText, label, placeholder }: TextFieldProps) {
  const palette = useColors();
  return (
    <TextInput
      accessibilityLabel={label}
      value={value}
      onChangeText={onChangeText}
      placeholder={placeholder}
      placeholderTextColor={palette.fgMuted}
      style={[
        typography.label,
        {
          minHeight: minTouchSize,
          paddingHorizontal: spacing.md,
          borderRadius: radius.md,
          backgroundColor: palette.surface,
          color: palette.fg,
        },
      ]}
    />
  );
}
