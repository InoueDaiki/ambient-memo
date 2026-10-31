import type { TranscriptEngineClient } from "@ambient-memo/engine-client";
import { EngineClientProvider } from "@ambient-memo/engine-client/react";
import { useState } from "react";
import { SafeAreaProvider } from "react-native-safe-area-context";

import { createAppEngineClient } from "./engine.ts";
import { TranscriptScreen } from "./transcript/TranscriptScreen.tsx";

// テストは client に Fake を渡す
export function App({ client }: { client?: TranscriptEngineClient }) {
  const [engineClient] = useState(() => client ?? createAppEngineClient());
  return (
    <SafeAreaProvider>
      <EngineClientProvider client={engineClient}>
        <TranscriptScreen />
      </EngineClientProvider>
    </SafeAreaProvider>
  );
}
