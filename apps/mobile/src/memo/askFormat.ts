import type { AskPhase, MemoAnswerOutcome } from "@ambient-memo/engine-client";
import type { AskedQuestion } from "@ambient-memo/engine-client/react";

// 最初の段階が届くまでは、モデルを読み込んでいるものとして出す
export const phaseMessages: Record<AskPhase, string> = {
  preparing: "モデルを読み込んでいます",
  searching: "メモを探しています",
  answering: "答えを作っています",
};

export const outcomeBanners: Record<
  Exclude<MemoAnswerOutcome, "answered">,
  { tone: "neutral" | "danger"; message: string }
> = {
  "not-found": {
    tone: "neutral",
    message: "関係するメモが見つかりませんでした。言葉を変えて聞いてみてください。",
  },
  busy: {
    tone: "neutral",
    message:
      "整理か別の質問が動いているので、答えられませんでした。終わってからもう一度試してください。",
  },
  "model-unavailable": {
    tone: "neutral",
    message: "整理のモデルがないので答えられませんでした。上の案内から取得してください。",
  },
  failed: { tone: "danger", message: "答えを作れませんでした。もう一度試してください。" },
};

const outcomeLabels: Record<MemoAnswerOutcome, string> = {
  answered: "答えました",
  "not-found": "見つかりませんでした",
  busy: "答えられませんでした",
  "model-unavailable": "答えられませんでした",
  failed: "答えを作れませんでした",
};

// 答えを待っている質問は、answer も failure もない
export function isPending(item: AskedQuestion): boolean {
  return item.answer === undefined && item.failure === undefined;
}

// 質問の一覧の行に添える、短い状態
export function questionStatus(item: AskedQuestion, phase: AskPhase | undefined): string {
  if (item.answer) return outcomeLabels[item.answer.outcome];
  if (item.failure === "cancelled") return "止めました";
  if (item.failure === "unknown") return outcomeLabels.failed;
  return phaseMessages[phase ?? "preparing"];
}
