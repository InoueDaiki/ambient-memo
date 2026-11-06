import { useMemoMode } from "@ambient-memo/engine-client/react";
import { StatusBanner } from "@ambient-memo/ui-native";

export function MemoModeBanner() {
  const mode = useMemoMode();
  if (mode === "development") {
    return <StatusBanner tone="neutral" message="開発用: メモは本物ではありません" />;
  }
  if (mode === "unavailable") {
    return <StatusBanner tone="neutral" message="メモの機能はまだ入っていません。" />;
  }
  return null;
}
