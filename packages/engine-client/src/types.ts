// 画面から見たエンジンの境界。Android 固有の型や概念を持ち込まない

export interface EngineStatus {
  engineName: string;
  apiVersion: number;
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

export type EngineEvent =
  // 画面の操作だけでなく、エンジンの都合(マイクの占有など)でも変わる
  | { type: "listening-changed"; status: ListeningStatus }
  // 発話の追加のほか、話者の更新でも届く。受け取ったら listUtterances で取り直す
  | { type: "utterances-changed" };

export type EngineListener = (event: EngineEvent) => void;

// 聞き取りと文字起こしを扱う実装。本物はエンジンの起動中に engine-bridge が提供する
export interface TranscriptEngineClient extends EngineClient {
  getListeningStatus(): Promise<ListeningStatus>;
  // 開始と停止は、すでにその状態なら何もせず成功する。
  // 状態が変わるときは listening-changed を届けてから解決する。始められないときは reject する
  startListening(): Promise<void>;
  stopListening(): Promise<void>;
  // 新しい順に最大 limit 件を返す
  listUtterances(options: { limit: number }): Promise<Utterance[]>;
  // 戻り値を呼ぶと購読をやめる
  subscribe(listener: EngineListener): () => void;
}
