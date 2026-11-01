import { spacing } from "@ambient-memo/design-tokens";
import type { MemoDetail } from "@ambient-memo/engine-client";
import { useCompleteTodo, useMemoDetail } from "@ambient-memo/engine-client/react";
import {
  Button,
  Disclosure,
  EmptyState,
  ListItem,
  Screen,
  StatusBanner,
  Text,
} from "@ambient-memo/ui-native";
import { useNavigation, useRoute } from "@react-navigation/native";
import type { ReactNode } from "react";
import { ScrollView, View } from "react-native";

import type { RootNavigation, RootRoute } from "../navigation/types.ts";
import { formatTime, speakerLabels } from "../transcript/format.ts";
import { formatMemoPeriod, UNTITLED_MEMO } from "./format.ts";
import { MemoModeBanner } from "./MemoModeBanner.tsx";
import { TodoRow } from "./TodoRow.tsx";

function Padded({ children }: { children: ReactNode }) {
  return <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md }}>{children}</View>;
}

function MemoBody({ memo }: { memo: MemoDetail }) {
  const { complete, pendingIds, error } = useCompleteTodo();
  return (
    <ScrollView>
      <Padded>
        <View style={{ gap: spacing.xs }}>
          <Text variant="title" accessibilityRole="header">
            {memo.title ?? UNTITLED_MEMO}
          </Text>
          <Text variant="caption" color="fgMuted">
            {formatMemoPeriod(memo)}
          </Text>
        </View>
        {memo.summary === null ? (
          <StatusBanner
            tone="neutral"
            message="要約を作れませんでした。下の発話から内容を確かめてください。"
          />
        ) : (
          <Text selectable>{memo.summary}</Text>
        )}
        {memo.todoExtraction === "incomplete" && (
          <StatusBanner
            tone="neutral"
            message="ToDo を最後まで読み取れませんでした。欠けているものがあるかもしれません。"
          />
        )}
        {error !== undefined && (
          <StatusBanner
            tone="danger"
            message="ToDo を完了にできませんでした。もう一度試してください。"
          />
        )}
      </Padded>

      {memo.todos.length > 0 && (
        <Disclosure title={`ToDo(${memo.todos.length} 件)`}>
          {memo.todos.map((todo) => (
            <TodoRow
              key={todo.id}
              todo={todo}
              isCompleting={pendingIds.has(todo.id)}
              onComplete={complete}
            />
          ))}
        </Disclosure>
      )}

      {/* 要約がないと発話でしか内容が分からないので、開いておく */}
      <Disclosure
        title={`根拠の発話(${memo.utterances.length} 件)`}
        defaultOpen={memo.summary === null}
      >
        {memo.utterances.map((utterance) => (
          <ListItem
            key={utterance.id}
            text={utterance.text}
            label={utterance.speakerName ?? speakerLabels[utterance.speaker]}
            meta={formatTime(utterance.startedAt)}
          />
        ))}
      </Disclosure>
    </ScrollView>
  );
}

export function MemoDetailScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { params } = useRoute<RootRoute<"MemoDetail">>();
  const { memo, error } = useMemoDetail(params.memoId);

  return (
    <Screen>
      <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md, paddingBottom: spacing.md }}>
        <View style={{ alignSelf: "flex-start" }}>
          <Button label="戻る" variant="ghost" onPress={() => navigation.goBack()} />
        </View>
        <MemoModeBanner />
        {error !== undefined && (
          <StatusBanner tone="danger" message="メモを読み込めませんでした。" />
        )}
      </View>
      {memo === null && (
        <EmptyState
          title="メモが見つかりません"
          description="消えたか、まだ整理されていないメモです。"
        />
      )}
      {memo && <MemoBody memo={memo} />}
    </Screen>
  );
}
