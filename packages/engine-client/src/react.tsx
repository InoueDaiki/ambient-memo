// 画面からエンジンを使う hooks。engine-bridge と Fake のどちらの実装でも同じように動く
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

import type {
  EnginePermissions,
  ListeningStatus,
  TranscriptEngineClient,
  TranscriptionMode,
  Utterance,
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
    client
      .getStatus()
      .then((status) => {
        if (active) setMode(status.transcription);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [client]);

  return mode;
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
