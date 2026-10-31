import {
  EngineStartError,
  type EngineEvent,
  type EngineListener,
  type EnginePermissions,
  type EngineStartFailure,
  type EngineStatus,
  type ListeningStatus,
  type TranscriptEngineClient,
  type Utterance,
} from "@ambient-memo/engine-client";
import { requireNativeModule, type EventSubscription } from "expo";

interface EngineBridgeNativeModule {
  getStatus(): Promise<EngineStatus>;
  getListeningStatus(): Promise<ListeningStatus>;
  getPermissions(): Promise<EnginePermissions>;
  openPermissionSettings(): Promise<void>;
  startListening(): Promise<void>;
  stopListening(): Promise<void>;
  listUtterances(limit: number): Promise<Utterance[]>;
  addListener(eventName: "engine", listener: (event: EngineEvent) => void): EventSubscription;
}

const START_FAILURES: readonly EngineStartFailure[] = [
  "microphone-permission",
  "microphone-unavailable",
  "transcription-unavailable",
];

// ネイティブの例外のコードを、画面が見分けられるエラーに変える
function toStartError(error: unknown): unknown {
  const code = (error as { code?: unknown } | undefined)?.code;
  const failure = START_FAILURES.find((item) => item === code);
  return failure === undefined ? error : new EngineStartError(failure);
}

export function createEngineClient(): TranscriptEngineClient {
  const native = requireNativeModule<EngineBridgeNativeModule>("EngineBridge");
  return {
    getStatus: () => native.getStatus(),
    getListeningStatus: () => native.getListeningStatus(),
    getPermissions: () => native.getPermissions(),
    openPermissionSettings: () => native.openPermissionSettings(),
    startListening: () =>
      native.startListening().catch((error: unknown) => {
        throw toStartError(error);
      }),
    stopListening: () => native.stopListening(),
    listUtterances: ({ limit }) => native.listUtterances(limit),
    subscribe: (listener: EngineListener) => {
      const subscription = native.addListener("engine", listener);
      return () => subscription.remove();
    },
  };
}
