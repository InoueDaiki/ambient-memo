import { describe, expect, it } from "vitest";

import { FakeEngineClient } from "./fake.ts";
import { memo, runMemoClientContract, todo } from "./memo-contract.ts";
import { sampleMemos } from "./sample-memos.ts";
import {
  SelfEnrollmentError,
  type EngineEvent,
  type TranscriptEngineClient,
  type Utterance,
} from "./types.ts";

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

describe("FakeEngineClient の声の登録", () => {
  const notEnrolled = () => new FakeEngineClient({ selfEnrollment: "not-enrolled" });

  it("聞き取りが終わると登録済みになる", async () => {
    const client = notEnrolled();
    expect(await client.getSelfEnrollmentStatus()).toBe("not-enrolled");

    const done = client.enrollSelf();
    client.finishEnrollment();
    await done;

    expect(await client.getSelfEnrollmentStatus()).toBe("enrolled");
  });

  it("止めると cancelled で reject し、登録しない", async () => {
    const client = notEnrolled();

    const done = client.enrollSelf();
    await client.cancelSelfEnrollment();

    await expect(done).rejects.toEqual(new SelfEnrollmentError("cancelled"));
    expect(await client.getSelfEnrollmentStatus()).toBe("not-enrolled");
  });

  it("聞き取り中は登録できない", async () => {
    const client = notEnrolled();
    await client.startListening();

    await expect(client.enrollSelf()).rejects.toEqual(new SelfEnrollmentError("listening"));
  });

  it("失敗は1回だけ起きる", async () => {
    const client = notEnrolled();
    client.failNextEnrollment("not-enough-speech");

    await expect(client.enrollSelf()).rejects.toEqual(new SelfEnrollmentError("not-enough-speech"));

    const done = client.enrollSelf();
    client.finishEnrollment();
    await done;
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

runMemoClientContract("FakeEngineClient", (memos) => new FakeEngineClient({ memos }));

describe("FakeEngineClient のメモと ToDo", () => {
  it("メモが増えるとイベントが届く", () => {
    const client = new FakeEngineClient();
    const events: EngineEvent[] = [];
    client.subscribe((event) => events.push(event));

    client.addMemo(memo("a"));
    client.addMemo(memo("b", { todos: [todo("t")] }));

    expect(events).toEqual([
      { type: "memos-changed" },
      { type: "memos-changed" },
      { type: "todos-changed" },
    ]);
  });

  it("読み取りと完了の失敗は1回だけ起きる", async () => {
    const client = new FakeEngineClient({ memos: [memo("a", { todos: [todo("t")] })] });

    client.failNextMemoRead(new Error("読めない"));
    await expect(client.listMemos({ limit: 1 })).rejects.toThrow("読めない");
    expect(await client.listMemos({ limit: 1 })).toHaveLength(1);

    client.failNextComplete(new Error("書けない"));
    await expect(client.completeTodo("t")).rejects.toThrow("書けない");
    await client.completeTodo("t");
    expect((await client.listTodos({ limit: 1 }))[0]?.status).toBe("done");
  });
});

describe("見本のメモ", () => {
  it("画面が扱う状態をすべて含む", () => {
    const todos = sampleMemos.flatMap((item) => item.todos);

    expect(sampleMemos.some((item) => item.summary === null)).toBe(true);
    expect(sampleMemos.some((item) => item.todoExtraction === "incomplete")).toBe(true);
    expect(todos.some((item) => item.status === "candidate" && item.dueDate !== null)).toBe(true);
    expect(todos.some((item) => item.status === "candidate" && item.dueDate === null)).toBe(true);
  });

  it("ToDo の memoId が親のメモを指す", () => {
    for (const item of sampleMemos) {
      expect(item.todos.every((entry) => entry.memoId === item.id)).toBe(true);
    }
  });
});
