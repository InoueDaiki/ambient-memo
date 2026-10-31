import { describe, expect, it } from "vitest";

import { FakeEngineClient } from "./fake.ts";
import type { EngineClient } from "./types.ts";

// 契約テスト。本物の実装を作ったら、同じテストをその実装にも流す
describe.each<[string, () => EngineClient]>([["FakeEngineClient", () => new FakeEngineClient()]])(
  "%s",
  (_name, createClient) => {
    it("エンジンの名前と API の版を返す", async () => {
      const status = await createClient().getStatus();

      expect(status.engineName).not.toBe("");
      expect(status.apiVersion).toBeGreaterThanOrEqual(1);
    });
  },
);
