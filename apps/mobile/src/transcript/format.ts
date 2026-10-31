import type { Speaker } from "@ambient-memo/engine-client";

export const speakerLabels: Record<Speaker, string> = {
  self: "自分",
  other: "それ以外",
  unknown: "不明",
};

const weekdays = ["日", "月", "火", "水", "木", "金", "土"];

// 端末の時刻で表示する
export function formatTime(timestamp: number): string {
  const date = new Date(timestamp);
  const minutes = String(date.getMinutes()).padStart(2, "0");
  return `${date.getHours()}:${minutes}`;
}

export function formatDay(timestamp: number): string {
  const date = new Date(timestamp);
  return `${date.getMonth() + 1}月${date.getDate()}日(${weekdays[date.getDay()]})`;
}
