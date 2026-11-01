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
  OrganizeResult,
  OrganizerModelStatus,
  OrganizeStartResult,
  OrganizeStatus,
  Memo,
  MemoDetail,
  MemoEngineClient,
  MemoMode,
  SpeakerDiagnostics,
  SpeakerInfo,
  SpeakerTarget,
  Todo,
  TranscriptEngineClient,
  Utterance,
} from "./types.ts";

export interface FakeEngineClientOptions {
  status?: EngineStatus;
  listeningStatus?: ListeningStatus;
  utterances?: Utterance[];
  speakers?: SpeakerInfo[];
  permissions?: EnginePermissions;
  selfEnrollment?: SelfEnrollmentStatus;
  modelDownload?: ModelDownloadStatus;
  organizerModel?: OrganizerModelStatus;
  memoMode?: MemoMode;
  memos?: MemoDetail[];
  // 今整理できるまとまりの数と、話が途切れるのを待っている発話の有無
  organizeBacklog?: { pendingChunks: number; isWaitingForSilence?: boolean };
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
  #speakers: SpeakerInfo[];
  #startError: Error | undefined;
  #permissions: EnginePermissions;
  #settingsOpened = 0;
  #selfEnrollment: SelfEnrollmentStatus;
  #enrollmentFailure: SelfEnrollmentFailure | undefined;
  #finishEnrollment: (() => void) | undefined;
  #rejectEnrollment: ((error: Error) => void) | undefined;
  #modelDownload: ModelDownloadStatus;
  #modelDownloadStarts = 0;
  #organizerModel: OrganizerModelStatus;
  #organizerModelStarts = 0;
  readonly #memoMode: MemoMode;
  #memos: MemoDetail[];
  #organize: OrganizeStatus;
  #organizeStarts = 0;
  #memoReadError: Error | undefined;
  #completeError: Error | undefined;

  constructor({
    status = { engineName: "fake-engine", apiVersion: 1, transcription: "ready" },
    listeningStatus = "stopped",
    utterances = [],
    speakers = [{ id: "1", name: "自分", kind: "self", utteranceCount: 0 }],
    permissions = { microphone: true, notifications: true },
    selfEnrollment = "enrolled",
    modelDownload,
    organizerModel = { state: "installed", downloadedBytes: 0, totalBytes: 0 },
    memoMode = "ready",
    memos = [],
    organizeBacklog = { pendingChunks: 0 },
  }: FakeEngineClientOptions = {}) {
    this.#organizerModel = organizerModel;
    this.#status = status;
    this.#listeningStatus = listeningStatus;
    this.#utterances = [...utterances];
    this.#speakers = [...speakers];
    this.#permissions = permissions;
    this.#selfEnrollment = selfEnrollment;
    this.#modelDownload =
      modelDownload ??
      (status.transcription === "ready"
        ? { state: "installed", downloadedBytes: 0, totalBytes: 0 }
        : { state: "idle", downloadedBytes: 0, totalBytes: 742_022_351 });
    this.#memoMode = memoMode;
    this.#memos = [...memos];
    this.#organize = {
      state: "idle",
      completedChunks: 0,
      totalChunks: 0,
      pendingChunks: organizeBacklog.pendingChunks,
      isWaitingForSilence: organizeBacklog.isWaitingForSilence ?? false,
      lastResult: null,
    };
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

  listSpeakers(): Promise<SpeakerInfo[]> {
    const counted = this.#speakers.map((speaker) => ({
      ...speaker,
      utteranceCount: this.#utterances.filter((item) => item.speakerId === speaker.id).length,
    }));
    return Promise.resolve(counted);
  }

  setUtteranceSpeaker(utteranceId: string, target: SpeakerTarget): Promise<void> {
    const speaker = this.#resolveTarget(target);
    if (speaker === undefined && target !== "unknown") {
      return Promise.reject(new Error("speaker-not-found"));
    }
    if (!this.#utterances.some((item) => item.id === utteranceId)) {
      return Promise.reject(new Error("speaker-not-found"));
    }
    this.#utterances = this.#utterances.map((item) => {
      if (item.id !== utteranceId) return item;
      const { speakerId: _id, speakerName: _name, ...rest } = item;
      if (speaker === undefined) return { ...rest, speaker: "unknown" };
      return {
        ...rest,
        speaker: speaker.kind === "self" ? "self" : "other",
        speakerId: speaker.id,
        speakerName: speaker.name,
      };
    });
    this.#emit({ type: "utterances-changed" });
    return Promise.resolve();
  }

  #resolveTarget(target: SpeakerTarget): SpeakerInfo | undefined {
    if (target === "self") return this.#speakers.find((item) => item.kind === "self");
    if (target === "new") {
      const id = String(Math.max(0, ...this.#speakers.map((item) => Number(item.id))) + 1);
      const provisional = this.#speakers.filter((item) => item.kind === "provisional").length;
      const created: SpeakerInfo = {
        id,
        name: `話者${provisional + 1}`,
        kind: "provisional",
        utteranceCount: 0,
      };
      this.#speakers = [...this.#speakers, created];
      return created;
    }
    return this.#speakers.find((item) => item.id === target);
  }

  mergeSpeakers(fromId: string, intoId: string): Promise<void> {
    const from = this.#speakers.find((item) => item.id === fromId);
    const into = this.#speakers.find((item) => item.id === intoId);
    if (!from || !into || from.kind === "self" || fromId === intoId) {
      return Promise.reject(new Error("speaker-not-found"));
    }
    this.#speakers = this.#speakers.filter((item) => item.id !== fromId);
    this.#utterances = this.#utterances.map((item) =>
      item.speakerId === fromId
        ? {
            ...item,
            speaker: into.kind === "self" ? "self" : "other",
            speakerId: intoId,
            speakerName: into.name,
          }
        : item,
    );
    this.#emit({ type: "utterances-changed" });
    return Promise.resolve();
  }

  renameSpeaker(speakerId: string, name: string): Promise<void> {
    const trimmed = name.trim();
    if (trimmed === "" || !this.#speakers.some((item) => item.id === speakerId)) {
      return Promise.reject(new Error("speaker-not-found"));
    }
    this.#speakers = this.#speakers.map((item) =>
      item.id === speakerId
        ? { ...item, name: trimmed, kind: item.kind === "self" ? "self" : "named" }
        : item,
    );
    this.#utterances = this.#utterances.map((item) =>
      item.speakerId === speakerId ? { ...item, speakerName: trimmed } : item,
    );
    this.#emit({ type: "utterances-changed" });
    return Promise.resolve();
  }

  getSpeakerDiagnostics(): Promise<SpeakerDiagnostics> {
    return Promise.resolve({
      days: 7,
      selfRuns: 6,
      otherRuns: 3,
      unknownRuns: 1,
      selfSeconds: 30,
      otherSeconds: 12,
      unknownSeconds: 2,
      histogram: [
        { floor: 0.3, self: 0, other: 3, unknown: 0 },
        { floor: 0.4, self: 1, other: 0, unknown: 1 },
        { floor: 0.5, self: 5, other: 0, unknown: 0 },
      ],
      provisionalSpeakers: this.#speakers.filter((item) => item.kind === "provisional").length,
      hasAdaptedVoice: false,
      selfThreshold: 0.45,
      unknownFloor: 0.35,
    });
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
    const organizer = this.#organizerModel;
    if (organizer.state === "running") {
      this.setOrganizerModel({
        state: "idle",
        downloadedBytes: organizer.completedBytes,
        totalBytes: organizer.totalBytes,
      });
    }
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

  getOrganizerModelStatus(): Promise<OrganizerModelStatus> {
    return Promise.resolve(this.#organizerModel);
  }

  startOrganizerModelDownload(): Promise<void> {
    const current = this.#organizerModel;
    if (current.state === "running" || current.state === "installed") return Promise.resolve();
    this.#organizerModelStarts += 1;
    this.setOrganizerModel({
      state: "running",
      phase: "downloading",
      completedBytes: current.downloadedBytes,
      totalBytes: current.totalBytes,
    });
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

  getOrganizeStatus(): Promise<OrganizeStatus> {
    return Promise.resolve({ ...this.#organize });
  }

  startOrganize(): Promise<OrganizeStartResult> {
    if (this.#organize.state !== "idle") return Promise.resolve("already-running");
    if (this.#organizerModel.state !== "installed") return Promise.resolve("model-unavailable");
    if (this.#organize.pendingChunks === 0) return Promise.resolve("nothing-to-organize");
    this.#organizeStarts += 1;
    this.#setOrganize({ state: "preparing", completedChunks: 0, totalChunks: 0, lastResult: null });
    return Promise.resolve("started");
  }

  stopOrganize(): Promise<void> {
    if (this.#organize.state !== "idle") this.#setOrganize({ state: "stopping" });
    return Promise.resolve();
  }

  get organizeStarts(): number {
    return this.#organizeStarts;
  }

  // 整理の進み具合を進める
  progressOrganize(completedChunks: number, totalChunks: number): void {
    const state = this.#organize.state === "stopping" ? "stopping" : "organizing";
    this.#setOrganize({ state, completedChunks, totalChunks });
  }

  // 整理を結果 result で終える
  finishOrganize(result: OrganizeResult, pendingChunks = 0): void {
    this.#setOrganize({
      state: "idle",
      completedChunks: 0,
      totalChunks: 0,
      pendingChunks,
      lastResult: result,
    });
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

  setOrganizerModel(status: OrganizerModelStatus): void {
    this.#organizerModel = status;
    this.#emit({ type: "organizer-model-changed", status });
  }

  get organizerModelStarts(): number {
    return this.#organizerModelStarts;
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

  #setOrganize(changes: Partial<OrganizeStatus>): void {
    this.#organize = { ...this.#organize, ...changes };
    this.#emit({ type: "organize-changed" });
  }

  #emit(event: EngineEvent): void {
    for (const listener of this.#listeners) listener(event);
  }
}
