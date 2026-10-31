// 画面が使うエンジンの実装を決める場所。本物に差し替えるときは、この関数だけを変える。
// engine-bridge の createEngineClient が TranscriptEngineClient を返すようになったら、それを返す
import { FakeEngineClient, type TranscriptEngineClient } from "@ambient-memo/engine-client";

// 聞き取りを試すための架空の会話。一定の間隔で1文ずつ届く
const DEMO_LINES = [
  { speaker: "self", text: "明日の朝、牛乳と卵を買ってから出かける。" },
  { speaker: "other", text: "図書館の本は今週の金曜日までに返せばよかったはず。" },
  { speaker: "self", text: "そうだった、帰りに寄れるか確認しておこう。" },
  { speaker: "other", text: "週末は天気が崩れるみたいだから、洗濯は土曜の午前中がいいかも。" },
] as const;

const DEMO_INTERVAL_MS = 5000;

export function createAppEngineClient(): TranscriptEngineClient {
  const client = new FakeEngineClient();
  let timer: ReturnType<typeof setInterval> | undefined;
  let count = 0;

  client.subscribe((event) => {
    if (event.type !== "listening-changed") return;
    clearInterval(timer);
    if (event.status !== "listening") return;
    timer = setInterval(() => {
      const line = DEMO_LINES[count % DEMO_LINES.length];
      if (!line) return;
      count += 1;
      client.addUtterance({ id: `demo-${count}`, startedAt: Date.now(), ...line });
    }, DEMO_INTERVAL_MS);
  });

  return client;
}
