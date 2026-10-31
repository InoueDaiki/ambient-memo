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
  | { type: "permissions-changed" }
  // メモの追加のほか、根拠の発話の更新でも届く。受け取ったら listMemos と getMemo で取り直す
  | { type: "memos-changed" }
  // ToDo の追加や完了で届く。受け取ったら listTodos と getMemo で取り直す
  | { type: "todos-changed" };

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

// メモと ToDo の実装の状態。意味は TranscriptionMode と同じ
export type MemoMode = "ready" | "development" | "unavailable";

// ToDo を最後まで読み取れたか。incomplete のメモは ToDo が欠けているかもしれない
export type TodoExtraction = "complete" | "incomplete";

export interface Memo {
  id: string;
  // 要約に失敗すると null。根拠の発話は残る
  title: string | null;
  summary: string | null;
  todoExtraction: TodoExtraction;
  // 最初の発話の開始時刻と、最後の発話の終了時刻(Unix 時間のミリ秒)
  startedAt: number;
  endedAt: number;
}

// - todo: 自分がこれからやること
// - reminder: 自分が日時を覚えておくべき予定
export type TodoKind = "todo" | "reminder";

// - candidate: 期限の確度が低い。人が確かめるまでリマインドしない。日付を捨てた場合は dueDate が null
// - open: 確定した ToDo。期限がないこともある
// - done: 完了した
export type TodoStatus = "candidate" | "open" | "done";

export interface Todo {
  id: string;
  memoId: string;
  task: string;
  kind: TodoKind;
  // 時差を持たない端末の日付(YYYY-MM-DD)
  dueDate: string | null;
  status: TodoStatus;
  // 記録した時刻(Unix 時間のミリ秒)
  createdAt: number;
}

export interface MemoDetail extends Memo {
  // 根拠の発話。古い順
  utterances: Utterance[];
  // このメモから抽出した ToDo。記録した順
  todos: Todo[];
}

// メモと ToDo を扱う実装。engine-bridge にはまだなく、今は Fake だけが提供する
export interface MemoEngineClient {
  getMemoMode(): Promise<MemoMode>;
  // 開始時刻の新しい順に最大 limit 件を返す
  listMemos(options: { limit: number }): Promise<Memo[]>;
  // ないメモは null を返す
  getMemo(id: string): Promise<MemoDetail | null>;
  // 未完了、期限の早い順、新しい順に最大 limit 件を返す。期限のない ToDo は同じ状態の中で最後にする
  listTodos(options: { limit: number }): Promise<Todo[]>;
  // すでに完了していれば何もせず成功する。ない ToDo は reject する。
  // 状態が変わるときは todos-changed を届けてから解決する
  completeTodo(id: string): Promise<void>;
  // 戻り値を呼ぶと購読をやめる
  subscribe(listener: EngineListener): () => void;
}
