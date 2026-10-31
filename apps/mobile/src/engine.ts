// 画面が使うエンジンの実装を決める場所
import { createEngineClient } from "@ambient-memo/engine-bridge";
import type { TranscriptEngineClient } from "@ambient-memo/engine-client";

export function createAppEngineClient(): TranscriptEngineClient {
  return createEngineClient();
}
