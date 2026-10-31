import {
  useListening,
  usePermissions,
  useTranscriptionMode,
  useUtterances,
} from "@ambient-memo/engine-client/react";
import {
  EngineStartError,
  type ListeningStatus,
  type Utterance,
} from "@ambient-memo/engine-client";
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

function listeningErrorMessage(status: ListeningStatus | undefined, error: unknown): string {
  if (status === "listening") return "聞き取りを止められませんでした。もう一度試してください。";
  if (error instanceof EngineStartError) {
    switch (error.failure) {
      case "microphone-permission":
        return "聞き取りにはマイクの許可が必要です。設定で許可してください。";
      case "microphone-unavailable":
        return "マイクを開けませんでした。ほかのアプリが使っていないか確かめてください。";
      case "transcription-unavailable":
        return "文字起こしの機能がまだ入っていないため、聞き取りを始められません。";
    }
  }
  return "聞き取りを始められませんでした。もう一度試してください。";
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
  const { permissions, openSettings } = usePermissions();
  const transcriptionMode = useTranscriptionMode();
  const { status, isChanging, start, stop } = listening;
  const isListening = status === "listening";
  // 開始を試すと OS の確認が出るので、断られた後にだけ設定へ案内する
  const needsMicrophoneGuide =
    permissions?.microphone === false &&
    listening.error instanceof EngineStartError &&
    listening.error.failure === "microphone-permission";

  return (
    <Screen>
      <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md, paddingBottom: spacing.sm }}>
        <Text variant="title" accessibilityRole="header">
          文字起こし
        </Text>
        {transcriptionMode === "development" && (
          <StatusBanner tone="neutral" message="開発用: 文字起こしは本物ではありません" />
        )}
        <StatusBanner {...listeningBanner(status)} />
        {listening.interrupted && (
          <StatusBanner
            tone="danger"
            message="マイクを使えなくなったため、聞き取りが止まりました。"
          />
        )}
        {listening.error !== undefined && (
          <StatusBanner tone="danger" message={listeningErrorMessage(status, listening.error)} />
        )}
        {needsMicrophoneGuide && (
          <Button label="設定を開く" variant="secondary" onPress={openSettings} />
        )}
        {/* 開始のときに OS の確認が出るので、聞き取りを始めた後にだけ案内する */}
        {permissions?.notifications === false && isListening && (
          <>
            <StatusBanner
              tone="neutral"
              message="通知が許可されていません。聞き取り中の表示と、再起動後のお知らせが出ません。"
            />
            {!needsMicrophoneGuide && (
              <Button label="設定を開く" variant="secondary" onPress={openSettings} />
            )}
          </>
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
