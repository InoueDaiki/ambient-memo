import { spacing } from "@ambient-memo/design-tokens";
import type { OrganizeResult, OrganizeStatus } from "@ambient-memo/engine-client";
import {
  useMemoMode,
  useOrganize,
  useOrganizerModel,
  type UseOrganizeResult,
} from "@ambient-memo/engine-client/react";
import { Button, ProgressBar, StatusBanner, Text } from "@ambient-memo/ui-native";
import { View } from "react-native";

import { OrganizerModelSection } from "../transcript/ModelDownload.tsx";

const resultBanners: Record<
  Exclude<OrganizeResult, "model-unavailable">,
  { tone: "neutral" | "danger"; message: string }
> = {
  completed: { tone: "neutral", message: "整理が終わりました。" },
  stopped: { tone: "neutral", message: "整理を止めました。残りは次の整理で続きから処理します。" },
  "too-hot": {
    tone: "danger",
    message: "端末が熱くなったので整理を止めました。冷めてからもう一度試してください。",
  },
  failed: {
    tone: "danger",
    message: "整理を続けられませんでした。聞き取りには影響ありません。もう一度試してください。",
  },
  "time-limit": {
    tone: "neutral",
    message: "長く動いたので整理を止めました。もう一度押すと続きから処理します。",
  },
};

function ResultBanner({ result }: { result: OrganizeResult | null }) {
  if (result === null || result === "model-unavailable") return null;
  return <StatusBanner {...resultBanners[result]} />;
}

function RunningSection({ status, stop }: { status: OrganizeStatus; stop: () => void }) {
  const isStopping = status.state === "stopping";
  const hasTotal = status.totalChunks > 0;
  return (
    <View style={{ gap: spacing.sm }}>
      <Text variant="label">
        {hasTotal
          ? `整理しています(${status.completedChunks} / ${status.totalChunks} まとまり)`
          : "整理の準備をしています"}
      </Text>
      {hasTotal && (
        <ProgressBar label="整理の進み具合" value={status.completedChunks / status.totalChunks} />
      )}
      <Text variant="caption" color="fgMuted">
        {isStopping
          ? "区切りがつくと止まります。"
          : "アプリを離れても続きます。聞き取りはそのまま動きます。"}
      </Text>
      <Button
        label={isStopping ? "止めています" : "止める"}
        variant="secondary"
        onPress={stop}
        disabled={isStopping}
      />
    </View>
  );
}

function idleGuide({ pendingChunks, isWaitingForSilence }: OrganizeStatus): string {
  if (pendingChunks > 0) {
    return `未処理のまとまり: ${pendingChunks}。充電していなくても動きますが、電池を多く使います。端末が熱くなると止まります。`;
  }
  if (isWaitingForSilence) {
    return "話が途切れて 5 分たつと、直近の発話を整理できます。";
  }
  return "未処理の発話はありません。";
}

function IdleSection({
  status,
  startResult,
  start,
}: {
  status: OrganizeStatus;
  startResult: UseOrganizeResult["startResult"];
  start: () => void;
}) {
  const hasNothing = status.pendingChunks === 0 && !status.isWaitingForSilence;
  return (
    <View style={{ gap: spacing.sm }}>
      <ResultBanner result={status.lastResult} />
      {startResult === "failed" && status.lastResult === null && (
        <StatusBanner tone="danger" message="整理を始められませんでした。" />
      )}
      <Text variant="caption" color="fgMuted">
        {idleGuide(status)}
      </Text>
      <Button label="今すぐ整理する" variant="secondary" onPress={start} disabled={hasNothing} />
    </View>
  );
}

// 充電を待たずに整理する操作。始めるのは利用者の操作だけ
export function OrganizeNow() {
  const mode = useMemoMode();
  const { status, startResult, start, stop } = useOrganize();
  const organizerModel = useOrganizerModel();

  if (mode === "unavailable" || status === undefined) return null;
  if (status.state !== "idle") return <RunningSection status={status} stop={stop} />;
  if (organizerModel.status === undefined) return null;
  if (organizerModel.status.state !== "installed") {
    return <OrganizerModelSection {...organizerModel} />;
  }
  return <IdleSection status={status} startResult={startResult} start={start} />;
}
