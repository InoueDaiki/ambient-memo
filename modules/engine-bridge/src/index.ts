import {
  EngineStartError,
  SelfEnrollmentError,
  type EngineEvent,
  type EngineListener,
  type EnginePermissions,
  type EngineStartFailure,
  type EngineStatus,
  type ListeningStatus,
  type SelfEnrollmentFailure,
  type SelfEnrollmentStatus,
  type ModelDownloadStatus,
  type OrganizerModelStatus,
  type MemoEngineClient,
  type TranscriptEngineClient,
} from "@ambient-memo/engine-client";
import { requireNativeModule, type EventSubscription } from "expo";

import {
  createMemoClient,
  toUtterance,
  type MemoNativeModule,
  type NativeUtterance,
} from "./memo-client.ts";

interface EngineBridgeNativeModule extends MemoNativeModule {
  getStatus(): Promise<EngineStatus>;
  getListeningStatus(): Promise<ListeningStatus>;
  getPermissions(): Promise<EnginePermissions>;
  openPermissionSettings(): Promise<void>;
  startListening(): Promise<void>;
  stopListening(): Promise<void>;
  listUtterances(limit: number): Promise<NativeUtterance[]>;
  getSelfEnrollmentStatus(): Promise<SelfEnrollmentStatus>;
  enrollSelf(): Promise<void>;
  cancelSelfEnrollment(): Promise<void>;
  getModelDownloadStatus(): Promise<ModelDownloadStatus>;
  startModelDownload(): Promise<void>;
  cancelModelDownload(): Promise<void>;
  getOrganizerModelStatus(): Promise<OrganizerModelStatus>;
  startOrganizerModelDownload(): Promise<void>;
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

const ENROLLMENT_FAILURES: readonly SelfEnrollmentFailure[] = [
  "cancelled",
  "listening",
  "microphone-permission",
  "microphone-unavailable",
  "not-enough-speech",
  "speaker-model-unavailable",
];

function toEnrollmentError(error: unknown): unknown {
  const code = (error as { code?: unknown } | undefined)?.code;
  const failure = ENROLLMENT_FAILURES.find((item) => item === code);
  return failure === undefined ? error : new SelfEnrollmentError(failure);
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
    listUtterances: ({ limit }) =>
      native.listUtterances(limit).then((items) => items.map(toUtterance)),
    getSelfEnrollmentStatus: () => native.getSelfEnrollmentStatus(),
    enrollSelf: () =>
      native.enrollSelf().catch((error: unknown) => {
        throw toEnrollmentError(error);
      }),
    cancelSelfEnrollment: () => native.cancelSelfEnrollment(),
    getModelDownloadStatus: () => native.getModelDownloadStatus(),
    startModelDownload: () => native.startModelDownload(),
    cancelModelDownload: () => native.cancelModelDownload(),
    getOrganizerModelStatus: () => native.getOrganizerModelStatus(),
    startOrganizerModelDownload: () => native.startOrganizerModelDownload(),
    subscribe: (listener: EngineListener) => {
      const subscription = native.addListener("engine", listener);
      return () => subscription.remove();
    },
  };
}

export function createMemoEngineClient(): MemoEngineClient {
  return createMemoClient(requireNativeModule<EngineBridgeNativeModule>("EngineBridge"));
}
