import type { Memo, Todo, TodoKind } from "@ambient-memo/engine-client";

import { formatDay, formatTime } from "../transcript/format.ts";

export const UNTITLED_MEMO = "要約できなかったメモ";

export const todoKindLabels: Record<TodoKind, string> = {
  todo: "ToDo",
  reminder: "予定",
};

// 同じ日のメモは日付を繰り返さない。日付をまたぐメモは終わりの日付も出す
export function formatMemoPeriod({ startedAt, endedAt }: Pick<Memo, "startedAt" | "endedAt">) {
  const startDay = formatDay(startedAt);
  const endDay = formatDay(endedAt);
  const start = `${startDay} ${formatTime(startedAt)}`;
  return startDay === endDay
    ? `${start} – ${formatTime(endedAt)}`
    : `${start} – ${endDay} ${formatTime(endedAt)}`;
}

// YYYY-MM-DD は時差を持たない日付。new Date に文字列のまま渡すと UTC とみなされて、日付がずれる
export function formatDueDate(dueDate: string): string {
  const [year = 0, month = 1, day = 1] = dueDate.split("-").map(Number);
  return formatDay(new Date(year, month - 1, day).getTime());
}

// 確定した期限。候補の期限と見分けられるよう、候補には使わない
export function confirmedDue({ dueDate, status }: Todo): string | undefined {
  return status === "open" && dueDate !== null ? `期限 ${formatDueDate(dueDate)}` : undefined;
}

// 候補の ToDo の札。日付を捨てた候補には dueDate がない
export function candidateTag({ dueDate, status }: Todo): string | undefined {
  if (status !== "candidate") return undefined;
  return dueDate === null ? "期限は未確定" : `期限の候補 ${formatDueDate(dueDate)}`;
}
