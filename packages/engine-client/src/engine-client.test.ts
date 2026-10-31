import { describe, expect, it } from "vitest";

import { FakeEngineClient } from "./fake.ts";
import type { EngineEvent, TranscriptEngineClient, Utterance } from "./types.ts";

// 契約テスト。本物の実装を作ったら、同じテストをその実装にも流す
describe.each<[string, () => TranscriptEngineClient]>([
  ["FakeEngineClient", () => new FakeEngineClient()],
])("%s", (_name, createClient) => {
  it("エンジンの名前と API の版を返す", async () => {
    const status = await createClient().getStatus();

    expect(status.engineName).not.toBe("");
    expect(status.apiVersion).toBeGreaterThanOrEqual(1);
  });

  it("最初は止まっている", async () => {
    expect(await createClient().getListeningStatus()).toBe("stopped");
  });

  it("開始と停止で状態が変わり、解決するまでにイベントが届く", async () => {
    const client = createClient();
    const events: EngineEvent[] = [];
    client.subscribe((event) => events.push(event));

    await client.startListening();
    expect(await client.getListeningStatus()).toBe("listening");
    expect(events).toEqual([{ type: "listening-changed", status: "listening" }]);

    await client.stopListening();
    expect(await client.getListeningStatus()).toBe("stopped");
    expect(events.at(-1)).toEqual({ type: "listening-changed", status: "stopped" });
  });

  it("同じ状態への開始や停止は何もせず成功する", async () => {
    const client = createClient();
    const events: EngineEvent[] = [];
    client.subscribe((event) => events.push(event));

    await client.stopListening();
    await client.startListening();
    await client.startListening();

    expect(events).toEqual([{ type: "listening-changed", status: "listening" }]);
    await client.stopListening();
  });

  it("購読をやめるとイベントが届かない", async () => {
    const client = createClient();
    const events: EngineEvent[] = [];
    const unsubscribe = client.subscribe((event) => events.push(event));

    unsubscribe();
    await client.startListening();

    expect(events).toEqual([]);
    await client.stopListening();
  });
});

describe("FakeEngineClient のモデルの取得", () => {
  const missing = () =>
    new FakeEngineClient({
      status: { engineName: "fake", apiVersion: 1, transcription: "unavailable" },
    });

  it("開始するまで取得しない", async () => {
    const client = missing();

    expect((await client.getModelDownloadStatus()).state).toBe("idle");
    expect(client.modelDownloadStarts).toBe(0);
  });

  it("開始すると取得中になり、イベントで届く", async () => {
    const client = missing();
    const events: EngineEvent[] = [];
    client.subscribe((event) => events.push(event));

    await client.startModelDownload();
    await client.startModelDownload();

    expect(client.modelDownloadStarts).toBe(1);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      type: "model-download-changed",
      status: { state: "running", phase: "downloading" },
    });
  });

  it("止めると途中までの量が残る", async () => {
    const client = missing();
    await client.startModelDownload();
    client.setModelDownload({
      state: "running",
      phase: "downloading",
      completedBytes: 100,
      totalBytes: 200,
    });

    await client.cancelModelDownload();

    expect(await client.getModelDownloadStatus()).toEqual({
      state: "idle",
      downloadedBytes: 100,
      totalBytes: 200,
    });
  });

  it("揃うと文字起こしが使える", async () => {
    const client = missing();

    client.setModelDownload({ state: "installed", downloadedBytes: 0, totalBytes: 0 });

    expect((await client.getStatus()).transcription).toBe("ready");
  });

  it("モデルが揃っていれば開始しても何もしない", async () => {
    const client = new FakeEngineClient();

    await client.startModelDownload();

    expect(client.modelDownloadStarts).toBe(0);
    expect((await client.getModelDownloadStatus()).state).toBe("installed");
  });

  it("整理のモデルは文字起こしのモデルと別に取得する", async () => {
    const client = new FakeEngineClient({
      organizerModel: { state: "idle", downloadedBytes: 0, totalBytes: 3_659_530_240 },
    });
    const events: EngineEvent[] = [];
    client.subscribe((event) => events.push(event));

    await client.startOrganizerModelDownload();
    await client.startOrganizerModelDownload();

    expect(client.organizerModelStarts).toBe(1);
    expect(client.modelDownloadStarts).toBe(0);
    expect(events[0]).toMatchObject({
      type: "organizer-model-changed",
      status: { state: "running", phase: "downloading" },
    });

    client.setOrganizerModel({
      state: "running",
      phase: "downloading",
      completedBytes: 100,
      totalBytes: 3_659_530_240,
    });
    await client.cancelModelDownload();

    expect(await client.getOrganizerModelStatus()).toEqual({
      state: "idle",
      downloadedBytes: 100,
      totalBytes: 3_659_530_240,
    });
  });

  it("取得できない整理のモデルは始めない", async () => {
    const client = new FakeEngineClient({ organizerModel: { state: "unavailable" } });

    await client.startOrganizerModelDownload();

    expect(client.organizerModelStarts).toBe(0);
  });
});

describe("FakeEngineClient の発話", () => {
  const utterance = (id: string, startedAt: number): Utterance => ({
    id,
    startedAt,
    text: `発話 ${id}`,
    speaker: "self",
  });

  it("新しい順に limit 件まで返す", async () => {
    const client = new FakeEngineClient({
      utterances: [utterance("a", 1), utterance("c", 3), utterance("b", 2)],
    });

    const items = await client.listUtterances({ limit: 2 });

    expect(items.map((item) => item.id)).toEqual(["c", "b"]);
  });

  it("発話が増えるとイベントが届く", () => {
    const client = new FakeEngineClient();
    const events: EngineEvent[] = [];
    client.subscribe((event) => events.push(event));

    client.addUtterance(utterance("a", 1));

    expect(events).toEqual([{ type: "utterances-changed" }]);
  });

  it("開始の失敗は1回だけ起きる", async () => {
    const client = new FakeEngineClient();
    client.failNextStart(new Error("マイクを使えない"));

    await expect(client.startListening()).rejects.toThrow("マイクを使えない");
    expect(await client.getListeningStatus()).toBe("stopped");

    await client.startListening();
    expect(await client.getListeningStatus()).toBe("listening");
  });
});
