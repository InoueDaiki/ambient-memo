import { useAskMemos, type UseAskMemosResult } from "@ambient-memo/engine-client/react";
import { createContext, useContext, type ReactNode } from "react";

const AskContext = createContext<UseAskMemosResult | undefined>(undefined);

// 質問の一覧と詳細の画面で、同じ質問と答えを見るために、質問の状態を画面の外に持つ。
// アプリが動いている間だけ残し、保存しない
export function AskProvider({ children }: { children: ReactNode }) {
  const value = useAskMemos();
  return <AskContext.Provider value={value}>{children}</AskContext.Provider>;
}

export function useAsk(): UseAskMemosResult {
  const value = useContext(AskContext);
  if (!value) throw new Error("AskProvider の内側で使う");
  return value;
}
