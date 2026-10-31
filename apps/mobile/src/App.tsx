import type { MemoEngineClient, TranscriptEngineClient } from "@ambient-memo/engine-client";
import { EngineClientProvider, MemoClientProvider } from "@ambient-memo/engine-client/react";
import { useState } from "react";
import { SafeAreaProvider } from "react-native-safe-area-context";

import { createAppEngineClient, createAppMemoClient } from "./engine.ts";
import { AppNavigator } from "./navigation/AppNavigator.tsx";

// テストは client と memoClient に Fake を渡す
export function App({
  client,
  memoClient,
}: {
  client?: TranscriptEngineClient;
  memoClient?: MemoEngineClient;
}) {
  const [engineClient] = useState(() => client ?? createAppEngineClient());
  const [appMemoClient] = useState(() => memoClient ?? createAppMemoClient());
  return (
    <SafeAreaProvider>
      <EngineClientProvider client={engineClient}>
        <MemoClientProvider client={appMemoClient}>
          <AppNavigator />
        </MemoClientProvider>
      </EngineClientProvider>
    </SafeAreaProvider>
  );
}
