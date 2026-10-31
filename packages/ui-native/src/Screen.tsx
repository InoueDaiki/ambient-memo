import { spacing } from "@ambient-memo/design-tokens";
import type { ReactNode } from "react";
import { StatusBar, useColorScheme, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { useColors } from "./theme.ts";

// 画面の地。システムバーの下に潜らないよう、端末の安全領域の分だけ内側に置く。
// 左右の余白は持たない(一覧を端までスクロールさせるため、中身の側で持つ)
// bottomInset は、下に TabBar があるときに false にする(TabBar が安全領域を持つ)
export function Screen({
  children,
  bottomInset = true,
}: {
  children: ReactNode;
  bottomInset?: boolean;
}) {
  const palette = useColors();
  const scheme = useColorScheme();
  const insets = useSafeAreaInsets();
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: palette.canvas,
        paddingTop: insets.top + spacing.sm,
        paddingBottom: (bottomInset ? insets.bottom : 0) + spacing.md,
        paddingLeft: insets.left,
        paddingRight: insets.right,
      }}
    >
      <StatusBar barStyle={scheme === "dark" ? "light-content" : "dark-content"} />
      {children}
    </View>
  );
}
