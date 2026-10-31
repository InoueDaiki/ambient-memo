import { spacing } from "@ambient-memo/design-tokens";
import type { ReactNode } from "react";
import { View } from "react-native";

// 雛形。画面の余白だけを持つ
export function Screen({ children }: { children: ReactNode }) {
  return (
    <View style={{ flex: 1, alignItems: "center", justifyContent: "center", padding: spacing.md }}>
      {children}
    </View>
  );
}
