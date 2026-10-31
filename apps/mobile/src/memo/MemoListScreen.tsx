import { spacing } from "@ambient-memo/design-tokens";
import { useMemos } from "@ambient-memo/engine-client/react";
import { EmptyState, ListItem, Screen, StatusBanner, Text } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { FlatList, View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";
import { formatMemoPeriod, UNTITLED_MEMO } from "./format.ts";
import { MemoModeBanner } from "./MemoModeBanner.tsx";

// 一度に読み込むメモの数。古いメモを遡る操作を作るときに見直す
export const MEMO_LIMIT = 200;

export function MemoListScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { memos, error } = useMemos({ limit: MEMO_LIMIT });

  return (
    <Screen bottomInset={false}>
      <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md, paddingBottom: spacing.sm }}>
        <Text variant="title" accessibilityRole="header">
          メモ
        </Text>
        <MemoModeBanner />
        {error !== undefined && (
          <StatusBanner tone="danger" message="メモを読み込めませんでした。" />
        )}
      </View>

      <View style={{ flex: 1 }}>
        {memos?.length === 0 ? (
          <EmptyState
            title="まだメモはありません"
            description="文字起こしが整理されると、ここにメモが並びます。"
          />
        ) : (
          <FlatList
            data={memos}
            keyExtractor={(item) => item.id}
            renderItem={({ item }) => (
              <ListItem
                text={item.title ?? UNTITLED_MEMO}
                label={formatMemoPeriod(item)}
                tag={item.todoExtraction === "incomplete" ? "ToDo が欠けているかも" : undefined}
                onPress={() => navigation.navigate("MemoDetail", { memoId: item.id })}
              />
            )}
          />
        )}
      </View>
    </Screen>
  );
}
