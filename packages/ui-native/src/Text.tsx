import { typography, type ColorName, type TypographyName } from "@ambient-memo/design-tokens";
import { Text as RNText, type TextProps as RNTextProps } from "react-native";

import { useColors } from "./theme.ts";

export interface TextProps extends Omit<RNTextProps, "style"> {
  variant?: TypographyName;
  color?: Extract<ColorName, "fg" | "fgMuted" | "onPrimary" | "listening" | "danger">;
  align?: "left" | "center";
}

export function Text({ variant = "body", color = "fg", align = "left", ...props }: TextProps) {
  const palette = useColors();
  return (
    <RNText style={[typography[variant], { color: palette[color], textAlign: align }]} {...props} />
  );
}
