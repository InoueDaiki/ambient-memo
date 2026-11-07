// 画面からエンジンを使う hooks。engine-bridge と Fake のどちらの実装でも同じように動く
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

import {
  SelfEnrollmentError,
  type AskPhase,
  type EngineEvent,
  type EnginePermissions,
  type ListeningStatus,
  type Memo,
  type MemoAnswer,
  type MemoDetail,
  type MemoEngineClient,
  type MemoMode,
  type OrganizeStartResult,
  type OrganizeStatus,
  type ModelDownloadStatus,
  type OrganizerModelStatus,
  type SelfEnrollmentFailure,
  type SelfEnrollmentStatus,
  type DiagnosticsStatus,
  type SpeakerDiagnostics,
  type SpeakerInfo,
  type SpeakerTarget,
  type TranscriptEngineClient,
  type TranscriptionMode,
  type Utterance,
} from "./types.ts";

const EngineClientContext = createContext<TranscriptEngineClient | undefined>(undefined);

export function EngineClientProvider({
  client,
  children,
}: {
  client: TranscriptEngineClient;
  children: ReactNode;
}) {
  return <EngineClientContext.Provider value={client}>{children}</EngineClientContext.Provider>;
}

export function useEngineClient(): TranscriptEngineClient {
  const client = useContext(EngineClientContext);
  if (!client) throw new Error("EngineClientProvider の内側で使う");
  return client;
}

export interface UseListeningResult {
  // 最初の取得が終わるまでは undefined
  status: ListeningStatus | undefined;
  // 開始や停止の結果を待っている間は true
  isChanging: boolean;
  // 最後の取得、開始、停止の失敗。次の操作で消える
  error: unknown;
  // エンジンの都合で止まったときに true。次の操作で消える
  interrupted: boolean;
  start: () => void;
  stop: () => void;
}

export function useListening(): UseListeningResult {
  const client = useEngineClient();
  const [status, setStatus] = useState<ListeningStatus>();
  const [isChanging, setIsChanging] = useState(false);
  const [error, setError] = useState<unknown>();
  const [interrupted, setInterrupted] = useState(false);

  useEffect(() => {
    let active = true;
    // 取得の結果より新しいイベントを上書きしないよう、イベントが来たら取得の結果を捨てる
    let hasEvent = false;
    const unsubscribe = client.subscribe((event) => {
      if (event.type !== "listening-changed") return;
      hasEvent = true;
      setStatus(event.status);
      setInterrupted(event.interrupted === true);
    });
    client
      .getListeningStatus()
      .then((current) => {
        if (active && !hasEvent) setStatus(current);
      })
      .catch((e: unknown) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  const change = useCallback((action: () => Promise<void>) => {
    setIsChanging(true);
    setError(undefined);
    setInterrupted(false);
    action()
      .catch(setError)
      .finally(() => setIsChanging(false));
  }, []);

  const start = useCallback(() => change(() => client.startListening()), [change, client]);
  const stop = useCallback(() => change(() => client.stopListening()), [change, client]);

  return { status, isChanging, error, interrupted, start, stop };
}

export interface UsePermissionsResult {
  // 最初の取得が終わるまでは undefined
  permissions: EnginePermissions | undefined;
  openSettings: () => void;
}

export function usePermissions(): UsePermissionsResult {
  const client = useEngineClient();
  const [permissions, setPermissions] = useState<EnginePermissions>();

  useEffect(() => {
    let active = true;
    const load = () => {
      client
        .getPermissions()
        .then((current) => {
          if (active) setPermissions(current);
        })
        // 案内が出ないだけで、開始の操作のときに改めて確かめる
        .catch(() => undefined);
    };
    const unsubscribe = client.subscribe((event) => {
      if (event.type === "permissions-changed") load();
    });
    load();
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  const openSettings = useCallback(() => {
    client.openPermissionSettings().catch(() => undefined);
  }, [client]);

  return { permissions, openSettings };
}

// 最初の取得が終わるまでと、取得に失敗したときは undefined
export function useTranscriptionMode(): TranscriptionMode | undefined {
  const client = useEngineClient();
  const [mode, setMode] = useState<TranscriptionMode>();

  useEffect(() => {
    let active = true;
    const load = () => {
      client
        .getStatus()
        .then((status) => {
          if (active) setMode(status.transcription);
        })
        .catch(() => undefined);
    };
    // 取得が終わったときと、確認で壊れたモデルが消えたときに変わる
    const unsubscribe = client.subscribe((event) => {
      if (event.type === "model-download-changed" && event.status.state !== "running") load();
    });
    load();
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  return mode;
}

export interface UseSelfEnrollmentResult {
  // 最初の取得が終わるまでと、取得に失敗したときは undefined
  status: SelfEnrollmentStatus | undefined;
  // 聞き取りの途中は true
  isEnrolling: boolean;
  // 最後の登録の失敗。止める操作は失敗に含めない。次の操作で消える
  failure: SelfEnrollmentFailure | "unknown" | undefined;
  // 今回の登録で使えた発話の数
  acceptedSamples: number;
  // 最後の発話が短いなどの理由で使えなかったら true
  isLastRejected: boolean;
  // 今回の登録が終わったら true
  isCompleted: boolean;
  enroll: () => void;
  cancel: () => void;
}

export function useSelfEnrollment(): UseSelfEnrollmentResult {
  const client = useEngineClient();
  const [status, setStatus] = useState<SelfEnrollmentStatus>();
  const [isEnrolling, setIsEnrolling] = useState(false);
  const [failure, setFailure] = useState<UseSelfEnrollmentResult["failure"]>();
  const [acceptedSamples, setAcceptedSamples] = useState(0);
  const [isLastRejected, setIsLastRejected] = useState(false);
  const [isCompleted, setIsCompleted] = useState(false);

  useEffect(() => {
    let active = true;
    const load = () => {
      client
        .getSelfEnrollmentStatus()
        .then((current) => {
          if (active) setStatus(current);
        })
        .catch(() => undefined);
    };
    // 話者のモデルは取得で揃うので、そのときに unavailable から変わる
    const unsubscribe = client.subscribe((event) => {
      const isModelReady =
        event.type === "model-download-changed" && event.status.state !== "running";
      if (isModelReady || event.type === "self-enrollment-changed") load();
      if (event.type === "enrollment-progress") {
        setAcceptedSamples(event.acceptedSamples);
        setIsLastRejected(event.isRejected);
      }
    });
    load();
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  const enroll = useCallback(() => {
    setIsEnrolling(true);
    setFailure(undefined);
    setAcceptedSamples(0);
    setIsLastRejected(false);
    setIsCompleted(false);
    client
      .enrollSelf()
      .then(() => {
        setStatus("enrolled");
        setIsCompleted(true);
      })
      .catch((e: unknown) => {
        const reason = e instanceof SelfEnrollmentError ? e.failure : "unknown";
        if (reason !== "cancelled") setFailure(reason);
      })
      .finally(() => setIsEnrolling(false));
  }, [client]);

  const cancel = useCallback(() => {
    client.cancelSelfEnrollment().catch(() => undefined);
  }, [client]);

  return {
    status,
    isEnrolling,
    failure,
    acceptedSamples,
    isLastRejected,
    isCompleted,
    enroll,
    cancel,
  };
}

export interface UseModelDownloadResult {
  // 最初の取得が終わるまでは undefined
  status: ModelDownloadStatus | undefined;
  // 利用者の操作でだけ呼ぶ
  start: () => void;
  cancel: () => void;
}

// 開いただけでは取得を始めない
export function useModelDownload(): UseModelDownloadResult {
  const client = useEngineClient();
  const [status, setStatus] = useState<ModelDownloadStatus>();

  useEffect(() => {
    let active = true;
    // 取得の結果より新しいイベントを上書きしないよう、イベントが来たら取得の結果を捨てる
    let hasEvent = false;
    const unsubscribe = client.subscribe((event) => {
      if (event.type !== "model-download-changed") return;
      hasEvent = true;
      setStatus(event.status);
    });
    client
      .getModelDownloadStatus()
      .then((current) => {
        if (active && !hasEvent) setStatus(current);
      })
      // 案内が出ないだけで、聞き取りの開始のときに改めて確かめる
      .catch(() => undefined);
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  const start = useCallback(() => {
    client.startModelDownload().catch(() => {
      setStatus({
        state: "failed",
        failure: "unknown",
        shortageBytes: 0,
        downloadedBytes: 0,
        totalBytes: 0,
      });
    });
  }, [client]);

  const cancel = useCallback(() => {
    client.cancelModelDownload().catch(() => undefined);
  }, [client]);

  return { status, start, cancel };
}

export interface UseOrganizerModelResult {
  // 最初の取得が終わるまでは undefined
  status: OrganizerModelStatus | undefined;
  // 利用者の操作でだけ呼ぶ
  start: () => void;
  cancel: () => void;
}

// 開いただけでは取得を始めない
export function useOrganizerModel(): UseOrganizerModelResult {
  const client = useEngineClient();
  const [status, setStatus] = useState<OrganizerModelStatus>();

  useEffect(() => {
    let active = true;
    let hasEvent = false;
    const unsubscribe = client.subscribe((event) => {
      if (event.type !== "organizer-model-changed") return;
      hasEvent = true;
      setStatus(event.status);
    });
    client
      .getOrganizerModelStatus()
      .then((current) => {
        if (active && !hasEvent) setStatus(current);
      })
      // 案内が出ないだけで、聞き取りには影響しない
      .catch(() => undefined);
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  const start = useCallback(() => {
    client.startOrganizerModelDownload().catch(() => {
      setStatus({
        state: "failed",
        failure: "unknown",
        shortageBytes: 0,
        downloadedBytes: 0,
        totalBytes: 0,
      });
    });
  }, [client]);

  const cancel = useCallback(() => {
    client.cancelModelDownload().catch(() => undefined);
  }, [client]);

  return { status, start, cancel };
}

export interface UseUtterancesResult {
  // 新しい順。最初の取得が終わるまでは undefined
  utterances: Utterance[] | undefined;
  error: unknown;
}

export function useUtterances({ limit }: { limit: number }): UseUtterancesResult {
  const client = useEngineClient();
  const [utterances, setUtterances] = useState<Utterance[]>();
  const [error, setError] = useState<unknown>();

  useEffect(() => {
    let active = true;
    // 取得が重なったら、最後に始めた取得の結果だけを使う
    let latest = 0;
    const load = () => {
      const request = ++latest;
      client
        .listUtterances({ limit })
        .then((items) => {
          if (!active || request !== latest) return;
          setUtterances(items);
          setError(undefined);
        })
        .catch((e: unknown) => {
          if (active && request === latest) setError(e);
        });
    };
    const unsubscribe = client.subscribe((event) => {
      if (event.type === "utterances-changed") load();
    });
    load();
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client, limit]);

  return { utterances, error };
}

export interface UseSpeakersResult {
  // 最初の取得が終わるまでは undefined
  speakers: SpeakerInfo[] | undefined;
  error: unknown;
}

// 話者の統合や発話の直しでも utterances-changed が届く
export function useSpeakers(): UseSpeakersResult {
  const client = useEngineClient();
  const [speakers, setSpeakers] = useState<SpeakerInfo[]>();
  const [error, setError] = useState<unknown>();

  useEffect(() => {
    let active = true;
    let latest = 0;
    const load = () => {
      const request = ++latest;
      client
        .listSpeakers()
        .then((items) => {
          if (!active || request !== latest) return;
          setSpeakers(items);
          setError(undefined);
        })
        .catch((e: unknown) => {
          if (active && request === latest) setError(e);
        });
    };
    const unsubscribe = client.subscribe((event) => {
      if (event.type === "utterances-changed") load();
    });
    load();
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  return { speakers, error };
}

export interface UseSpeakerActionsResult {
  // 直している間は true
  isPending: boolean;
  // 最後の操作の失敗。次の操作で消える
  error: unknown;
  setUtteranceSpeaker: (utteranceId: string, target: SpeakerTarget) => Promise<boolean>;
  mergeSpeakers: (fromId: string, intoId: string) => Promise<boolean>;
  renameSpeaker: (speakerId: string, name: string) => Promise<boolean>;
  resetSpeakers: () => Promise<boolean>;
}

// 操作の結果は成功したかどうかで返す。失敗の中身は error に残す
export function useSpeakerActions(): UseSpeakerActionsResult {
  const client = useEngineClient();
  const [isPending, setIsPending] = useState(false);
  const [error, setError] = useState<unknown>();

  const run = useCallback((action: () => Promise<void>) => {
    setIsPending(true);
    setError(undefined);
    return action()
      .then(() => true)
      .catch((e: unknown) => {
        setError(e);
        return false;
      })
      .finally(() => setIsPending(false));
  }, []);

  const setUtteranceSpeaker = useCallback(
    (utteranceId: string, target: SpeakerTarget) =>
      run(() => client.setUtteranceSpeaker(utteranceId, target)),
    [client, run],
  );
  const mergeSpeakers = useCallback(
    (fromId: string, intoId: string) => run(() => client.mergeSpeakers(fromId, intoId)),
    [client, run],
  );
  const renameSpeaker = useCallback(
    (speakerId: string, name: string) => run(() => client.renameSpeaker(speakerId, name)),
    [client, run],
  );

  const resetSpeakers = useCallback(() => run(() => client.resetSpeakers()), [client, run]);

  return { isPending, error, setUtteranceSpeaker, mergeSpeakers, renameSpeaker, resetSpeakers };
}

// 開いたときの値を出す。調整の確認に使うだけなので、リセット以外の更新は追わない
export function useSpeakerDiagnostics(): SpeakerDiagnostics | undefined {
  const client = useEngineClient();
  const [diagnostics, setDiagnostics] = useState<SpeakerDiagnostics>();

  useEffect(() => {
    let active = true;
    const load = () => {
      client
        .getSpeakerDiagnostics()
        .then((current) => {
          if (active) setDiagnostics(current);
        })
        .catch(() => undefined);
    };
    const unsubscribe = client.subscribe((event) => {
      if (event.type === "self-enrollment-changed") load();
    });
    load();
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  return diagnostics;
}

export interface UseDiagnosticsResult {
  status: DiagnosticsStatus | undefined;
  isPending: boolean;
  hasFailed: boolean;
  // 直前の操作でダウンロードに保存できた
  hasSaved: boolean;
  start: () => Promise<void>;
  stop: () => Promise<void>;
  clear: () => Promise<void>;
  share: () => Promise<void>;
  save: () => Promise<void>;
}

// 記録の状態は操作のたびに返るので、イベントは流さない
export function useDiagnostics(): UseDiagnosticsResult {
  const client = useEngineClient();
  const [status, setStatus] = useState<DiagnosticsStatus>();
  const [isPending, setIsPending] = useState(false);
  const [hasFailed, setHasFailed] = useState(false);
  const [hasSaved, setHasSaved] = useState(false);

  useEffect(() => {
    let active = true;
    client
      .getDiagnosticsStatus()
      .then((current) => {
        if (active) setStatus(current);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [client]);

  const run = useCallback(async (action: () => Promise<DiagnosticsStatus | void>) => {
    setIsPending(true);
    setHasFailed(false);
    setHasSaved(false);
    try {
      const next = await action();
      if (next !== undefined) setStatus(next);
    } catch {
      setHasFailed(true);
    } finally {
      setIsPending(false);
    }
  }, []);

  return {
    status,
    isPending,
    hasFailed,
    hasSaved,
    start: useCallback(() => run(() => client.startDiagnostics()), [client, run]),
    stop: useCallback(() => run(() => client.stopDiagnostics()), [client, run]),
    clear: useCallback(() => run(() => client.clearDiagnostics()), [client, run]),
    // 書き出しは状態を返さない。ネイティブの戻り値(null)で状態を上書きしない
    share: useCallback(
      () =>
        run(async () => {
          await client.shareDiagnostics();
        }),
      [client, run],
    ),
    save: useCallback(
      () =>
        run(async () => {
          await client.saveDiagnostics();
          setHasSaved(true);
        }),
      [client, run],
    ),
  };
}

const MemoClientContext = createContext<MemoEngineClient | undefined>(undefined);

export function MemoClientProvider({
  client,
  children,
}: {
  client: MemoEngineClient;
  children: ReactNode;
}) {
  return <MemoClientContext.Provider value={client}>{children}</MemoClientContext.Provider>;
}

function useMemoClient(): MemoEngineClient {
  const client = useContext(MemoClientContext);
  if (!client) throw new Error("MemoClientProvider の内側で使う");
  return client;
}

// load が変わると取り直し、前の load の結果は返さない。
// load は useCallback で、refreshOn はモジュールの定数で固定して渡す
function useMemoQuery<T>(
  load: () => Promise<T>,
  refreshOn: readonly EngineEvent["type"][],
): { value: T | undefined; error: unknown } {
  const client = useMemoClient();
  const [loaded, setLoaded] = useState<{ load: () => Promise<T>; value: T }>();
  const [failed, setFailed] = useState<{ load: () => Promise<T>; error: unknown }>();

  useEffect(() => {
    let active = true;
    // 取得が重なったら、最後に始めた取得の結果だけを使う
    let latest = 0;
    const reload = () => {
      const request = ++latest;
      load()
        .then((value) => {
          if (!active || request !== latest) return;
          setLoaded({ load, value });
          setFailed(undefined);
        })
        .catch((error: unknown) => {
          if (active && request === latest) setFailed({ load, error });
        });
    };
    const unsubscribe = client.subscribe((event) => {
      if (refreshOn.includes(event.type)) reload();
    });
    reload();
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client, load, refreshOn]);

  return {
    value: loaded?.load === load ? loaded.value : undefined,
    error: failed?.load === load ? failed.error : undefined,
  };
}

const MEMO_EVENTS = ["memos-changed"] as const;
// 未処理の量は発話が増えても、メモになっても変わる
const ORGANIZE_EVENTS = ["organize-changed", "memos-changed", "utterances-changed"] as const;

// 最初の取得が終わるまでと、取得に失敗したときは undefined
export function useMemoMode(): MemoMode | undefined {
  const client = useMemoClient();
  const [mode, setMode] = useState<MemoMode>();

  useEffect(() => {
    let active = true;
    client
      .getMemoMode()
      .then((current) => {
        if (active) setMode(current);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [client]);

  return mode;
}

export interface UseMemosResult {
  // 新しい順。最初の取得が終わるまでは undefined
  memos: Memo[] | undefined;
  error: unknown;
}

export function useMemos({ limit }: { limit: number }): UseMemosResult {
  const client = useMemoClient();
  const load = useCallback(() => client.listMemos({ limit }), [client, limit]);
  const { value, error } = useMemoQuery(load, MEMO_EVENTS);
  return { memos: value, error };
}

export interface UseMemoDetailResult {
  // 最初の取得が終わるまでは undefined。メモがなければ null
  memo: MemoDetail | null | undefined;
  error: unknown;
}

export function useMemoDetail(id: string): UseMemoDetailResult {
  const client = useMemoClient();
  const load = useCallback(() => client.getMemo(id), [client, id]);
  const { value, error } = useMemoQuery(load, MEMO_EVENTS);
  return { memo: value, error };
}

export interface UseOrganizeResult {
  // 最初の取得が終わるまでと、取得に失敗したときは undefined
  status: OrganizeStatus | undefined;
  // 最後の開始の依頼の結果。始まらなかった理由を出すのに使う。次の操作で消える
  startResult: OrganizeStartResult | undefined;
  start: () => void;
  stop: () => void;
}

// 充電を待たない整理の操作と状態
export function useOrganize(): UseOrganizeResult {
  const client = useMemoClient();
  const load = useCallback(() => client.getOrganizeStatus(), [client]);
  const { value } = useMemoQuery(load, ORGANIZE_EVENTS);
  const [startResult, setStartResult] = useState<OrganizeStartResult>();

  const start = useCallback(() => {
    setStartResult(undefined);
    client
      .startOrganize()
      .then(setStartResult)
      .catch(() => setStartResult("failed"));
  }, [client]);

  const stop = useCallback(() => {
    client.stopOrganize().catch(() => undefined);
  }, [client]);

  return { status: value, startResult, start, stop };
}

export interface AskedQuestion {
  id: number;
  question: string;
  // 答えを待っている間は undefined
  answer: MemoAnswer | undefined;
  // askMemos が reject したとき。cancelled は利用者が止めた
  failure: "cancelled" | "unknown" | undefined;
}

export interface UseAskMemosResult {
  // フックが生きている間に聞いた質問。新しい順。保存しない
  questions: AskedQuestion[];
  // 答えを待っている間は true
  isAsking: boolean;
  // 動いている質問の段階。最初の ask-progress が届くまでは undefined
  phase: AskPhase | undefined;
  // 聞いた質問の id を返す。前後の空白を除いて空のときと、答えを待っている間は何もせず undefined
  ask: (question: string) => number | undefined;
  cancel: () => void;
}

// メモへの質問。答えを待っている間にフックが外れたら止める
export function useAskMemos(): UseAskMemosResult {
  const client = useMemoClient();
  const [questions, setQuestions] = useState<AskedQuestion[]>([]);
  const [phase, setPhase] = useState<AskPhase>();
  // 動いている質問の id。イベントと結果を、どの質問のものか見分ける
  const askingId = useRef<number | undefined>(undefined);
  const isCancelling = useRef(false);
  const nextId = useRef(0);
  const [isAsking, setIsAsking] = useState(false);

  useEffect(() => {
    const unsubscribe = client.subscribe((event) => {
      if (event.type === "ask-progress" && askingId.current !== undefined) setPhase(event.phase);
    });
    return () => {
      unsubscribe();
      if (askingId.current === undefined) return;
      askingId.current = undefined;
      client.cancelAsk().catch(() => undefined);
    };
  }, [client]);

  const ask = useCallback(
    (question: string) => {
      const trimmed = question.trim();
      if (trimmed === "" || askingId.current !== undefined) return undefined;
      const id = ++nextId.current;
      askingId.current = id;
      isCancelling.current = false;
      setIsAsking(true);
      setPhase(undefined);
      setQuestions((current) => [
        { id, question: trimmed, answer: undefined, failure: undefined },
        ...current,
      ]);
      const settle = (changes: Pick<AskedQuestion, "answer" | "failure">) => {
        // 画面を離れて止めた質問の結果は捨てる
        if (askingId.current !== id) return;
        askingId.current = undefined;
        setIsAsking(false);
        setPhase(undefined);
        setQuestions((current) =>
          current.map((item) => (item.id === id ? { ...item, ...changes } : item)),
        );
      };
      client
        .askMemos(trimmed)
        .then((answer) => settle({ answer, failure: undefined }))
        .catch(() =>
          settle({ answer: undefined, failure: isCancelling.current ? "cancelled" : "unknown" }),
        );
      return id;
    },
    [client],
  );

  const cancel = useCallback(() => {
    if (askingId.current === undefined) return;
    isCancelling.current = true;
    client.cancelAsk().catch(() => undefined);
  }, [client]);

  return { questions, isAsking, phase, ask, cancel };
}
