import { SelfEnrollmentError } from "./types.ts";
import type {
  EngineEvent,
  EngineListener,
  EnginePermissions,
  EngineStatus,
  ListeningStatus,
  SelfEnrollmentFailure,
  SelfEnrollmentStatus,
  ModelDownloadStatus,
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
  selfEnrollment?: SelfEnrollmentStatus;
  modelDownload?: ModelDownloadStatus;
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
  #status: EngineStatus;
  readonly #listeners = new Set<EngineListener>();
  #listeningStatus: ListeningStatus;
  #utterances: Utterance[];
  #startError: Error | undefined;
  #permissions: EnginePermissions;
  #settingsOpened = 0;
  #selfEnrollment: SelfEnrollmentStatus;
  #enrollmentFailure: SelfEnrollmentFailure | undefined;
  #finishEnrollment: (() => void) | undefined;
  #rejectEnrollment: ((error: Error) => void) | undefined;
  #modelDownload: ModelDownloadStatus;
  #modelDownloadStarts = 0;
  readonly #memoMode: MemoMode;
  #memos: MemoDetail[];
  #memoReadError: Error | undefined;
  #completeError: Error | undefined;

  constructor({
    status = { engineName: "fake-engine", apiVersion: 1, transcription: "ready" },
    listeningStatus = "stopped",
    utterances = [],
    permissions = { microphone: true, notifications: true },
    selfEnrollment = "enrolled",
    modelDownload,
    memoMode = "ready",
    memos = [],
  }: FakeEngineClientOptions = {}) {
    this.#status = status;
    this.#listeningStatus = listeningStatus;
    this.#utterances = [...utterances];
    this.#permissions = permissions;
    this.#selfEnrollment = selfEnrollment;
    this.#modelDownload =
      modelDownload ??
      (status.transcription === "ready"
        ? { state: "installed", downloadedBytes: 0, totalBytes: 0 }
        : { state: "idle", downloadedBytes: 0, totalBytes: 742_022_351 });
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

  getSelfEnrollmentStatus(): Promise<SelfEnrollmentStatus> {
    return Promise.resolve(this.#selfEnrollment);
  }

  // finishEnrollment を呼ぶまで聞き取りの途中のまま待つ
  enrollSelf(): Promise<void> {
    if (this.#enrollmentFailure) {
      const failure = this.#enrollmentFailure;
      this.#enrollmentFailure = undefined;
      return Promise.reject(new SelfEnrollmentError(failure));
    }
    if (this.#finishEnrollment) return Promise.reject(new SelfEnrollmentError("listening"));
    if (this.#listeningStatus === "listening") {
      return Promise.reject(new SelfEnrollmentError("listening"));
    }
    return new Promise((resolve, reject) => {
      this.#finishEnrollment = () => {
        this.#selfEnrollment = "enrolled";
        resolve();
      };
      this.#rejectEnrollment = reject;
    });
  }

  cancelSelfEnrollment(): Promise<void> {
    this.#rejectEnrollment?.(new SelfEnrollmentError("cancelled"));
    this.#clearEnrollment();
    return Promise.resolve();
  }

  getModelDownloadStatus(): Promise<ModelDownloadStatus> {
    return Promise.resolve(this.#modelDownload);
  }

  startModelDownload(): Promise<void> {
    const current = this.#modelDownload;
    if (current.state === "running" || current.state === "installed") return Promise.resolve();
    this.#modelDownloadStarts += 1;
    this.setModelDownload({
      state: "running",
      phase: "downloading",
      completedBytes: current.downloadedBytes,
      totalBytes: current.totalBytes,
    });
    return Promise.resolve();
  }

  cancelModelDownload(): Promise<void> {
    const current = this.#modelDownload;
    if (current.state === "running") {
      this.setModelDownload({
        state: "idle",
        downloadedBytes:
          current.phase === "downloading" ? current.completedBytes : current.totalBytes,
        totalBytes: current.totalBytes,
      });
    }
    return Promise.resolve();
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

  // 取得の進み具合や失敗を再現する。揃ったら文字起こしも使えるようになる
  setModelDownload(status: ModelDownloadStatus): void {
    this.#modelDownload = status;
    if (status.state === "installed") {
      this.#status = { ...this.#status, transcription: "ready" };
      if (this.#selfEnrollment === "unavailable") this.#selfEnrollment = "not-enrolled";
    }
    if (status.state === "idle") this.#status = { ...this.#status, transcription: "unavailable" };
    this.#emit({ type: "model-download-changed", status });
  }

  get modelDownloadStarts(): number {
    return this.#modelDownloadStarts;
  }

  get settingsOpened(): number {
    return this.#settingsOpened;
  }

  // 次の startListening を失敗させる
  failNextStart(error: Error): void {
    this.#startError = error;
  }

  // 登録の聞き取りを成功で終える
  finishEnrollment(): void {
    this.#finishEnrollment?.();
    this.#clearEnrollment();
  }

  // 次の enrollSelf を失敗させる
  failNextEnrollment(failure: SelfEnrollmentFailure): void {
    this.#enrollmentFailure = failure;
  }

  #clearEnrollment(): void {
    this.#finishEnrollment = undefined;
    this.#rejectEnrollment = undefined;
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
