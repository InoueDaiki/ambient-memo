// 画面から見たエンジンの境界。Android 固有の型や概念を持ち込まない

// 文字起こしの実装の状態
// - ready: 本物の認識で動く
// - development: 開発用の Fake で動く。画面でそのことを知らせる
// - unavailable: 実装がなく、聞き取りを始められない
export type TranscriptionMode = "ready" | "development" | "unavailable";

export interface EngineStatus {
  engineName: string;
  apiVersion: number;
  transcription: TranscriptionMode;
}

export interface EngineClient {
  getStatus(): Promise<EngineStatus>;
}

export type ListeningStatus = "stopped" | "listening";

// 今は「自分」か「それ以外」だけ。名前の推定を足すときに広げる
export type Speaker = "self" | "other";

export interface Utterance {
  id: string;
  text: string;
  speaker: Speaker;
  // 発話の開始時刻(Unix 時間のミリ秒)
  startedAt: number;
}

export interface EnginePermissions {
  microphone: boolean;
  notifications: boolean;
}

export type EngineStartFailure =
  // マイクの利用が許可されていない
  | "microphone-permission"
  // マイクを開けなかった
  | "microphone-unavailable"
  // 文字起こしの実装がない
  | "transcription-unavailable";

// startListening の reject で、理由が分かるときに使う
export class EngineStartError extends Error {
  readonly failure: EngineStartFailure;

  constructor(failure: EngineStartFailure) {
    super(failure);
    this.name = "EngineStartError";
    this.failure = failure;
  }
}

export type EngineEvent =
  // 画面の操作だけでなく、エンジンの都合(マイクの占有など)でも変わる。
  // interrupted は、操作ではなくエンジンの都合で止まったときに true
  | { type: "listening-changed"; status: ListeningStatus; interrupted?: boolean }
  // 発話の追加のほか、話者の更新でも届く。受け取ったら listUtterances で取り直す
  | { type: "utterances-changed" }
  // 受け取ったら getPermissions で取り直す
  | { type: "permissions-changed" };

export type EngineListener = (event: EngineEvent) => void;

// 聞き取りと文字起こしを扱う実装。本物は engine-bridge が提供する
export interface TranscriptEngineClient extends EngineClient {
  getListeningStatus(): Promise<ListeningStatus>;
  getPermissions(): Promise<EnginePermissions>;
  // 端末の設定の、このアプリの画面を開く
  openPermissionSettings(): Promise<void>;
  // 開始と停止は、すでにその状態なら何もせず成功する。
  // 状態が変わるときは listening-changed を届けてから解決する。始められないときは reject する。
  // 開始は、未許可の権限があれば OS の確認を出す
  startListening(): Promise<void>;
  stopListening(): Promise<void>;
  // 新しい順に最大 limit 件を返す
  listUtterances(options: { limit: number }): Promise<Utterance[]>;
  // 戻り値を呼ぶと購読をやめる
  subscribe(listener: EngineListener): () => void;
}
