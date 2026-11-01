import { minTouchSize, spacing } from "@ambient-memo/design-tokens";
import { type ReactNode, useState } from "react";
import { Pressable } from "react-native";

import { Text } from "./Text.tsx";

export interface DisclosureProps {
  title: string;
  children: ReactNode;
  // 最初から開いておく
  defaultOpen?: boolean;
}

// 見出しを押して開け閉めする欄。閉じている間は中身を描画しない
export function Disclosure({ title, children, defaultOpen = false }: DisclosureProps) {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  return (
    <>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={title}
        accessibilityState={{ expanded: isOpen }}
        onPress={() => setIsOpen(!isOpen)}
        style={{
          minHeight: minTouchSize,
          flexDirection: "row",
          alignItems: "center",
          gap: spacing.sm,
          paddingHorizontal: spacing.lg,
          marginTop: spacing.sm,
        }}
      >
        <Text variant="label" color="fgMuted">
          {isOpen ? "▾" : "▸"}
        </Text>
        <Text variant="label" color="fgMuted">
          {title}
        </Text>
      </Pressable>
      {isOpen && children}
    </>
  );
}
