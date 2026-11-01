import { describe, expect, it } from "vitest";

import { FakeEngineClient } from "./fake.ts";
import { memo, runMemoClientContract, todo } from "./memo-contract.ts";
import { sampleMemos } from "./sample-memos.ts";
import {
  SelfEnrollmentError,
  type EngineEvent,
  type SpeakerInfo,
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

  it("取得済みの整理のモデルは始めない", async () => {
    const client = new FakeEngineClient();

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

runMemoClientContract(
  "FakeEngineClient",
  (memos, { pendingChunks = 0, isWaitingForSilence = false, organizerModel } = {}) =>
    new FakeEngineClient({
      memos,
      organizeBacklog: { pendingChunks, isWaitingForSilence },
      ...(organizerModel && { organizerModel }),
    }),
);

describe("FakeEngineClient の整理", () => {
  it("始まりと進み具合と終わりでイベントが届き、結果が残る", async () => {
    const client = new FakeEngineClient({ organizeBacklog: { pendingChunks: 2 } });
    const events: EngineEvent[] = [];
    client.subscribe((event) => events.push(event));

    await client.startOrganize();
    client.progressOrganize(1, 2);
    expect((await client.getOrganizeStatus()).completedChunks).toBe(1);
    client.finishOrganize("too-hot", 1);

    expect(events).toHaveLength(3);
    const status = await client.getOrganizeStatus();
    expect(status.state).toBe("idle");
    expect(status.lastResult).toBe("too-hot");
    expect(status.pendingChunks).toBe(1);
  });

  it("もう一度始めると前の結果は消える", async () => {
    const client = new FakeEngineClient({ organizeBacklog: { pendingChunks: 1 } });
    await client.startOrganize();
    client.finishOrganize("stopped", 1);

    await client.startOrganize();

    expect((await client.getOrganizeStatus()).lastResult).toBeNull();
  });
});

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

describe("FakeEngineClient の話者", () => {
  const speakers: SpeakerInfo[] = [
    { id: "1", name: "自分", kind: "self", utteranceCount: 0 },
    { id: "2", name: "話者1", kind: "provisional", utteranceCount: 0 },
  ];
  const row: Utterance = {
    id: "10",
    text: "本文",
    speaker: "other",
    speakerId: "2",
    speakerName: "話者1",
    startedAt: 0,
  };

  it("発話の話者を直すと utterances-changed が届く", async () => {
    const client = new FakeEngineClient({ speakers, utterances: [row] });
    const events: string[] = [];
    client.subscribe((event) => events.push(event.type));

    await client.setUtteranceSpeaker("10", "self");

    expect(events).toEqual(["utterances-changed"]);
    const [saved] = await client.listUtterances({ limit: 1 });
    expect(saved).toMatchObject({ speaker: "self", speakerId: "1", speakerName: "自分" });
  });

  it("不明に直すと話者の id と名前がなくなる", async () => {
    const client = new FakeEngineClient({ speakers, utterances: [row] });

    await client.setUtteranceSpeaker("10", "unknown");

    const [saved] = await client.listUtterances({ limit: 1 });
    expect(saved?.speaker).toBe("unknown");
    expect(saved?.speakerId).toBeUndefined();
    expect(saved?.speakerName).toBeUndefined();
  });

  it("ない発話やない話者は reject する", async () => {
    const client = new FakeEngineClient({ speakers, utterances: [row] });

    await expect(client.setUtteranceSpeaker("99", "self")).rejects.toThrow();
    await expect(client.setUtteranceSpeaker("10", "99")).rejects.toThrow();
  });

  it("統合すると統合元が消え、発話が移る。自分は統合元にできない", async () => {
    const client = new FakeEngineClient({ speakers, utterances: [row] });

    await expect(client.mergeSpeakers("1", "2")).rejects.toThrow();
    await client.mergeSpeakers("2", "1");

    expect((await client.listSpeakers()).map((item) => item.id)).toEqual(["1"]);
    expect((await client.listUtterances({ limit: 1 }))[0]?.speaker).toBe("self");
  });

  it("リセットすると話者と登録が消え、発話は不明になる。聞き取り中は reject する", async () => {
    const listening = new FakeEngineClient({ speakers, listeningStatus: "listening" });
    await expect(listening.resetSpeakers()).rejects.toThrow();

    const client = new FakeEngineClient({ speakers, utterances: [row] });
    await client.resetSpeakers();

    expect(await client.listSpeakers()).toEqual([]);
    expect(await client.getSelfEnrollmentStatus()).toBe("not-enrolled");
    expect((await client.listUtterances({ limit: 1 }))[0]?.speaker).toBe("unknown");
  });

  it("名前を付けると named になり、空の名前は reject する", async () => {
    const client = new FakeEngineClient({ speakers, utterances: [row] });

    await expect(client.renameSpeaker("2", "  ")).rejects.toThrow();
    await client.renameSpeaker("2", " 母 ");

    expect((await client.listSpeakers())[1]).toMatchObject({ name: "母", kind: "named" });
    expect((await client.listUtterances({ limit: 1 }))[0]?.speakerName).toBe("母");
  });

  it("発話の数を話者に添える", async () => {
    const client = new FakeEngineClient({ speakers, utterances: [row] });

    expect((await client.listSpeakers())[1]?.utteranceCount).toBe(1);
  });
});
