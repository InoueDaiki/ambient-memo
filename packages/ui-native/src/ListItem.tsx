import { spacing } from "@ambient-memo/design-tokens";
import { Pressable, View } from "react-native";

import { Avatar } from "./Avatar.tsx";
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
  // 行の頭に添える丸い印(話者など)
  avatar?: string;
  // 右に寄せる(自分の発話など)。印は右端に置く
  alignEnd?: boolean;
  // 渡すと行全体がボタンになる
  onPress?: () => void;
}

// 読むための行。枠や線を持たず、余白で区切る
export function ListItem({
  text,
  label,
  meta,
  tag,
  muted = false,
  avatar,
  alignEnd = false,
  onPress,
}: ListItemProps) {
  const palette = useColors();
  const style = { paddingHorizontal: spacing.lg, paddingVertical: spacing.md - spacing.xs };
  const body = (
    // 右に寄せるときは、短い発話が右端に付くよう幅を文に合わせる。折り返す行は左揃えのまま読む
    <View style={{ flexShrink: 1, alignItems: alignEnd ? "flex-end" : "stretch" }}>
      <View
        style={{
          flexDirection: alignEnd ? "row-reverse" : "row",
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
    </View>
  );
  const content =
    avatar === undefined && !alignEnd ? (
      body
    ) : (
      <View
        style={{
          flexDirection: alignEnd ? "row-reverse" : "row",
          alignItems: "flex-start",
          gap: spacing.sm,
        }}
      >
        {avatar !== undefined && <Avatar label={avatar} />}
        {body}
      </View>
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
