import { hairline, minTouchSize, spacing, typography } from "@ambient-memo/design-tokens";
import { Pressable, Text as RNText, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { useColors } from "./theme.ts";

export interface TabBarItem {
  key: string;
  label: string;
}

export interface TabBarProps {
  items: readonly TabBarItem[];
  activeKey: string;
  onSelect: (key: string) => void;
}

// 画面の下の切り替え。今の項目は太さと下線でも分かるようにする
export function TabBar({ items, activeKey, onSelect }: TabBarProps) {
  const palette = useColors();
  const insets = useSafeAreaInsets();
  return (
    <View
      accessibilityRole="tablist"
      style={{
        flexDirection: "row",
        backgroundColor: palette.canvas,
        borderTopWidth: hairline,
        borderTopColor: palette.border,
        paddingBottom: insets.bottom,
        paddingLeft: insets.left,
        paddingRight: insets.right,
      }}
    >
      {items.map(({ key, label }) => {
        const selected = key === activeKey;
        return (
          <Pressable
            key={key}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            onPress={() => onSelect(key)}
            style={({ pressed }) => ({
              flex: 1,
              minHeight: minTouchSize + spacing.sm,
              alignItems: "center",
              justifyContent: "center",
              backgroundColor: pressed ? palette.surfacePressed : palette.canvas,
            })}
          >
            <RNText
              style={[
                typography.label,
                {
                  color: selected ? palette.fg : palette.fgMuted,
                  fontWeight: selected ? "600" : "400",
                  textDecorationLine: selected ? "underline" : "none",
                },
              ]}
            >
              {label}
            </RNText>
          </Pressable>
        );
      })}
    </View>
  );
}
