import { spacing } from "@ambient-memo/design-tokens";
import type { DiagnosticsStatus } from "@ambient-memo/engine-client";
import { useDiagnostics } from "@ambient-memo/engine-client/react";
import { Button, Screen, StatusBanner, Text } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { ScrollView, View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";

const BYTES_PER_KB = 1024;

function formatTime(millis: number): string {
  return new Date(millis).toLocaleString("ja-JP", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function StatusSummary({ status }: { status: DiagnosticsStatus }) {
  const { startedAt, endsAt } = status;
  if (startedAt === undefined || endsAt === undefined) {
    return <Text variant="caption">記録はありません。</Text>;
  }
  const kilobytes = Math.ceil(status.sizeBytes / BYTES_PER_KB);
  return (
    <View style={{ gap: spacing.xs }}>
      <Text accessibilityLiveRegion="polite">
        {status.isRecording ? "記録しています" : "記録を止めています"}
      </Text>
      <Text variant="caption">開始: {formatTime(startedAt)}</Text>
      <Text variant="caption">
        {status.isRecording ? "自動で止まる時刻" : "終了"}: {formatTime(endsAt)}
      </Text>
      <Text variant="caption">大きさ: {kilobytes} KB</Text>
      {status.isFull && (
        <Text variant="caption">上限に達したので、新しい記録は残していません。</Text>
      )}
    </View>
  );
}

// 実機の確認のために、必要な期間だけ数値を記録して書き出す
export function DiagnosticsScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { status, isPending, hasFailed, start, stop, clear, share } = useDiagnostics();
  const hasRecord = status?.startedAt !== undefined;

  return (
    <Screen>
      <ScrollView contentContainerStyle={{ padding: spacing.lg, gap: spacing.lg }}>
        <Button label="戻る" variant="ghost" onPress={() => navigation.goBack()} />
        <Text variant="title" accessibilityRole="header">
          動作の記録
        </Text>
        <Text variant="caption">
          処理の時間、発話の検知の回数、電池、温度、メモリなどの数値を、端末の中に残します。
          発話の本文、音声、話者の名前は含みません。書き出すまで端末の外へは出ません。止めなければ 7
          日で止まります。
        </Text>
        {hasFailed && <StatusBanner tone="danger" message="操作できませんでした" />}
        {status !== undefined && <StatusSummary status={status} />}
        {status?.isRecording === true ? (
          <Button label="記録を止める" variant="primary" disabled={isPending} onPress={stop} />
        ) : (
          <Button
            label={hasRecord ? "前の記録を消して始める" : "記録を始める"}
            variant="primary"
            disabled={isPending}
            onPress={start}
          />
        )}
        {hasRecord && (
          <Button label="書き出す" variant="secondary" disabled={isPending} onPress={share} />
        )}
        {hasRecord && status?.isRecording === false && (
          <Button label="記録を消す" variant="danger" disabled={isPending} onPress={clear} />
        )}
      </ScrollView>
    </Screen>
  );
}
