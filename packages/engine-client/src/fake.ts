import { SelfEnrollmentError } from "./types.ts";
import type {
  EngineEvent,
  EngineListener,
  EnginePermissions,
  EngineStatus,
  ListeningStatus,
  SelfEnrollmentFailure,
  SelfEnrollmentStatus,
  TranscriptEngineClient,
  Utterance,
} from "./types.ts";

export interface FakeEngineClientOptions {
  status?: EngineStatus;
  listeningStatus?: ListeningStatus;
  utterances?: Utterance[];
  permissions?: EnginePermissions;
  selfEnrollment?: SelfEnrollmentStatus;
}

// エンジンなしで画面を開発・テストするための実装
export class FakeEngineClient implements TranscriptEngineClient {
  readonly #status: EngineStatus;
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

  constructor({
    status = { engineName: "fake-engine", apiVersion: 1, transcription: "ready" },
    listeningStatus = "stopped",
    utterances = [],
    permissions = { microphone: true, notifications: true },
    selfEnrollment = "enrolled",
  }: FakeEngineClientOptions = {}) {
    this.#status = status;
    this.#listeningStatus = listeningStatus;
    this.#utterances = [...utterances];
    this.#permissions = permissions;
    this.#selfEnrollment = selfEnrollment;
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

  #emit(event: EngineEvent): void {
    for (const listener of this.#listeners) listener(event);
  }
}
