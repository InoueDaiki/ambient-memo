import type { ModelDownloadStatus } from "@ambient-memo/engine-client";
import type {
  UseModelDownloadResult,
  UseOrganizerModelResult,
} from "@ambient-memo/engine-client/react";
import { spacing } from "@ambient-memo/design-tokens";
import { Button, ProgressBar, StatusBanner, Text } from "@ambient-memo/ui-native";
import { View } from "react-native";

import { formatBytes } from "./format.ts";

type RunningStatus = Extract<ModelDownloadStatus, { state: "running" }>;
type FailedStatus = Extract<ModelDownloadStatus, { state: "failed" }>;

function failureMessage(status: FailedStatus): string {
  switch (status.failure) {
    case "network":
      return "通信が切れたため、取得が止まりました。";
    case "corrupted":
      return "取得したファイルが正しくなかったため、消しました。";
    case "insufficient-storage":
      return status.shortageBytes > 0
        ? `空き容量が足りません。あと ${formatBytes(status.shortageBytes)} 空けてください。`
        : "空き容量が足りません。";
    case "unknown":
      return "モデルを取得できませんでした。";
  }
}

function runningMessage(status: RunningStatus): string {
  if (status.totalBytes === 0) return "取得の準備をしています";
  switch (status.phase) {
    case "downloading":
      return `取得中 ${formatBytes(status.completedBytes)} / ${formatBytes(status.totalBytes)}`;
    case "verifying":
      return "正しいファイルか確かめています";
    case "extracting":
      return "モデルを取り出しています";
  }
}

function guide(status: ModelDownloadStatus): string {
  if (status.state === "running" || status.state === "installed") return "";
  if (status.downloadedBytes > 0) {
    return `${formatBytes(status.downloadedBytes)} / ${formatBytes(status.totalBytes)} まで取得済みです。続きから取得できます。`;
  }
  return `聞き取りにはモデルの取得が必要です。約 ${formatBytes(status.totalBytes)} を通信します。通信するのはモデルの取得だけで、音声や文字起こしは送りません。`;
}

// モデルがないときの案内と、取得の進み具合
export function ModelDownloadNotice({ status }: { status: ModelDownloadStatus | undefined }) {
  return (
    <View style={{ gap: spacing.sm }}>
      <StatusBanner tone="neutral" message="文字起こしのモデルが端末にありません。" />
      {status?.state === "failed" && (
        <StatusBanner tone="danger" message={failureMessage(status)} />
      )}
      {status?.state === "running" && (
        <>
          <ProgressBar
            label="モデルの取得"
            value={status.totalBytes > 0 ? status.completedBytes / status.totalBytes : 0}
          />
          <Text variant="caption" color="fgMuted">
            {runningMessage(status)}
          </Text>
        </>
      )}
      {status !== undefined && guide(status) !== "" && (
        <Text variant="caption" color="fgMuted">
          {guide(status)}
        </Text>
      )}
    </View>
  );
}

function organizerGuide(status: ModelDownloadStatus): string {
  if (status.state === "running" || status.state === "installed") return "";
  if (status.downloadedBytes > 0) {
    return `${formatBytes(status.downloadedBytes)} / ${formatBytes(status.totalBytes)} まで取得済みです。続きから取得できます。`;
  }
  return `発話をメモと ToDo に整理するには、別のモデルが必要です。約 ${formatBytes(status.totalBytes)} を通信するので、Wi-Fi をおすすめします。なくても聞き取りはできます。`;
}

// 整理のモデルの案内。聞き取りとは別に、利用者が取得するかを選ぶ。取得済みや取得できないときは何も出さない
export function OrganizerModelSection({ status, start, cancel }: UseOrganizerModelResult) {
  if (status === undefined || status.state === "installed" || status.state === "unavailable") {
    return null;
  }
  const canResume = status.state !== "running" && status.downloadedBytes > 0;
  return (
    <View style={{ gap: spacing.sm }}>
      {status.state === "failed" && <StatusBanner tone="danger" message={failureMessage(status)} />}
      {status.state === "running" && (
        <>
          <ProgressBar
            label="整理のモデルの取得"
            value={status.totalBytes > 0 ? status.completedBytes / status.totalBytes : 0}
          />
          <Text variant="caption" color="fgMuted">
            {runningMessage(status)}
          </Text>
        </>
      )}
      {organizerGuide(status) !== "" && (
        <Text variant="caption" color="fgMuted">
          {organizerGuide(status)}
        </Text>
      )}
      {status.state === "running" ? (
        <Button label="取得を止める" variant="secondary" onPress={cancel} />
      ) : (
        <Button
          label={canResume ? "整理のモデルを続きから取得する" : "整理のモデルを取得する"}
          variant="secondary"
          onPress={start}
        />
      )}
    </View>
  );
}

// 画面の下の操作。モデルがない間は、聞き取りの開始の代わりに出す
export function ModelDownloadButton({ status, start, cancel }: UseModelDownloadResult) {
  if (status?.state === "running") {
    return <Button label="取得を止める" variant="secondary" onPress={cancel} />;
  }
  const canResume =
    status !== undefined && "downloadedBytes" in status && status.downloadedBytes > 0;
  return (
    <Button
      label={canResume ? "続きから取得する" : "モデルを取得する"}
      disabled={status === undefined}
      onPress={start}
    />
  );
}
