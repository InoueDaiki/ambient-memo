import type { TuningItem, TuningKey, TuningProblem } from "@ambient-memo/engine-client";

export interface TuningCopy {
  label: string;
  description: string;
  format: (value: number) => string;
}

const similarity = (value: number) => value.toFixed(2);

// 画面の文言と値の書き方。範囲と刻みはエンジンから受け取る
export const tuningCopy: Record<TuningKey, TuningCopy> = {
  selfThreshold: {
    label: "自分と判定する類似度",
    description:
      "登録した声とこれ以上似ていれば自分にします。下げると自分を他人と取り違えにくくなり、他人を自分としやすくなります。",
    format: similarity,
  },
  selfExitThreshold: {
    label: "自分から外れる類似度",
    description:
      "自分と判定している間は、これを下回るまで自分のままにします。自分と判定する類似度以下にします。",
    format: similarity,
  },
  unknownFloor: {
    label: "不明にする類似度の下限",
    description:
      "この値から自分と判定する類似度までの間は、話者を不明にします。自分から外れる類似度以下にします。",
    format: similarity,
  },
  provisionalThreshold: {
    label: "同じ他人とみなす類似度",
    description:
      "知っている他人とこれ以上似ていれば同じ人にします。下げると他人がまとまりやすく、上げると分かれやすくなります。",
    format: similarity,
  },
  maxProvisionalSpeakers: {
    label: "仮の話者の上限",
    description:
      "「話者N」として作る他人の数の上限です。名前を付けた話者は数えません。上限に達すると、最も近い話者に入れます。",
    format: (value) => `${value} 人`,
  },
  quietGateDbfs: {
    label: "検知を始める音の大きさ",
    description:
      "これより静かな音は発話の検知に回さず、電池を節約します。上げると小さな声や遠い声を聞き逃しやすくなります。",
    format: (value) => `${value} dBFS`,
  },
  vadThreshold: {
    label: "発話とみなすしきい値",
    description: "上げると雑音を発話としにくくなり、小さな声を聞き逃しやすくなります。",
    format: (value) => value.toFixed(2),
  },
  vadMinSilenceMillis: {
    label: "発話を区切る無音の長さ",
    description:
      "この長さの無音で発話を区切ります。短くすると細かく区切り、長くすると息継ぎで切れにくくなります。",
    format: (value) => `${(value / 1000).toFixed(1)} 秒`,
  },
  minTopicLines: {
    label: "話題とみなす最短の行数",
    description:
      "これより短い話題は前の話題に戻します。上げるとメモが細切れになりにくく、別の話が混ざりやすくなります。",
    format: (value) => `${value} 行`,
  },
  chunkGapMinutes: {
    label: "メモを分ける会話の間隔",
    description:
      "会話がこの長さとぎれたら、別のまとまりとして整理します。会話が終わってからこの長さ待って整理します。",
    format: (value) => `${value} 分`,
  },
};

export const tuningSections: { title: string; keys: TuningKey[] }[] = [
  {
    title: "話者の判定",
    keys: [
      "selfThreshold",
      "selfExitThreshold",
      "unknownFloor",
      "provisionalThreshold",
      "maxProvisionalSpeakers",
    ],
  },
  { title: "発話の検知", keys: ["quietGateDbfs", "vadThreshold", "vadMinSilenceMillis"] },
  { title: "メモの整理", keys: ["minTopicLines", "chunkGapMinutes"] },
];

// 1 刻み動かした値。刻みの誤差を消し、範囲に収める。エンジンの丸め方と同じにする
export function stepTuning(item: TuningItem, value: number, direction: 1 | -1): number {
  const steps = Math.round((value - item.min) / item.step) + direction;
  const next = Math.round((item.min + steps * item.step) * 1_000_000) / 1_000_000;
  return Math.min(Math.max(next, item.min), item.max);
}

export function tuningProblemMessage(problem: TuningProblem): string {
  switch (problem) {
    case "self-exit-above-self":
      return "自分から外れる類似度は、自分と判定する類似度以下にしてください。";
    case "unknown-floor-above-self-exit":
      return "不明にする類似度の下限は、自分から外れる類似度以下にしてください。";
    case "out-of-range":
    case "unknown-key":
      return "受け付けられない値があります。";
  }
}
