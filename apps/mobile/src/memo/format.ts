import type { Memo } from "@ambient-memo/engine-client";

import { formatDay, formatTime } from "../transcript/format.ts";

export const UNTITLED_MEMO = "要約できなかったメモ";

// 同じ日のメモは日付を繰り返さない。日付をまたぐメモは終わりの日付も出す
export function formatMemoPeriod({ startedAt, endedAt }: Pick<Memo, "startedAt" | "endedAt">) {
  const startDay = formatDay(startedAt);
  const endDay = formatDay(endedAt);
  const start = `${startDay} ${formatTime(startedAt)}`;
  return startDay === endDay
    ? `${start} – ${formatTime(endedAt)}`
    : `${start} – ${endDay} ${formatTime(endedAt)}`;
}
