import type {
  EngineEvent,
  EngineListener,
  EnginePermissions,
  EngineStatus,
  ListeningStatus,
  Memo,
  MemoDetail,
  MemoEngineClient,
  MemoMode,
  Todo,
  TranscriptEngineClient,
  Utterance,
} from "./types.ts";

export interface FakeEngineClientOptions {
  status?: EngineStatus;
  listeningStatus?: ListeningStatus;
  utterances?: Utterance[];
  permissions?: EnginePermissions;
  memoMode?: MemoMode;
  memos?: MemoDetail[];
}

// 未完了を先に、期限の早い順。期限がなければ後ろ、同じなら新しい順
function compareTodos(a: Todo, b: Todo): number {
  const byDone = Number(a.status === "done") - Number(b.status === "done");
  if (byDone !== 0) return byDone;
  if (a.dueDate !== b.dueDate) {
    if (a.dueDate === null) return 1;
    if (b.dueDate === null) return -1;
    return a.dueDate < b.dueDate ? -1 : 1;
  }
  return b.createdAt - a.createdAt;
}

function toMemo(detail: MemoDetail): Memo {
  const { id, title, summary, todoExtraction, startedAt, endedAt } = detail;
  return { id, title, summary, todoExtraction, startedAt, endedAt };
}

// エンジンなしで画面を開発・テストするための実装
export class FakeEngineClient implements TranscriptEngineClient, MemoEngineClient {
  readonly #status: EngineStatus;
  readonly #listeners = new Set<EngineListener>();
  #listeningStatus: ListeningStatus;
  #utterances: Utterance[];
  #startError: Error | undefined;
  #permissions: EnginePermissions;
  #settingsOpened = 0;
  readonly #memoMode: MemoMode;
  #memos: MemoDetail[];
  #memoReadError: Error | undefined;
  #completeError: Error | undefined;

  constructor({
    status = { engineName: "fake-engine", apiVersion: 1, transcription: "ready" },
    listeningStatus = "stopped",
    utterances = [],
    permissions = { microphone: true, notifications: true },
    memoMode = "ready",
    memos = [],
  }: FakeEngineClientOptions = {}) {
    this.#status = status;
    this.#listeningStatus = listeningStatus;
    this.#utterances = [...utterances];
    this.#permissions = permissions;
    this.#memoMode = memoMode;
    this.#memos = [...memos];
  }

  getStatus(): Promise<EngineStatus> {
    return Promise.resolve(this.#status);
  }

  getListeningStatus(): Promise<ListeningStatus> {
    return Promise.resolve(this.#listeningStatus);
  }

  getPermissions(): Promise<EnginePermissions> {
    return Promise.resolve(this.#permissions);
  }

  openPermissionSettings(): Promise<void> {
    this.#settingsOpened += 1;
    return Promise.resolve();
  }

  startListening(): Promise<void> {
    if (this.#startError) {
      const error = this.#startError;
      this.#startError = undefined;
      return Promise.reject(error);
    }
    this.setListeningStatus("listening");
    return Promise.resolve();
  }

  stopListening(): Promise<void> {
    this.setListeningStatus("stopped");
    return Promise.resolve();
  }

  listUtterances({ limit }: { limit: number }): Promise<Utterance[]> {
    const newestFirst = [...this.#utterances].sort((a, b) => b.startedAt - a.startedAt);
    return Promise.resolve(newestFirst.slice(0, limit));
  }

  getMemoMode(): Promise<MemoMode> {
    return Promise.resolve(this.#memoMode);
  }

  listMemos({ limit }: { limit: number }): Promise<Memo[]> {
    return this.#readMemos(() =>
      [...this.#memos]
        .sort((a, b) => b.startedAt - a.startedAt)
        .slice(0, limit)
        .map(toMemo),
    );
  }

  getMemo(id: string): Promise<MemoDetail | null> {
    return this.#readMemos(() => this.#memos.find((memo) => memo.id === id) ?? null);
  }

  listTodos({ limit }: { limit: number }): Promise<Todo[]> {
    return this.#readMemos(() =>
      this.#memos
        .flatMap((memo) => memo.todos)
        .sort(compareTodos)
        .slice(0, limit),
    );
  }

  completeTodo(id: string): Promise<void> {
    if (this.#completeError) {
      const error = this.#completeError;
      this.#completeError = undefined;
      return Promise.reject(error);
    }
    const todo = this.#memos.flatMap((memo) => memo.todos).find((item) => item.id === id);
    if (!todo) return Promise.reject(new Error(`ToDo がありません: ${id}`));
    if (todo.status === "done") return Promise.resolve();
    this.#memos = this.#memos.map((memo) => ({
      ...memo,
      todos: memo.todos.map((item) => (item.id === id ? { ...item, status: "done" } : item)),
    }));
    this.#emit({ type: "todos-changed" });
    return Promise.resolve();
  }

  subscribe(listener: EngineListener): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  // ここから下は Fake だけの操作。エンジンの側で起きることを再現する

  addUtterance(utterance: Utterance): void {
    this.#utterances.push(utterance);
    this.#emit({ type: "utterances-changed" });
  }

  addMemo(memo: MemoDetail): void {
    this.#memos.push(memo);
    this.#emit({ type: "memos-changed" });
    if (memo.todos.length > 0) this.#emit({ type: "todos-changed" });
  }

  // エンジンの都合での停止(マイクの占有など)も、これで再現する
  setListeningStatus(status: ListeningStatus): void {
    if (status === this.#listeningStatus) return;
    this.#listeningStatus = status;
    this.#emit({ type: "listening-changed", status });
  }

  // エンジンの都合で止まったことを再現する(マイクの占有など)
  interrupt(): void {
    if (this.#listeningStatus === "stopped") return;
    this.#listeningStatus = "stopped";
    this.#emit({ type: "listening-changed", status: "stopped", interrupted: true });
  }

  setPermissions(permissions: EnginePermissions): void {
    this.#permissions = permissions;
    this.#emit({ type: "permissions-changed" });
  }

  get settingsOpened(): number {
    return this.#settingsOpened;
  }

  // 次の startListening を失敗させる
  failNextStart(error: Error): void {
    this.#startError = error;
  }

  // 次の listMemos、getMemo、listTodos のどれか1回を失敗させる
  failNextMemoRead(error: Error): void {
    this.#memoReadError = error;
  }

  // 次の completeTodo を失敗させる
  failNextComplete(error: Error): void {
    this.#completeError = error;
  }

  #readMemos<T>(read: () => T): Promise<T> {
    if (this.#memoReadError) {
      const error = this.#memoReadError;
      this.#memoReadError = undefined;
      return Promise.reject(error);
    }
    return Promise.resolve(read());
  }

  #emit(event: EngineEvent): void {
    for (const listener of this.#listeners) listener(event);
  }
}
