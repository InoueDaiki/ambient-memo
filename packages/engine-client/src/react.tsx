// 画面からエンジンを使う hooks。engine-bridge と Fake のどちらの実装でも同じように動く
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

import {
  SelfEnrollmentError,
  type EngineEvent,
  type EnginePermissions,
  type ListeningStatus,
  type Memo,
  type MemoDetail,
  type MemoEngineClient,
  type MemoMode,
  type ModelDownloadStatus,
  type OrganizerModelStatus,
  type SelfEnrollmentFailure,
  type SelfEnrollmentStatus,
  type Todo,
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
  enroll: () => void;
  cancel: () => void;
}

export function useSelfEnrollment(): UseSelfEnrollmentResult {
  const client = useEngineClient();
  const [status, setStatus] = useState<SelfEnrollmentStatus>();
  const [isEnrolling, setIsEnrolling] = useState(false);
  const [failure, setFailure] = useState<UseSelfEnrollmentResult["failure"]>();

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
      if (event.type === "model-download-changed" && event.status.state !== "running") load();
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
    client
      .enrollSelf()
      .then(() => setStatus("enrolled"))
      .catch((e: unknown) => {
        const reason = e instanceof SelfEnrollmentError ? e.failure : "unknown";
        if (reason !== "cancelled") setFailure(reason);
      })
      .finally(() => setIsEnrolling(false));
  }, [client]);

  const cancel = useCallback(() => {
    client.cancelSelfEnrollment().catch(() => undefined);
  }, [client]);

  return { status, isEnrolling, failure, enroll, cancel };
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
const TODO_EVENTS = ["todos-changed"] as const;
const MEMO_DETAIL_EVENTS = ["memos-changed", "todos-changed"] as const;

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
  const { value, error } = useMemoQuery(load, MEMO_DETAIL_EVENTS);
  return { memo: value, error };
}

export interface UseTodosResult {
  // 未完了、期限の早い順。最初の取得が終わるまでは undefined
  todos: Todo[] | undefined;
  error: unknown;
}

export function useTodos({ limit }: { limit: number }): UseTodosResult {
  const client = useMemoClient();
  const load = useCallback(() => client.listTodos({ limit }), [client, limit]);
  const { value, error } = useMemoQuery(load, TODO_EVENTS);
  return { todos: value, error };
}

export interface UseCompleteTodoResult {
  complete: (id: string) => void;
  // 結果を待っている ToDo の id
  pendingIds: ReadonlySet<string>;
  // 最後の完了の失敗。次の操作で消える
  error: unknown;
}

export function useCompleteTodo(): UseCompleteTodoResult {
  const client = useMemoClient();
  const [pendingIds, setPendingIds] = useState<ReadonlySet<string>>(new Set());
  const [error, setError] = useState<unknown>();

  const complete = useCallback(
    (id: string) => {
      setPendingIds((current) => new Set(current).add(id));
      setError(undefined);
      client
        .completeTodo(id)
        .catch(setError)
        .finally(() => {
          setPendingIds((current) => {
            const next = new Set(current);
            next.delete(id);
            return next;
          });
        });
    },
    [client],
  );

  return { complete, pendingIds, error };
}
