import { FakeEngineClient, type MemoDetail } from "@ambient-memo/engine-client";
import { runMemoClientContract, type OrganizeSetup } from "@ambient-memo/engine-client/contract";
import { describe, expect, it } from "vitest";

import { createMemoClient, type MemoNativeModule } from "./memo-client.ts";

// ネイティブと同じ形を返す。振る舞いの本体は Fake で、ここで確かめるのは JS の変換。
// Kotlin と SQLite の振る舞いは engine/android の MemoBridgeContractTest が同じケースを流す
function nativeModule(memos: MemoDetail[], organize: OrganizeSetup = {}): MemoNativeModule {
  const fake = new FakeEngineClient({
    memos,
    organizeBacklog: {
      pendingChunks: organize.pendingChunks ?? 0,
      isWaitingForSilence: organize.isWaitingForSilence ?? false,
    },
    ...(organize.organizerModel && { organizerModel: organize.organizerModel }),
  });
  return {
    listMemos: (limit) => fake.listMemos({ limit }),
    getMemo: async (id) => {
      const detail = await fake.getMemo(id);
      if (detail === null) return null;
      return {
        ...detail,
        utterances: detail.utterances.map((item) => ({
          ...item,
          speakerName: item.speakerName ?? null,
        })),
      };
    },
    getOrganizeStatus: () => fake.getOrganizeStatus(),
    startOrganize: () => fake.startOrganize(),
    stopOrganize: () => fake.stopOrganize(),
    askMemos: (question) => fake.askMemos(question),
    cancelAsk: () => fake.cancelAsk(),
    addListener: (_name, listener) => ({ remove: fake.subscribe(listener) }),
  };
}

runMemoClientContract("engine-bridge の JS", (memos, organize) =>
  createMemoClient(nativeModule(memos, organize)),
);

describe("createMemoClient", () => {
  it("本物の接続では見本の帯を出さない", async () => {
    expect(await createMemoClient(nativeModule([])).getMemoMode()).toBe("ready");
  });

  it("質問を止めたときのネイティブの null を返さない", async () => {
    const native = { ...nativeModule([]), cancelAsk: () => Promise.resolve(null as never) };

    expect(await createMemoClient(native).cancelAsk()).toBeUndefined();
  });
});
