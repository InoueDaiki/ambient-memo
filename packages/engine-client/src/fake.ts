import type { EngineClient, EngineStatus } from "./types.ts";

// エンジンなしで画面を開発・テストするための実装
export class FakeEngineClient implements EngineClient {
  readonly #status: EngineStatus;

  constructor(status: EngineStatus = { engineName: "fake-engine", apiVersion: 1 }) {
    this.#status = status;
  }

  getStatus(): Promise<EngineStatus> {
    return Promise.resolve(this.#status);
  }
}
