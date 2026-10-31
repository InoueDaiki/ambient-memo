import { useListening, useUtterances } from "@ambient-memo/engine-client/react";
import type { ListeningStatus, Utterance } from "@ambient-memo/engine-client";
import { spacing } from "@ambient-memo/design-tokens";
import {
  Button,
  EmptyState,
  ListItem,
  Screen,
  StatusBanner,
  Text,
  type StatusBannerTone,
} from "@ambient-memo/ui-native";
import { FlatList, View } from "react-native";

import { formatDay, formatTime, speakerLabels } from "./format.ts";

// 一度に読み込む発話の数。古い発話を遡る操作を作るときに見直す
const UTTERANCE_LIMIT = 200;

function listeningBanner(status: ListeningStatus | undefined): {
  tone: StatusBannerTone;
  message: string;
} {
  switch (status) {
    case "listening":
      return { tone: "listening", message: "聞き取り中" };
    case "stopped":
      return { tone: "neutral", message: "止まっています" };
    default:
      return { tone: "neutral", message: "確認しています" };
  }
}

function emptyDescription(status: ListeningStatus | undefined): string {
  return status === "listening"
    ? "話し始めると、ここに文字起こしが並びます。"
    : "聞き取りを始めると、ここに文字起こしが並びます。\n音声は保存しません。";
}

function UtteranceRow({ item, older }: { item: Utterance; older: Utterance | undefined }) {
  const day = formatDay(item.startedAt);
  const isNewDay = older === undefined || formatDay(older.startedAt) !== day;
  return (
    <View>
      {isNewDay && (
        <View style={{ paddingHorizontal: spacing.lg, paddingTop: spacing.md }}>
          <Text variant="caption" color="fgMuted" accessibilityRole="header">
            {day}
          </Text>
        </View>
      )}
      <ListItem
        text={item.text}
        label={speakerLabels[item.speaker]}
        meta={formatTime(item.startedAt)}
      />
    </View>
  );
}

export function TranscriptScreen() {
  const listening = useListening();
  const { utterances, error: loadError } = useUtterances({ limit: UTTERANCE_LIMIT });
  const { status, isChanging, start, stop } = listening;
  const isListening = status === "listening";

  return (
    <Screen>
      <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md, paddingBottom: spacing.sm }}>
        <Text variant="title" accessibilityRole="header">
          文字起こし
        </Text>
        <StatusBanner {...listeningBanner(status)} />
        {listening.error !== undefined && (
          <StatusBanner
            tone="danger"
            message={
              status === "listening"
                ? "聞き取りを止められませんでした。もう一度試してください。"
                : "聞き取りを始められませんでした。もう一度試してください。"
            }
          />
        )}
        {loadError !== undefined && (
          <StatusBanner tone="danger" message="文字起こしを読み込めませんでした。" />
        )}
      </View>

      <View style={{ flex: 1 }}>
        {utterances?.length === 0 ? (
          <EmptyState title="まだ発話はありません" description={emptyDescription(status)} />
        ) : (
          // inverted: 古い発話を上、新しい発話を下に並べ、最新の位置から読み始める
          <FlatList
            inverted
            data={utterances}
            keyExtractor={(item) => item.id}
            renderItem={({ item, index }) => (
              <UtteranceRow item={item} older={utterances?.[index + 1]} />
            )}
          />
        )}
      </View>

      <View style={{ paddingHorizontal: spacing.lg, paddingTop: spacing.sm }}>
        <Button
          label={isListening ? "聞き取りを止める" : "聞き取りを始める"}
          variant={isListening ? "secondary" : "primary"}
          disabled={status === undefined || isChanging}
          onPress={isListening ? stop : start}
        />
      </View>
    </Screen>
  );
}
