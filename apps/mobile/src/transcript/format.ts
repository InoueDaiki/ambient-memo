import type { Speaker, Utterance } from "@ambient-memo/engine-client";

export const speakerLabels: Record<Speaker, string> = {
  self: "自分",
  other: "それ以外",
  unknown: "不明",
};

export function speakerLabel(utterance: Pick<Utterance, "speaker" | "speakerName">): string {
  return utterance.speakerName ?? speakerLabels[utterance.speaker];
}

// 仮の話者にエンジンが付ける名前。手で付けた名前と見分けて、番号だけを印にする
const PROVISIONAL_NAME = /^話者(\d+)$/;

// 話者の丸い印に入れる文字。仮の話者は番号、名前を付けた話者は頭文字にする
export function speakerAvatar(utterance: Pick<Utterance, "speaker" | "speakerName">): string {
  const name = utterance.speakerName;
  if (name === undefined) return "?";
  const number = PROVISIONAL_NAME.exec(name)?.[1];
  if (number !== undefined) return number;
  // 絵文字などサロゲートペアの文字を割らない
  return Array.from(name.trim())[0] ?? "?";
}

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

const MEGABYTE = 1_000_000;
const GIGABYTE = 1_000_000_000;

// 通信量と空き容量の目安を出す。端末の設定の画面に合わせて 1000 で割る
export function formatBytes(bytes: number): string {
  if (bytes >= GIGABYTE) return `${(bytes / GIGABYTE).toFixed(1)} GB`;
  return `${Math.ceil(bytes / MEGABYTE)} MB`;
}
