import { hairline, minTouchSize, radius, spacing } from "@ambient-memo/design-tokens";
import { Pressable, View } from "react-native";

import { ListItem, type ListItemProps } from "./ListItem.tsx";
import { useColors } from "./theme.ts";

export interface CheckItemProps extends Omit<ListItemProps, "muted"> {
  checked: boolean;
  // 読み上げで使う、印の操作の説明
  checkLabel: string;
  onCheck: () => void;
  disabled?: boolean;
}

const MARK_SIZE = 22;
const MARK_INNER_SIZE = 12;

// 印の付いた行。印と本文は別の押す場所にする
export function CheckItem({
  checked,
  checkLabel,
  onCheck,
  disabled = false,
  ...item
}: CheckItemProps) {
  const palette = useColors();
  return (
    <View style={{ flexDirection: "row", alignItems: "center", paddingLeft: spacing.md }}>
      <Pressable
        accessibilityRole="checkbox"
        accessibilityLabel={checkLabel}
        accessibilityState={{ checked, disabled }}
        disabled={disabled}
        onPress={onCheck}
        style={{
          width: minTouchSize,
          height: minTouchSize,
          alignItems: "center",
          justifyContent: "center",
          opacity: disabled && !checked ? 0.5 : 1,
        }}
      >
        <View
          style={{
            width: MARK_SIZE,
            height: MARK_SIZE,
            alignItems: "center",
            justifyContent: "center",
            borderRadius: radius.full,
            borderWidth: hairline,
            borderColor: palette.fgMuted,
          }}
        >
          {checked && (
            <View
              style={{
                width: MARK_INNER_SIZE,
                height: MARK_INNER_SIZE,
                borderRadius: radius.full,
                backgroundColor: palette.fgMuted,
              }}
            />
          )}
        </View>
      </Pressable>
      <View style={{ flex: 1 }}>
        <ListItem {...item} muted={checked} />
      </View>
    </View>
  );
}
