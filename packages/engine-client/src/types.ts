// 画面から見たエンジンの境界。Android 固有の型や概念を持ち込まない

export interface EngineStatus {
  engineName: string;
  apiVersion: number;
}

export interface EngineClient {
  getStatus(): Promise<EngineStatus>;
}
