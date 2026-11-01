// メモと ToDo の境界。ネイティブの形は engine/android の MemoBridge と合わせる。
// expo に依存させず、Vitest で契約テストを流す
import type {
  EngineEvent,
  MemoDetail,
  MemoEngineClient,
  OrganizeStartResult,
  OrganizeStatus,
  Utterance,
} from "@ambient-memo/engine-client";

// ネイティブの null は、名前がないことを表す
export type NativeUtterance = Omit<Utterance, "speakerId" | "speakerName"> & {
  speakerId?: string | null;
  speakerName: string | null;
};

export type NativeMemoDetail = Omit<MemoDetail, "utterances"> & { utterances: NativeUtterance[] };

type MemoList = Awaited<ReturnType<MemoEngineClient["listMemos"]>>;
type TodoList = Awaited<ReturnType<MemoEngineClient["listTodos"]>>;

export interface MemoNativeModule {
  listMemos(limit: number): Promise<MemoList>;
  getMemo(id: string): Promise<NativeMemoDetail | null>;
  listTodos(limit: number): Promise<TodoList>;
  completeTodo(id: string): Promise<void>;
  getOrganizeStatus(): Promise<OrganizeStatus>;
  startOrganize(): Promise<OrganizeStartResult>;
  stopOrganize(): Promise<void>;
  addListener(eventName: "engine", listener: (event: EngineEvent) => void): { remove(): void };
}

export function toUtterance({ speakerId, speakerName, ...rest }: NativeUtterance): Utterance {
  return {
    ...rest,
    ...(speakerId != null && { speakerId }),
    ...(speakerName !== null && { speakerName }),
  };
}

function toMemoDetail({ utterances, ...rest }: NativeMemoDetail): MemoDetail {
  return { ...rest, utterances: utterances.map(toUtterance) };
}

export function createMemoClient(native: MemoNativeModule): MemoEngineClient {
  return {
    // 本物の接続に見本や未実装の状態はない。整理ジョブが動くまでは、一覧が空のまま
    getMemoMode: () => Promise.resolve("ready"),
    listMemos: ({ limit }) => native.listMemos(limit),
    getMemo: (id) => native.getMemo(id).then((detail) => detail && toMemoDetail(detail)),
    listTodos: ({ limit }) => native.listTodos(limit),
    completeTodo: (id) => native.completeTodo(id),
    getOrganizeStatus: () => native.getOrganizeStatus(),
    startOrganize: () => native.startOrganize(),
    stopOrganize: () => native.stopOrganize(),
    subscribe: (listener) => {
      const subscription = native.addListener("engine", listener);
      return () => subscription.remove();
    },
  };
}
