// 画面が使うエンジンの実装を決める場所
import { createEngineClient, createMemoEngineClient } from "@ambient-memo/engine-bridge";
import type { MemoEngineClient, TranscriptEngineClient } from "@ambient-memo/engine-client";

export function createAppEngineClient(): TranscriptEngineClient {
  return createEngineClient();
}

export function createAppMemoClient(): MemoEngineClient {
  return createMemoEngineClient();
}
