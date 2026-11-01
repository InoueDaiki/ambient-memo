// MemoEngineClient の契約テスト。Fake と本物の両方に流す。
// 本物の JS 側は modules/engine-bridge、Kotlin 側は engine/android の MemoBridgeContractTest が流す。
// Kotlin 側はテストの名前をここと比べるので、ケースを足したら両方に足す
import { describe, expect, it } from "vitest";

import type {
  EngineEvent,
  MemoDetail,
  MemoEngineClient,
  OrganizerModelStatus,
  Todo,
  Utterance,
} from "./types.ts";

export const memo = (id: string, overrides: Partial<MemoDetail> = {}): MemoDetail => ({
  id,
  title: `タイトル ${id}`,
  summary: `要約 ${id}`,
  todoExtraction: "complete",
  startedAt: 1,
  endedAt: 2,
  utterances: [],
  todos: [],
  ...overrides,
});

export const todo = (id: string, overrides: Partial<Todo> = {}): Todo => ({
  id,
  memoId: "m",
  task: `内容 ${id}`,
  kind: "todo",
  dueDate: null,
  status: "open",
  createdAt: 1,
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
  describe(`${name} のメモと ToDo`, () => {
    it("メモは開始時刻の新しい順に limit 件まで返す", async () => {
      const client = createClient([
        memo("a", { startedAt: 1 }),
        memo("c", { startedAt: 3 }),
        memo("b", { startedAt: 2 }),
      ]);

      const items = await client.listMemos({ limit: 2 });

      expect(items.map((item) => item.id)).toEqual(["c", "b"]);
    });

    it("一覧のメモには発話と ToDo を含めない", async () => {
      const [item] = await createClient([memo("a", { todos: [todo("t")] })]).listMemos({
        limit: 1,
      });

      expect(item).not.toHaveProperty("utterances");
      expect(item).not.toHaveProperty("todos");
    });

    it("要約に失敗したメモも一覧と詳細で返す", async () => {
      const client = createClient([memo("a", { title: null, summary: null })]);

      expect((await client.listMemos({ limit: 1 }))[0]?.summary).toBeNull();
      expect((await client.getMemo("a"))?.title).toBeNull();
    });

    it("メモの詳細は根拠の発話と ToDo を返す", async () => {
      const utterances: Utterance[] = [{ id: "u", text: "発話", speaker: "self", startedAt: 1 }];
      const detail = await createClient([memo("a", { utterances, todos: [todo("t")] })]).getMemo(
        "a",
      );

      expect(detail?.utterances).toEqual(utterances);
      expect(detail?.todos.map((item) => item.id)).toEqual(["t"]);
    });

    it("ないメモは null を返す", async () => {
      expect(await createClient([]).getMemo("none")).toBeNull();
    });

    it("ToDo は未完了、期限の早い順、新しい順に並べ、期限のないものは後ろにする", async () => {
      const client = createClient([
        memo("a", {
          todos: [
            todo("done", { status: "done", dueDate: "2026-01-01" }),
            todo("no-due"),
            todo("later", { dueDate: "2026-12-01" }),
            todo("sooner-old", { dueDate: "2026-11-01", createdAt: 1 }),
          ],
        }),
        memo("b", {
          todos: [todo("sooner-new", { dueDate: "2026-11-01", createdAt: 2, status: "candidate" })],
        }),
      ]);

      const items = await client.listTodos({ limit: 10 });

      expect(items.map((item) => item.id)).toEqual([
        "sooner-new",
        "sooner-old",
        "later",
        "no-due",
        "done",
      ]);
    });

    it("ToDo は limit 件まで返す", async () => {
      const client = createClient([memo("a", { todos: [todo("1"), todo("2"), todo("3")] })]);

      expect(await client.listTodos({ limit: 2 })).toHaveLength(2);
    });

    it("完了すると状態が変わり、解決するまでにイベントが届く", async () => {
      const client = createClient([memo("a", { todos: [todo("t", { status: "candidate" })] })]);
      const events: EngineEvent[] = [];
      client.subscribe((event) => events.push(event));

      await client.completeTodo("t");

      expect(events).toEqual([{ type: "todos-changed" }]);
      expect((await client.listTodos({ limit: 1 }))[0]?.status).toBe("done");
      expect((await client.getMemo("a"))?.todos[0]?.status).toBe("done");
    });

    it("完了済みの ToDo の完了は何もせず成功する", async () => {
      const client = createClient([memo("a", { todos: [todo("t", { status: "done" })] })]);
      const events: EngineEvent[] = [];
      client.subscribe((event) => events.push(event));

      await client.completeTodo("t");

      expect(events).toEqual([]);
    });

    it("ない ToDo の完了は reject する", async () => {
      await expect(createClient([]).completeTodo("none")).rejects.toThrow();
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
}
