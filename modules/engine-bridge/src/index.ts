import type { EngineClient, EngineStatus } from "@ambient-memo/engine-client";
import { requireNativeModule } from "expo";

interface EngineBridgeNativeModule {
  getStatus(): Promise<EngineStatus>;
}

export function createEngineClient(): EngineClient {
  const native = requireNativeModule<EngineBridgeNativeModule>("EngineBridge");
  return { getStatus: () => native.getStatus() };
}
