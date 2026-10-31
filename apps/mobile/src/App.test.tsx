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

  it("仮の話者は名前で出し、話者が分からない発話は不明と出す", async () => {
    const client = new FakeEngineClient({
      utterances: [
        utterance({ id: "1", text: "予定を確かめます", speaker: "other", speakerName: "話者1" }),
        utterance({ id: "2", text: "はい", speaker: "unknown" }),
      ],
    });
    await renderApp(client);

    await screen.findByText("話者1");
    screen.getByText("不明");
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

    await screen.findByText("文字起こしのモデルが端末にないため、聞き取りを始められません。");
  });

  it("モデルが端末にないときは、そのことを出す", async () => {
    const client = new FakeEngineClient({
      status: { engineName: "fake", apiVersion: 1, transcription: "unavailable" },
    });
    await renderApp(client);

    await screen.findByText("文字起こしのモデルが端末にありません。");
  });

  describe("モデルの取得", () => {
    it("話者のモデルだけがなければ、聞き取りを妨げずに取得へ案内し、揃うと登録できる", async () => {
      const client = new FakeEngineClient({
        selfEnrollment: "unavailable",
        modelDownload: { state: "idle", downloadedBytes: 0, totalBytes: 28_281_164 },
      });
      await renderApp(client);

      await screen.findByText(/話者のモデルが端末にありません/);
      await screen.findByText(/声の登録にはモデルの取得が必要です。約 29 MB を通信します/);
      expect(screen.getByRole("button", { name: "聞き取りを始める" })).toBeOnTheScreen();
      expect(screen.queryByRole("button", { name: "声を登録する" })).toBeNull();

      await userEvent.press(screen.getByRole("button", { name: "モデルを取得する" }));
      expect(client.modelDownloadStarts).toBe(1);

      await act(async () => {
        client.setModelDownload({ state: "installed", downloadedBytes: 0, totalBytes: 0 });
      });

      await screen.findByRole("button", { name: "声を登録する" });
      expect(screen.queryByText(/話者のモデルが端末にありません/)).toBeNull();
    });

    const status = { engineName: "fake", apiVersion: 1, transcription: "unavailable" } as const;

    it("開いただけでは取得を始めず、通信量を伝える", async () => {
      const client = new FakeEngineClient({ status });
      await renderApp(client);

      await screen.findByText(/約 743 MB を通信します/);
      screen.getByRole("button", { name: "モデルを取得する" });
      expect(screen.queryByRole("button", { name: "聞き取りを始める" })).toBeNull();
      expect(client.modelDownloadStarts).toBe(0);
    });

    it("操作で取得を始めて、進み具合を出す", async () => {
      const client = new FakeEngineClient({ status });
      await renderApp(client);

      await press("モデルを取得する");

      expect(client.modelDownloadStarts).toBe(1);
      await act(async () => {
        client.setModelDownload({
          state: "running",
          phase: "downloading",
          completedBytes: 300_000_000,
          totalBytes: 600_000_000,
        });
      });
      await screen.findByText("取得中 300 MB / 600 MB");
      expect(screen.getByRole("progressbar").props.accessibilityValue.now).toBe(50);

      await act(async () => {
        client.setModelDownload({
          state: "running",
          phase: "extracting",
          completedBytes: 1,
          totalBytes: 2,
        });
      });
      await screen.findByText("モデルを取り出しています");
    });

    it("取得が終わると聞き取りを始められる", async () => {
      const client = new FakeEngineClient({ status });
      await renderApp(client);
      await press("モデルを取得する");

      await act(async () => {
        client.setModelDownload({ state: "installed", downloadedBytes: 0, totalBytes: 0 });
      });

      await screen.findByRole("button", { name: "聞き取りを始める" });
      expect(screen.queryByText(/モデルが端末にありません/)).toBeNull();
    });

    it("途中で止めたら、続きから取得できる", async () => {
      const client = new FakeEngineClient({ status });
      await renderApp(client);
      await press("モデルを取得する");
      await act(async () => {
        client.setModelDownload({
          state: "running",
          phase: "downloading",
          completedBytes: 300_000_000,
          totalBytes: 600_000_000,
        });
      });

      await press("取得を止める");

      await screen.findByText("300 MB / 600 MB まで取得済みです。続きから取得できます。");
      await press("続きから取得する");
      expect(client.modelDownloadStarts).toBe(2);
    });

    it.each([
      ["network", 0, "通信が切れたため、取得が止まりました。"],
      ["corrupted", 0, "取得したファイルが正しくなかったため、消しました。"],
      ["insufficient-storage", 1_500_000_000, "空き容量が足りません。あと 1.5 GB 空けてください。"],
      ["unknown", 0, "モデルを取得できませんでした。"],
    ] as const)("失敗(%s)を知らせて、やり直せる", async (failure, shortageBytes, message) => {
      const client = new FakeEngineClient({
        status,
        modelDownload: {
          state: "failed",
          failure,
          shortageBytes,
          downloadedBytes: 0,
          totalBytes: 600_000_000,
        },
      });
      await renderApp(client);

      await screen.findByText(message);
      await press("モデルを取得する");

      expect(client.modelDownloadStarts).toBe(1);
    });

    it("聞き取りを始めるときにモデルが壊れていたら、取得へ案内する", async () => {
      const client = new FakeEngineClient();
      client.failNextStart(new EngineStartError("transcription-unavailable"));
      await renderApp(client);
      await press("聞き取りを始める");

      await act(async () => {
        client.setModelDownload({ state: "idle", downloadedBytes: 0, totalBytes: 600_000_000 });
      });

      await screen.findByRole("button", { name: "モデルを取得する" });
    });
  });

  describe("整理のモデルの取得", () => {
    const idle = { state: "idle", downloadedBytes: 0, totalBytes: 3_659_530_240 } as const;

    it("開いただけでは取得を始めず、文字起こしのモデルとは別に選べる", async () => {
      const client = new FakeEngineClient({ organizerModel: idle });
      await renderApp(client);

      await screen.findByText(/発話をメモと ToDo に整理するには/);
      screen.getByRole("button", { name: "聞き取りを始める" });
      expect(client.organizerModelStarts).toBe(0);

      await press("整理のモデルを取得する");

      expect(client.organizerModelStarts).toBe(1);
      expect(client.modelDownloadStarts).toBe(0);
      await screen.findByRole("button", { name: "取得を止める" });
      screen.getByRole("button", { name: "聞き取りを始める" });
    });

    it("文字起こしのモデルがない間は案内しない", async () => {
      const client = new FakeEngineClient({
        status: { engineName: "fake", apiVersion: 1, transcription: "unavailable" },
        organizerModel: idle,
      });
      await renderApp(client);

      await screen.findByRole("button", { name: "モデルを取得する" });
      expect(screen.queryByRole("button", { name: "整理のモデルを取得する" })).toBeNull();
    });

    it("取得済みなら何も出さない", async () => {
      await renderApp(new FakeEngineClient());

      await screen.findByText("まだ発話はありません");
      expect(screen.queryByText(/整理/)).toBeNull();
    });

    it("途中で止まったら続きから取得できる", async () => {
      const client = new FakeEngineClient({
        organizerModel: {
          state: "failed",
          failure: "network",
          shortageBytes: 0,
          downloadedBytes: 1_000_000_000,
          totalBytes: idle.totalBytes,
        },
      });
      await renderApp(client);

      await screen.findByText("通信が切れたため、取得が止まりました。");
      await press("整理のモデルを続きから取得する");

      expect(client.organizerModelStarts).toBe(1);
    });
  });

  it("モデルがあるときは案内を出さない", async () => {
    await renderApp(new FakeEngineClient());

    await screen.findByText("まだ発話はありません");
    expect(screen.queryByText(/モデルが端末に/)).toBeNull();
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

describe("自分の声の登録", () => {
  const notEnrolled = () => new FakeEngineClient({ selfEnrollment: "not-enrolled" });

  it("登録済みなら案内を出さない", async () => {
    await renderApp(new FakeEngineClient());

    await screen.findByText("まだ発話はありません");
    expect(screen.queryByText("自分の声を登録する")).toBeNull();
  });

  it("モデルがなければ案内を出さない", async () => {
    await renderApp(new FakeEngineClient({ selfEnrollment: "unavailable" }));

    await screen.findByText("まだ発話はありません");
    expect(screen.queryByText("自分の声を登録する")).toBeNull();
  });

  it("読み上げる文を出して聞き取り、終わると案内が消える", async () => {
    const client = notEnrolled();
    await renderApp(client);
    await screen.findByText("自分の声を登録する");

    await press("声を登録する");

    await screen.findByText(/今日の予定を確認してから/);

    await act(async () => {
      client.finishEnrollment();
    });

    expect(screen.queryByText("自分の声を登録する")).toBeNull();
    expect(await client.getSelfEnrollmentStatus()).toBe("enrolled");
  });

  it("やめると元に戻り、エラーを出さない", async () => {
    const client = notEnrolled();
    await renderApp(client);
    await screen.findByText("自分の声を登録する");

    await press("声を登録する");
    await press("登録をやめる");

    await screen.findByRole("button", { name: "声を登録する" });
    expect(screen.queryByText(/登録できませんでした/)).toBeNull();
    expect(await client.getSelfEnrollmentStatus()).toBe("not-enrolled");
  });

  it("声が足りなければ理由を知らせて、やり直せる", async () => {
    const client = notEnrolled();
    client.failNextEnrollment("not-enough-speech");
    await renderApp(client);
    await screen.findByText("自分の声を登録する");

    await press("声を登録する");

    await screen.findByText(
      "声を十分に聞き取れませんでした。静かな場所で、もう一度試してください。",
    );
    await press("声を登録する");
    expect(screen.queryByText(/声を十分に聞き取れませんでした/)).toBeNull();
  });

  it("聞き取り中は登録を始められない", async () => {
    await renderApp(
      new FakeEngineClient({ selfEnrollment: "not-enrolled", listeningStatus: "listening" }),
    );
    await screen.findByText("自分の声を登録する");

    expect(
      screen.getByRole("button", { name: "声を登録する" }).props.accessibilityState?.disabled,
    ).toBe(true);
  });
});
