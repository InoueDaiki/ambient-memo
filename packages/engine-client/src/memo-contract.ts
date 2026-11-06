// MemoEngineClient の契約テスト。Fake と本物の両方に流す。
// 本物の JS 側は modules/engine-bridge、Kotlin 側は engine/android の MemoBridgeContractTest が流す。
// Kotlin 側はテストの名前をここと比べるので、ケースを足したら両方に足す
import { describe, expect, it } from "vitest";

import type {
  EngineEvent,
  MemoDetail,
  MemoEngineClient,
  OrganizerModelStatus,
  Utterance,
} from "./types.ts";

export const memo = (id: string, overrides: Partial<MemoDetail> = {}): MemoDetail => ({
  id,
  title: `タイトル ${id}`,
  summary: `要約 ${id}`,
  startedAt: 1,
  endedAt: 2,
  utterances: [],
  ...overrides,
});

// 整理の契約の前提。Fake は未処理の量を直接持つ。Kotlin 側は本物の SQLite に発話を入れる
export interface OrganizeSetup {
  pendingChunks?: number;
  isWaitingForSilence?: boolean;
  organizerModel?: OrganizerModelStatus;
}

export function runMemoClientContract(
  name: string,
  createClient: (memos: MemoDetail[], organize?: OrganizeSetup) => MemoEngineClient,
): void {
  describe(`${name} のメモ`, () => {
    it("メモは開始時刻の新しい順に limit 件まで返す", async () => {
      const client = createClient([
        memo("a", { startedAt: 1 }),
        memo("c", { startedAt: 3 }),
        memo("b", { startedAt: 2 }),
      ]);

      const items = await client.listMemos({ limit: 2 });

      expect(items.map((item) => item.id)).toEqual(["c", "b"]);
    });

    it("一覧のメモには発話を含めない", async () => {
      const [item] = await createClient([memo("a")]).listMemos({ limit: 1 });

      expect(item).not.toHaveProperty("utterances");
    });

    it("要約に失敗したメモも一覧と詳細で返す", async () => {
      const client = createClient([memo("a", { title: null, summary: null })]);

      expect((await client.listMemos({ limit: 1 }))[0]?.summary).toBeNull();
      expect((await client.getMemo("a"))?.title).toBeNull();
    });

    it("メモの詳細は根拠の発話を返す", async () => {
      const utterances: Utterance[] = [{ id: "u", text: "発話", speaker: "self", startedAt: 1 }];
      const detail = await createClient([memo("a", { utterances })]).getMemo("a");

      expect(detail?.utterances).toEqual(utterances);
    });

    it("ないメモは null を返す", async () => {
      expect(await createClient([]).getMemo("none")).toBeNull();
    });

    it("未処理がなければ整理を始めない", async () => {
      const client = createClient([]);

      expect(await client.startOrganize()).toBe("nothing-to-organize");
      const status = await client.getOrganizeStatus();
      expect(status.state).toBe("idle");
      expect(status.pendingChunks).toBe(0);
    });

    it("話が途切れていない発話は待っていると伝える", async () => {
      const client = createClient([], { isWaitingForSilence: true });

      const status = await client.getOrganizeStatus();

      expect(status.pendingChunks).toBe(0);
      expect(status.isWaitingForSilence).toBe(true);
      expect(await client.startOrganize()).toBe("nothing-to-organize");
    });

    it("未処理があれば整理を始めて、進行の状態を返す", async () => {
      const client = createClient([], { pendingChunks: 2 });

      expect((await client.getOrganizeStatus()).pendingChunks).toBe(2);
      expect(await client.startOrganize()).toBe("started");

      expect((await client.getOrganizeStatus()).state).toBe("preparing");
    });

    it("動いている間に始めても二重には始めない", async () => {
      const client = createClient([], { pendingChunks: 1 });
      await client.startOrganize();

      expect(await client.startOrganize()).toBe("already-running");
    });

    it("止めると区切りで止まる途中の状態になる", async () => {
      const client = createClient([], { pendingChunks: 1 });
      await client.startOrganize();

      await client.stopOrganize();

      expect((await client.getOrganizeStatus()).state).toBe("stopping");
    });

    it("動いていないときに止めても何も起きない", async () => {
      const client = createClient([]);

      await client.stopOrganize();

      expect((await client.getOrganizeStatus()).state).toBe("idle");
    });

    it("整理のモデルがなければ始めない", async () => {
      const client = createClient([], {
        pendingChunks: 1,
        organizerModel: { state: "idle", downloadedBytes: 0, totalBytes: 1 },
      });

      expect(await client.startOrganize()).toBe("model-unavailable");
    });
  });

  // 答えの文の中身は契約に入れない。Kotlin 側は本物の LLM の代わりに、決まった応答の Fake を使うため
  describe(`${name} の質問`, () => {
    const related = () => [
      memo("old", { title: "読書会の日程", summary: "次の読書会は来月の第2日曜", startedAt: 1 }),
      memo("new", { title: "読書会の本", summary: "課題の本を読んでおく", startedAt: 3 }),
      memo("other", { title: "庭の手入れ", summary: "土曜に土を買う", startedAt: 2 }),
    ];

    it("空の質問は reject する", async () => {
      const client = createClient(related());

      await expect(client.askMemos("")).rejects.toThrow();
      await expect(client.askMemos("  ")).rejects.toThrow();
    });

    it("関係するメモがなければ not-found で、答えも根拠もない", async () => {
      const answer = await createClient(related()).askMemos("引っ越し");

      expect(answer).toEqual({ outcome: "not-found", answer: null, sources: [] });
    });

    it("関係するメモがあれば answered で、答えと根拠のメモを返す", async () => {
      const answer = await createClient(related()).askMemos("読書会");

      expect(answer.outcome).toBe("answered");
      expect(answer.answer?.length).toBeGreaterThan(0);
      expect(answer.sources.length).toBeGreaterThan(0);
      for (const source of answer.sources) {
        expect(["old", "new"]).toContain(source.id);
        expect(source).not.toHaveProperty("utterances");
      }
    });

    it("解決するまでに進み具合が準備、検索、回答の順に届く", async () => {
      const client = createClient(related());
      const events: EngineEvent[] = [];
      client.subscribe((event) => events.push(event));

      await client.askMemos("読書会");

      expect(events.filter((event) => event.type === "ask-progress")).toEqual([
        { type: "ask-progress", phase: "preparing" },
        { type: "ask-progress", phase: "searching" },
        { type: "ask-progress", phase: "answering" },
      ]);
    });

    it("整理のモデルがなければ model-unavailable を返す", async () => {
      const client = createClient(related(), {
        organizerModel: { state: "idle", downloadedBytes: 0, totalBytes: 1 },
      });

      expect(await client.askMemos("読書会")).toEqual({
        outcome: "model-unavailable",
        answer: null,
        sources: [],
      });
    });

    it("整理が動いている間は busy を返す", async () => {
      const client = createClient(related(), { pendingChunks: 1 });
      await client.startOrganize();

      expect(await client.askMemos("読書会")).toEqual({
        outcome: "busy",
        answer: null,
        sources: [],
      });
    });

    it("質問が動いていないときに止めても何も起きない", async () => {
      const client = createClient(related());

      await client.cancelAsk();

      expect((await client.askMemos("読書会")).outcome).toBe("answered");
    });
  });
}
