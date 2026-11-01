// 画面から見たエンジンの境界。Android 固有の型や概念を持ち込まない

// 文字起こしの状態
// - ready: モデルが端末にあり、聞き取りを始められる
// - unavailable: モデルが端末になく、聞き取りを始められない
export type TranscriptionMode = "ready" | "unavailable";

export interface EngineStatus {
  engineName: string;
  apiVersion: number;
  transcription: TranscriptionMode;
}

export interface EngineClient {
  getStatus(): Promise<EngineStatus>;
}

export type ListeningStatus = "stopped" | "listening";

// - self: 登録した自分の声
// - other: 自分以外。仮の話者か、名前の分かった話者
// - unknown: 判定していないか、判定できなかった
export type Speaker = "self" | "other" | "unknown";

export interface Utterance {
  id: string;
  text: string;
  speaker: Speaker;
  // 話者の id。unknown の発話にはない
  speakerId?: string;
  // 表示する話者の名前(「話者1」など)。unknown の発話にはない
  speakerName?: string;
  // 発話の開始時刻(Unix 時間のミリ秒)
  startedAt: number;
}

// - self: 登録した自分
// - provisional: 特徴量のまとまりに付けた仮の話者。増えすぎるとエンジンがまとめ直す
// - named: 名前の付いた話者。エンジンは勝手に統合しない
export type SpeakerKind = "self" | "provisional" | "named";

export interface SpeakerInfo {
  id: string;
  name: string;
  kind: SpeakerKind;
  utteranceCount: number;
}

// 発話を手で直す先
// - self: 自分
// - unknown: 不明
// - new: 新しい仮の話者。仮の話者の上限に達していれば reject する
// - それ以外: 話者の id
export type SpeakerTarget = "self" | "unknown" | "new" | (string & {});

// 仮の話者の上限。画面が「新しい話者」を出すかどうかに使う。値はエンジンの既定値と揃える
export const MAX_PROVISIONAL_SPEAKERS = 3;

// 類似度の帯ごとの判定の数。floor は帯の下端
export interface SpeakerScoreBand {
  floor: number;
  self: number;
  other: number;
  unknown: number;
}

// 端末の中で話者の判定を確かめる値。音声も特徴量も含まない
export interface SpeakerDiagnostics {
  days: number;
  selfRuns: number;
  otherRuns: number;
  unknownRuns: number;
  selfSeconds: number;
  otherSeconds: number;
  unknownSeconds: number;
  histogram: SpeakerScoreBand[];
  provisionalSpeakers: number;
  hasAdaptedVoice: boolean;
  selfThreshold: number;
  unknownFloor: number;
}

// 実機の確認のための記録。数値だけを持ち、発話の本文や音声は含まない
// - endsAt: 記録が止まる時刻。止めたときは止めた時刻。startedAt と一緒に、記録がないときはない
// - isFull: 大きさの上限に達して、新しい記録を残していない
export interface DiagnosticsStatus {
  isRecording: boolean;
  startedAt?: number;
  endsAt?: number;
  sizeBytes: number;
  isFull: boolean;
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

// 自分の声の登録の状態
// - enrolled: 登録済み
// - not-enrolled: 未登録
// - unavailable: 話者のモデルが端末になく、登録できない
export type SelfEnrollmentStatus = "enrolled" | "not-enrolled" | "unavailable";

export type SelfEnrollmentFailure =
  // cancelSelfEnrollment で止めた。前の登録はそのまま
  | "cancelled"
  // 聞き取り中か、別の登録の途中
  | "listening"
  | "microphone-permission"
  | "microphone-unavailable"
  // 時間内に十分な長さの発話を聞き取れなかった
  | "not-enough-speech"
  | "speaker-model-unavailable";

// enrollSelf の reject で、理由が分かるときに使う
export class SelfEnrollmentError extends Error {
  readonly failure: SelfEnrollmentFailure;

  constructor(failure: SelfEnrollmentFailure) {
    super(failure);
    this.name = "SelfEnrollmentError";
    this.failure = failure;
  }
}

export type ModelDownloadPhase =
  // 配布物を取得している
  | "downloading"
  // 取得した配布物が正しいことを確かめている
  | "verifying"
  // 使うファイルを取り出している
  | "extracting";

export type ModelDownloadFailure =
  // 通信が切れたか、取得先が応じなかった。続きから取得できる
  | "network"
  // 配布物が期待と違った。途中までのファイルは消えたので、最初から取得する
  | "corrupted"
  // 端末の空き容量が足りない
  | "insufficient-storage"
  | "unknown";

// バイト数は Number の整数で足りる範囲に収まる
export type ModelDownloadStatus =
  // モデルが揃っている
  | { state: "installed"; downloadedBytes: number; totalBytes: number }
  // 取得していない。downloadedBytes が 0 より大きければ、続きから取得できる
  | { state: "idle"; downloadedBytes: number; totalBytes: number }
  // completedBytes と totalBytes は phase の進み具合。totalBytes が 0 の間は準備中
  | { state: "running"; phase: ModelDownloadPhase; completedBytes: number; totalBytes: number }
  // shortageBytes は空き容量の不足分。分からないときは 0
  | {
      state: "failed";
      failure: ModelDownloadFailure;
      shortageBytes: number;
      downloadedBytes: number;
      totalBytes: number;
    };

// 整理のモデルは数 GB あるので、文字起こしのモデルとは別に取得する
export type OrganizerModelStatus = ModelDownloadStatus;

export type EngineEvent =
  // 画面の操作だけでなく、エンジンの都合(マイクの占有など)でも変わる。
  // interrupted は、操作ではなくエンジンの都合で止まったときに true
  | { type: "listening-changed"; status: ListeningStatus; interrupted?: boolean }
  // 発話の追加のほか、話者の更新でも届く。受け取ったら listUtterances で取り直す
  | { type: "utterances-changed" }
  // 声の登録やリセットで届く。受け取ったら getSelfEnrollmentStatus で取り直す
  | { type: "self-enrollment-changed" }
  // 声の登録の途中で届く。isRejected は、話した発話が短いなどの理由で使えなかったとき
  | { type: "enrollment-progress"; acceptedSamples: number; isRejected: boolean }
  // 受け取ったら getPermissions で取り直す
  | { type: "permissions-changed" }
  // 進み具合もこれで届く
  | { type: "model-download-changed"; status: ModelDownloadStatus }
  | { type: "organizer-model-changed"; status: OrganizerModelStatus }
  // メモの追加のほか、根拠の発話の更新でも届く。受け取ったら listMemos と getMemo で取り直す
  | { type: "memos-changed" }
  // ToDo の追加や完了で届く。受け取ったら listTodos と getMemo で取り直す
  | { type: "todos-changed" }
  // 整理の開始、進み具合、終わりで届く。受け取ったら getOrganizeStatus で取り直す
  | { type: "organize-changed" };

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
  // id の順に返す
  listSpeakers(): Promise<SpeakerInfo[]>;
  // 発話の話者を直す。以後の判定にも反映する。終わると utterances-changed が届く。ない発話や話者は reject する
  setUtteranceSpeaker(utteranceId: string, target: SpeakerTarget): Promise<void>;
  // fromId の発話を intoId へ移し、fromId を消す。自分は統合元にできない。取り消せない
  mergeSpeakers(fromId: string, intoId: string): Promise<void>;
  // 自分を含むすべての話者と判定の記録を消し、発話を話者なしに戻す。文字起こしとメモは残す。
  // 聞き取りや登録の途中は reject する。取り消せない
  resetSpeakers(): Promise<void>;
  // 手で名前を付ける。空の名前は reject する。自分の名前も変えられるが、整理では「自分」のまま
  renameSpeaker(speakerId: string, name: string): Promise<void>;
  // 直近の話者の判定の集計を返す
  getSpeakerDiagnostics(): Promise<SpeakerDiagnostics>;
  getDiagnosticsStatus(): Promise<DiagnosticsStatus>;
  // 前の記録を消して始める。止めなくても 7 日で止まる
  startDiagnostics(): Promise<DiagnosticsStatus>;
  stopDiagnostics(): Promise<DiagnosticsStatus>;
  clearDiagnostics(): Promise<DiagnosticsStatus>;
  // OS の共有の画面を開く。送り先は利用者が選ぶので、操作でだけ呼ぶ。記録がなければ reject する
  shareDiagnostics(): Promise<void>;
  getSelfEnrollmentStatus(): Promise<SelfEnrollmentStatus>;
  // 自分の声を聞き取って登録する。登録済みなら置き換える。声は端末の中だけで扱い、音声は残さない。
  // 未許可のマイクの権限があれば OS の確認を出す。登録できないときは SelfEnrollmentError で reject する
  enrollSelf(): Promise<void>;
  // 登録の途中なら止める。enrollSelf は cancelled で reject する
  cancelSelfEnrollment(): Promise<void>;
  getModelDownloadStatus(): Promise<ModelDownloadStatus>;
  // 文字起こしと話者のモデルが対象。取得を始めて、終わるのを待たずに解決する。結果は model-download-changed で届く。
  // 通信はここからしか始まらないので、利用者の操作でだけ呼ぶ。取得中とモデルが揃っているときは何もしない
  startModelDownload(): Promise<void>;
  // 途中までのファイルは残るので、次は続きから取得する
  // 動いている取得を止める。文字起こしのモデルにも整理のモデルにも使う
  cancelModelDownload(): Promise<void>;
  getOrganizerModelStatus(): Promise<OrganizerModelStatus>;
  // startModelDownload と同じ約束で、結果は organizer-model-changed で届く。取得は同時に 1 本だけ
  startOrganizerModelDownload(): Promise<void>;
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

// - idle: 動いていない
// - preparing: モデルを読み込んでいる
// - organizing: まとまりを処理している
// - stopping: 止める依頼を受け、処理中のまとまりが終わるのを待っている
export type OrganizeState = "idle" | "preparing" | "organizing" | "stopping";

// 手動の整理の最後の結果
// - completed: 今処理できるまとまりをすべて処理した
// - stopped: 利用者が止めた。残りは次の整理で続ける
// - too-hot: 端末が熱くなったので止めた
// - model-unavailable: 整理のモデルがなかった
// - failed: 実行基盤が動かなかったか、整理のプロセスが落ちた。聞き取りには影響しない
// - time-limit: OS の実行時間の上限に達した
export type OrganizeResult =
  | "completed"
  | "stopped"
  | "too-hot"
  | "model-unavailable"
  | "failed"
  | "time-limit";

export interface OrganizeStatus {
  state: OrganizeState;
  // 動いている実行の進み具合。対象が決まるまで totalChunks は 0
  completedChunks: number;
  totalChunks: number;
  // 今整理できるまとまりの数
  pendingChunks: number;
  // 話が続いているかもしれない発話が、途切れるのを待っている
  isWaitingForSilence: boolean;
  // プロセスの再起動では消える。通知でも伝える
  lastResult: OrganizeResult | null;
}

export type OrganizeStartResult =
  | "started"
  | "already-running"
  | "model-unavailable"
  | "nothing-to-organize"
  | "failed";

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
  getOrganizeStatus(): Promise<OrganizeStatus>;
  // 充電を待たずに整理する。利用者の操作でだけ呼ぶ。終わるのを待たずに解決し、結果は organize-changed で届く。
  // 通知の許可がなければ OS の確認を出す
  startOrganize(): Promise<OrganizeStartResult>;
  // まとまりの区切りで止める。動いていなければ何もしない
  stopOrganize(): Promise<void>;
  // 戻り値を呼ぶと購読をやめる
  subscribe(listener: EngineListener): () => void;
}
