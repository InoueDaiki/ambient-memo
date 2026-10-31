import { spacing } from "@ambient-memo/design-tokens";
import { Pressable, View } from "react-native";

import { Tag } from "./Tag.tsx";
import { Text } from "./Text.tsx";
import { useColors } from "./theme.ts";

export interface ListItemProps {
  // 本文。一覧の主役
  text: string;
  // 本文の上に小さく添える補足(話者など)
  label: string;
  // label の右に並べる補足(時刻など)
  meta?: string;
  // meta の右に並べる、状態を伝える短い文
  tag?: string;
  // 済んだ項目など、本文を控えめに出す
  muted?: boolean;
  // 渡すと行全体がボタンになる
  onPress?: () => void;
}

// 読むための行。枠や線を持たず、余白で区切る
export function ListItem({ text, label, meta, tag, muted = false, onPress }: ListItemProps) {
  const palette = useColors();
  const style = { paddingHorizontal: spacing.lg, paddingVertical: spacing.md - spacing.xs };
  const content = (
    <>
      <View
        style={{
          flexDirection: "row",
          flexWrap: "wrap",
          alignItems: "center",
          gap: spacing.sm,
          marginBottom: spacing.xs,
        }}
      >
        <Text variant="caption" color="fgMuted">
          {label}
        </Text>
        {meta !== undefined && (
          <Text variant="caption" color="fgMuted">
            {meta}
          </Text>
        )}
        {tag !== undefined && <Tag label={tag} />}
      </View>
      {/* 押せる行では、長押しの選択が押す操作とぶつかる */}
      <Text selectable={onPress === undefined} color={muted ? "fgMuted" : "fg"}>
        {text}
      </Text>
    </>
  );

  if (onPress === undefined) {
    return (
      <View accessible style={style}>
        {content}
      </View>
    );
  }
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [style, pressed && { backgroundColor: palette.surfacePressed }]}
    >
      {content}
    </Pressable>
  );
}
