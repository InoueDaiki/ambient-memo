import { EngineStartError, FakeEngineClient, type Utterance } from "@ambient-memo/engine-client";
import { describe, expect, it } from "@jest/globals";
import { act, render, screen, userEvent } from "@testing-library/react-native";

import { App } from "./App.tsx";

const utterance = (overrides: Partial<Utterance> & { id: string }): Utterance => ({
  text: "牛乳を買う",
  speaker: "self",
  startedAt: new Date(2026, 9, 31, 14, 32).getTime(),
  ...overrides,
});

async function renderApp(client: FakeEngineClient) {
  await render(<App client={client} memoClient={client} />);
  await screen.findByText(/^(止まっています|聞き取り中)$/);
}

async function press(name: string) {
  await userEvent.press(screen.getByRole("button", { name }));
}

describe("文字起こしの画面", () => {
  it("発話がないときは、空の状態と開始のボタンを出す", async () => {
    await renderApp(new FakeEngineClient());

    await screen.findByText("まだ発話はありません");
    screen.getByText("止まっています");
    screen.getByRole("button", { name: "聞き取りを始める" });
  });

  it("開始すると聞き取り中になり、停止すると止まる", async () => {
    const client = new FakeEngineClient();
    await renderApp(client);

    await press("聞き取りを始める");

    await screen.findByText("聞き取り中");
    screen.getByText("話し始めると、ここに文字起こしが並びます。");
    expect(await client.getListeningStatus()).toBe("listening");

    await press("聞き取りを止める");

    await screen.findByText("止まっています");
    screen.getByRole("button", { name: "聞き取りを始める" });
    expect(await client.getListeningStatus()).toBe("stopped");
  });

  it("保存済みの発話を、話者と時刻を添えて出す", async () => {
    const client = new FakeEngineClient({
      utterances: [
        utterance({ id: "1", text: "牛乳を買う", speaker: "self" }),
        utterance({ id: "2", text: "卵も切れていたよ", speaker: "other" }),
      ],
    });
    await renderApp(client);

    await screen.findByText("牛乳を買う");
    screen.getByText("卵も切れていたよ");
    screen.getByText("自分");
    screen.getByText("それ以外");
    expect(screen.getAllByText("14:32")).toHaveLength(2);
    screen.getByText("10月31日(土)");
    expect(screen.queryByText("まだ発話はありません")).toBeNull();
  });

  it("聞き取り中に発話が届くと一覧が更新される", async () => {
    const client = new FakeEngineClient({ listeningStatus: "listening" });
    await renderApp(client);
    await screen.findByText("まだ発話はありません");

    await act(async () => {
      client.addUtterance(utterance({ id: "1", text: "新しい発話" }));
    });

    await screen.findByText("新しい発話");
    expect(screen.queryByText("まだ発話はありません")).toBeNull();
  });

  it("開始に失敗したら知らせて、やり直せる", async () => {
    const client = new FakeEngineClient();
    client.failNextStart(new Error("マイクを使えない"));
    await renderApp(client);

    await press("聞き取りを始める");

    await screen.findByText("聞き取りを始められませんでした。もう一度試してください。");
    screen.getByText("止まっています");

    await press("聞き取りを始める");

    await screen.findByText("聞き取り中");
    expect(screen.queryByText(/聞き取りを始められませんでした/)).toBeNull();
  });

  it("マイクの許可がなくて始められなかったら、設定へ案内する", async () => {
    const client = new FakeEngineClient({
      permissions: { microphone: false, notifications: true },
    });
    client.failNextStart(new EngineStartError("microphone-permission"));
    await renderApp(client);

    expect(screen.queryByRole("button", { name: "設定を開く" })).toBeNull();

    await press("聞き取りを始める");

    await screen.findByText("聞き取りにはマイクの許可が必要です。設定で許可してください。");
    await press("設定を開く");
    expect(client.settingsOpened).toBe(1);

    await act(async () => {
      client.setPermissions({ microphone: true, notifications: true });
    });
    expect(screen.queryByRole("button", { name: "設定を開く" })).toBeNull();
  });

  it("通知が許可されていないときは、出なくなるものを知らせる", async () => {
    const client = new FakeEngineClient({
      permissions: { microphone: true, notifications: false },
    });
    await renderApp(client);
    await screen.findByText("まだ発話はありません");
    expect(screen.queryByText(/通知が許可されていません/)).toBeNull();

    await press("聞き取りを始める");

    await screen.findByText(/通知が許可されていません/);
    await press("設定を開く");
    expect(client.settingsOpened).toBe(1);
  });

  it("マイクを開けなかったら理由を知らせる", async () => {
    const client = new FakeEngineClient();
    client.failNextStart(new EngineStartError("microphone-unavailable"));
    await renderApp(client);

    await press("聞き取りを始める");

    await screen.findByText(
      "マイクを開けませんでした。ほかのアプリが使っていないか確かめてください。",
    );
  });

  it("文字起こしの実装がなければ理由を知らせる", async () => {
    const client = new FakeEngineClient();
    client.failNextStart(new EngineStartError("transcription-unavailable"));
    await renderApp(client);

    await press("聞き取りを始める");

    await screen.findByText("文字起こしの機能がまだ入っていないため、聞き取りを始められません。");
  });

  it("開発用の実装で動いているときは、そのことを出す", async () => {
    const client = new FakeEngineClient({
      status: { engineName: "fake", apiVersion: 1, transcription: "development" },
    });
    await renderApp(client);

    await screen.findByText("開発用: 文字起こしは本物ではありません");
  });

  it("本物の実装では開発用の表示を出さない", async () => {
    await renderApp(new FakeEngineClient());

    await screen.findByText("まだ発話はありません");
    expect(screen.queryByText(/開発用/)).toBeNull();
  });

  it("マイクの都合で止まったら理由を知らせて、次の開始で消す", async () => {
    const client = new FakeEngineClient({ listeningStatus: "listening" });
    await renderApp(client);
    await screen.findByText("聞き取り中");

    await act(async () => {
      client.interrupt();
    });

    await screen.findByText("マイクを使えなくなったため、聞き取りが止まりました。");

    await press("聞き取りを始める");

    await screen.findByText("聞き取り中");
    expect(screen.queryByText(/聞き取りが止まりました/)).toBeNull();
  });

  it("エンジンの都合で止まったら、画面も止まった状態になる", async () => {
    const client = new FakeEngineClient({ listeningStatus: "listening" });
    await renderApp(client);
    await screen.findByText("聞き取り中");

    await act(async () => {
      client.setListeningStatus("stopped");
    });

    await screen.findByText("止まっています");
    screen.getByRole("button", { name: "聞き取りを始める" });
  });
});
