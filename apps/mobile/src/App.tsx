import { createEngineClient } from "@ambient-memo/engine-bridge";
import type { EngineStatus } from "@ambient-memo/engine-client";
import { Screen } from "@ambient-memo/ui-native";
import { useEffect, useState } from "react";
import { Text } from "react-native";

export function App() {
  const [status, setStatus] = useState<EngineStatus>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    createEngineClient()
      .getStatus()
      .then(setStatus)
      .catch((e: unknown) => setError(String(e)));
  }, []);

  return (
    <Screen>
      <Text>
        {error ?? (status ? `${status.engineName} (API ${status.apiVersion})` : "読み込み中")}
      </Text>
    </Screen>
  );
}
