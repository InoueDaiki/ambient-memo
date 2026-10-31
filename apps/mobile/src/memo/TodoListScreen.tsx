import { spacing } from "@ambient-memo/design-tokens";
import type { Todo } from "@ambient-memo/engine-client";
import { useCompleteTodo, useMemos, useTodos } from "@ambient-memo/engine-client/react";
import { EmptyState, Screen, StatusBanner, Text } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { SectionList, View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";
import { MEMO_LIMIT } from "./MemoListScreen.tsx";
import { MemoModeBanner } from "./MemoModeBanner.tsx";
import { TodoRow } from "./TodoRow.tsx";

// 一度に読み込む ToDo の数。未完了が先に並ぶので、溢れるのは古い完了済みから
const TODO_LIMIT = 200;

function toSections(todos: Todo[]) {
  const sections = [
    { title: "これから", data: todos.filter((todo) => todo.status !== "done") },
    { title: "完了", data: todos.filter((todo) => todo.status === "done") },
  ];
  return sections.filter((section) => section.data.length > 0);
}

export function TodoListScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { todos, error: loadError } = useTodos({ limit: TODO_LIMIT });
  const { memos } = useMemos({ limit: MEMO_LIMIT });
  const { complete, pendingIds, error: completeError } = useCompleteTodo();
  const hasIncompleteMemo = memos?.some((memo) => memo.todoExtraction === "incomplete") === true;

  return (
    <Screen bottomInset={false}>
      <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md, paddingBottom: spacing.sm }}>
        <Text variant="title" accessibilityRole="header">
          ToDo
        </Text>
        <MemoModeBanner />
        {hasIncompleteMemo && (
          <StatusBanner
            tone="neutral"
            message="ToDo を読み取りきれなかったメモがあります。欠けているものがあるかもしれません。"
          />
        )}
        {loadError !== undefined && (
          <StatusBanner tone="danger" message="ToDo を読み込めませんでした。" />
        )}
        {completeError !== undefined && (
          <StatusBanner
            tone="danger"
            message="ToDo を完了にできませんでした。もう一度試してください。"
          />
        )}
      </View>

      <View style={{ flex: 1 }}>
        {todos?.length === 0 ? (
          <EmptyState
            title="まだ ToDo はありません"
            description="メモから ToDo が見つかると、ここに並びます。"
          />
        ) : (
          <SectionList
            sections={toSections(todos ?? [])}
            keyExtractor={(item) => item.id}
            renderSectionHeader={({ section }) => (
              <View style={{ paddingHorizontal: spacing.lg, paddingTop: spacing.md }}>
                <Text variant="caption" color="fgMuted" accessibilityRole="header">
                  {section.title}
                </Text>
              </View>
            )}
            renderItem={({ item }) => (
              <TodoRow
                todo={item}
                isCompleting={pendingIds.has(item.id)}
                onComplete={complete}
                onOpenMemo={(memoId) => navigation.navigate("MemoDetail", { memoId })}
              />
            )}
          />
        )}
      </View>
    </Screen>
  );
}
