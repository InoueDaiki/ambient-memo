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
