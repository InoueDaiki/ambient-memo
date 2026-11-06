import { FakeEngineClient, sampleMemos, type MemoDetail } from "@ambient-memo/engine-client";
import { describe, expect, it } from "@jest/globals";
import { act, render, screen, userEvent, waitFor } from "@testing-library/react-native";

import { App } from "../App.tsx";
import { formatMemoPeriod } from "./format.ts";

const at = (day: number, hour: number, minute = 0) =>
  new Date(2026, 9, day, hour, minute).getTime();

const memo = (overrides: Partial<MemoDetail> & { id: string }): MemoDetail => ({
  title: "買い物の相談",
  summary: "週末にまとめて買い物をする。",
  startedAt: at(31, 14, 32),
  endedAt: at(31, 14, 40),
  utterances: [{ id: "u1", text: "週末に行こう", speaker: "self", startedAt: at(31, 14, 32) }],
  ...overrides,
});

async function openSection(name: string | RegExp) {
  await userEvent.press(await screen.findByRole("button", { name }));
}

async function openTab(client: FakeEngineClient, name: "メモ" | "質問") {
  await render(<App client={client} memoClient={client} />);
  await screen.findByText(/^(止まっています|聞き取り中)$/);
  await userEvent.press(screen.getByRole("tab", { name }));
}

describe("メモの一覧", () => {
  it("メモがないときは空の状態を出す", async () => {
    await openTab(new FakeEngineClient(), "メモ");

    await screen.findByText("まだメモはありません");
  });

  it("新しい順に、タイトルと時間を出す", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({ id: "m1", title: "古い話", startedAt: at(30, 9), endedAt: at(30, 10) }),
        memo({ id: "m2", title: "新しい話" }),
      ],
    });
    await openTab(client, "メモ");

    const titles = await screen.findAllByText(/い話$/);
    expect(titles.map((node) => node.props.children)).toEqual(["新しい話", "古い話"]);
    screen.getByText("10月31日(土) 14:32 – 14:40");
  });

  it("要約できなかったメモも開けることが分かるように出す", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", title: null, summary: null })],
    });
    await openTab(client, "メモ");

    await screen.findByText("要約できなかったメモ");
  });

  it("整理が進むと一覧が更新される", async () => {
    const client = new FakeEngineClient();
    await openTab(client, "メモ");
    await screen.findByText("まだメモはありません");

    await act(async () => {
      client.addMemo(memo({ id: "m1", title: "届いたメモ" }));
    });

    await screen.findByText("届いたメモ");
  });

  it("読み込めなかったら知らせる", async () => {
    const client = new FakeEngineClient();
    await render(<App client={client} memoClient={client} />);
    await screen.findByText("止まっています");
    client.failNextMemoRead(new Error("読めない"));

    await userEvent.press(screen.getByRole("tab", { name: "メモ" }));

    await screen.findByText("メモを読み込めませんでした。");
    expect(screen.queryByText("まだメモはありません")).toBeNull();
  });
});

describe("メモの詳細", () => {
  async function openMemo(client: FakeEngineClient, title: string) {
    await openTab(client, "メモ");
    await userEvent.press(await screen.findByText(title));
  }

  it("根拠の発話には、話者の名前があれば名前を出す", async () => {
    const utterances = [
      {
        id: "u1",
        text: "週末に行こう",
        speaker: "other" as const,
        speakerName: "話者1",
        startedAt: 1,
      },
    ];
    await openMemo(
      new FakeEngineClient({ memos: [memo({ id: "m1", utterances })] }),
      "買い物の相談",
    );

    await openSection(/^根拠の発話/);
    await screen.findByText("話者1");
    expect(screen.queryByText("それ以外")).toBeNull();
  });

  it("要約と根拠の発話を古い順に、話者と時刻を添えて出す", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({
          id: "m1",
          utterances: [
            { id: "u1", text: "週末に行こう", speaker: "self", startedAt: at(31, 14, 32) },
            { id: "u2", text: "卵も買おう", speaker: "other", startedAt: at(31, 14, 35) },
          ],
        }),
      ],
    });
    await openMemo(client, "買い物の相談");

    await screen.findByText("週末にまとめて買い物をする。");
    await openSection("根拠の発話(2 件)");
    const lines = screen.getAllByText(/^(週末に行こう|卵も買おう)$/);
    expect(lines.map((node) => node.props.children)).toEqual(["週末に行こう", "卵も買おう"]);
    screen.getByText("自分");
    screen.getByText("それ以外");
    screen.getByText("14:35");
  });

  it("根拠の発話は閉じていて、押すと開け閉めできる", async () => {
    await openMemo(new FakeEngineClient({ memos: [memo({ id: "m1" })] }), "買い物の相談");

    await screen.findByText("週末にまとめて買い物をする。");
    expect(screen.queryByText("週末に行こう")).toBeNull();

    await openSection(/^根拠の発話/);
    screen.getByText("週末に行こう");
    await openSection(/^根拠の発話/);
    expect(screen.queryByText("週末に行こう")).toBeNull();
  });

  it("要約が NULL なら、発話から確かめるよう案内する", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", title: null, summary: null })],
    });
    await openMemo(client, "要約できなかったメモ");

    await screen.findByText("要約を作れませんでした。下の発話から内容を確かめてください。");
    screen.getByText("週末に行こう");
  });

  it("戻るとメモの一覧へ帰る", async () => {
    await openMemo(new FakeEngineClient({ memos: [memo({ id: "m1" })] }), "買い物の相談");
    await screen.findByText("週末にまとめて買い物をする。");

    await userEvent.press(screen.getByRole("button", { name: "戻る" }));

    await waitFor(() => expect(screen.queryByText("週末にまとめて買い物をする。")).toBeNull());
    screen.getByRole("tab", { name: "メモ", selected: true });
  });

  it("読み込めなかったら知らせる", async () => {
    const client = new FakeEngineClient({ memos: [memo({ id: "m1" })] });
    await openTab(client, "メモ");
    const row = await screen.findByText("買い物の相談");
    client.failNextMemoRead(new Error("読めない"));

    await userEvent.press(row);

    await screen.findByText("メモを読み込めませんでした。");
  });

  it("メモが消えていたら、見つからないと知らせる", async () => {
    const client = new FakeEngineClient({ memos: [memo({ id: "m1", title: "読書会の日程" })] });
    await openTab(client, "質問");
    await userEvent.type(screen.getByLabelText("メモへの質問"), "読書会");
    await userEvent.press(screen.getByRole("button", { name: "質問する" }));
    const source = await screen.findByRole("button", { name: /読書会の日程/ });
    client.removeMemo("m1");

    await userEvent.press(source);

    await screen.findByText("メモが見つかりません");
  });
});

describe("ナビゲーション", () => {
  it("最初は文字起こしを開き、タブで行き来できる", async () => {
    const client = new FakeEngineClient({ memos: [memo({ id: "m1" })] });
    await render(<App client={client} memoClient={client} />);

    await screen.findByText("止まっています");
    screen.getByRole("tab", { name: "文字起こし", selected: true });

    await userEvent.press(screen.getByRole("tab", { name: "質問" }));
    await screen.findByText("メモに質問できます");

    await userEvent.press(screen.getByRole("tab", { name: "メモ" }));
    await screen.findByText("買い物の相談");

    await userEvent.press(screen.getByRole("tab", { name: "文字起こし" }));
    await screen.findByRole("button", { name: "聞き取りを始める" });
  });
});

describe("アプリの Fake", () => {
  it("見本を使う Fake では、開発用だと知らせる", async () => {
    await openTab(new FakeEngineClient({ memoMode: "development", memos: sampleMemos }), "メモ");

    await screen.findByText("開発用: メモは本物ではありません");
    await screen.findByText(sampleMemos[0]?.title ?? "");
  });

  it("使えないときは、そのことを知らせる", async () => {
    const client = new FakeEngineClient({ memoMode: "unavailable" });
    await openTab(client, "メモ");

    await screen.findByText("メモの機能はまだ入っていません。");
  });

  it("本物では開発用の表示を出さない", async () => {
    await openTab(new FakeEngineClient(), "メモ");

    await screen.findByText("まだメモはありません");
    expect(screen.queryByText(/開発用/)).toBeNull();
  });
});

describe("表示の形", () => {
  it("日付をまたぐメモは、終わりの日付も出す", () => {
    expect(
      formatMemoPeriod({
        startedAt: at(31, 23, 50),
        endedAt: new Date(2026, 10, 1, 0, 10).getTime(),
      }),
    ).toBe("10月31日(土) 23:50 – 11月1日(日) 0:10");
  });
});

describe("今すぐ整理する", () => {
  const ready = (pendingChunks: number) =>
    new FakeEngineClient({ organizeBacklog: { pendingChunks } });

  it("未処理の量を出し、押すと始まって、進み具合が出る", async () => {
    const client = ready(3);
    await openTab(client, "メモ");
    await screen.findByText(/未処理のまとまり: 3/);

    await userEvent.press(screen.getByRole("button", { name: "今すぐ整理する" }));
    await screen.findByText("整理の準備をしています");
    expect(client.organizeStarts).toBe(1);

    await act(async () => client.progressOrganize(1, 3));
    await screen.findByText("整理しています(1 / 3 まとまり)");
    await screen.findByRole("progressbar", { name: "整理の進み具合" });
  });

  it("開くだけでは始まらない", async () => {
    const client = ready(3);
    await openTab(client, "メモ");

    await screen.findByRole("button", { name: "今すぐ整理する" });

    expect(client.organizeStarts).toBe(0);
  });

  it("止めると、今のまとまりが終わるまで待つことを出し、止まると結果を出す", async () => {
    const client = ready(2);
    await openTab(client, "メモ");
    await userEvent.press(await screen.findByRole("button", { name: "今すぐ整理する" }));

    await userEvent.press(await screen.findByRole("button", { name: "止める" }));

    await screen.findByText("区切りがつくと止まります。");
    await act(async () => client.finishOrganize("stopped", 1));
    await screen.findByText("整理を止めました。残りは次の整理で続きから処理します。");
  });

  it("発熱で止まったら理由を出す", async () => {
    const client = ready(2);
    await openTab(client, "メモ");
    await userEvent.press(await screen.findByRole("button", { name: "今すぐ整理する" }));
    await screen.findByText("整理の準備をしています");

    await act(async () => client.finishOrganize("too-hot", 2));

    await screen.findByText(
      "端末が熱くなったので整理を止めました。冷めてからもう一度試してください。",
    );
  });

  it("整理のプロセスが落ちたら、聞き取りには影響がないと伝える", async () => {
    const client = ready(1);
    await openTab(client, "メモ");
    await userEvent.press(await screen.findByRole("button", { name: "今すぐ整理する" }));
    await screen.findByText("整理の準備をしています");

    await act(async () => client.finishOrganize("failed", 1));

    await screen.findByText(/聞き取りには影響ありません/);
  });

  it("Gemma がないときは取得の案内を出し、整理のボタンは出さない", async () => {
    const client = new FakeEngineClient({
      organizeBacklog: { pendingChunks: 1 },
      organizerModel: { state: "idle", downloadedBytes: 0, totalBytes: 3_660_000_000 },
    });
    await openTab(client, "メモ");

    await screen.findByRole("button", { name: "整理のモデルを取得する" });

    expect(screen.queryByRole("button", { name: "今すぐ整理する" })).toBeNull();
  });

  it("未処理の発話がないときはそう出し、押せない", async () => {
    await openTab(ready(0), "メモ");

    await screen.findByText("未処理の発話はありません。");
    expect(
      screen.getByRole("button", { name: "今すぐ整理する" }).props.accessibilityState.disabled,
    ).toBe(true);
  });

  it("話の途中の発話しかないときは待っていることを出す", async () => {
    const client = new FakeEngineClient({
      organizeBacklog: { pendingChunks: 0, isWaitingForSilence: true },
    });
    await openTab(client, "メモ");

    await screen.findByText("話が途切れて 5 分たつと、直近の発話を整理できます。");
  });

  it("動いている間に開いても、進み具合と止める操作を出す", async () => {
    const client = ready(2);
    await client.startOrganize();
    client.progressOrganize(1, 2);
    await openTab(client, "メモ");

    await screen.findByText("整理しています(1 / 2 まとまり)");
    await screen.findByRole("button", { name: "止める" });
  });
});
