// 画面が使うエンジンの実装を決める場所
import { createEngineClient } from "@ambient-memo/engine-bridge";
import {
  FakeEngineClient,
  sampleMemos,
  type MemoEngineClient,
  type TranscriptEngineClient,
} from "@ambient-memo/engine-client";

export function createAppEngineClient(): TranscriptEngineClient {
  return createEngineClient();
}

// engine-bridge にメモと ToDo がつながるまでは Fake を使う。
// 架空の見本は開発中だけ出し、Release には何も出さない
export function createAppMemoClient(): MemoEngineClient {
  return __DEV__
    ? new FakeEngineClient({ memoMode: "development", memos: sampleMemos })
    : new FakeEngineClient({ memoMode: "unavailable" });
}
