import {
  FakeEngineClient,
  sampleMemos,
  type MemoDetail,
  type Todo,
} from "@ambient-memo/engine-client";
import { describe, expect, it } from "@jest/globals";
import { act, render, screen, userEvent, waitFor } from "@testing-library/react-native";

import { App } from "../App.tsx";
import { formatDueDate, formatMemoPeriod } from "./format.ts";

const at = (day: number, hour: number, minute = 0) =>
  new Date(2026, 9, day, hour, minute).getTime();

const memo = (overrides: Partial<MemoDetail> & { id: string }): MemoDetail => ({
  title: "買い物の相談",
  summary: "週末にまとめて買い物をする。",
  todoExtraction: "complete",
  startedAt: at(31, 14, 32),
  endedAt: at(31, 14, 40),
  utterances: [{ id: "u1", text: "週末に行こう", speaker: "self", startedAt: at(31, 14, 32) }],
  todos: [],
  ...overrides,
});

const todo = (overrides: Partial<Todo> & { id: string; task: string }): Todo => ({
  memoId: "m1",
  kind: "todo",
  dueDate: null,
  status: "open",
  createdAt: at(31, 22),
  ...overrides,
});

async function openTab(client: FakeEngineClient, name: "メモ" | "ToDo") {
  await render(<App client={client} memoClient={client} />);
  await screen.findByText(/^(止まっています|聞き取り中)$/);
  await userEvent.press(screen.getByRole("tab", { name }));
}

const check = (name: string) => screen.findByRole("checkbox", { name });

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

  it("ToDo の読み取りが不完全なメモには札を付ける", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({ id: "m1", title: "不完全", todoExtraction: "incomplete" }),
        memo({ id: "m2", title: "完全", startedAt: at(30, 9), endedAt: at(30, 10) }),
      ],
    });
    await openTab(client, "メモ");

    await screen.findByText("不完全");
    expect(screen.getAllByText("ToDo が欠けているかも")).toHaveLength(1);
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
    screen.getByRole("header", { name: "根拠の発話" });
    const lines = screen.getAllByText(/^(週末に行こう|卵も買おう)$/);
    expect(lines.map((node) => node.props.children)).toEqual(["週末に行こう", "卵も買おう"]);
    screen.getByText("自分");
    screen.getByText("それ以外");
    screen.getByText("14:35");
  });

  it("要約が NULL なら、発話から確かめるよう案内する", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", title: null, summary: null })],
    });
    await openMemo(client, "要約できなかったメモ");

    await screen.findByText("要約を作れませんでした。下の発話から内容を確かめてください。");
    screen.getByText("週末に行こう");
  });

  it("ToDo の読み取りが不完全なら知らせ、読めた分は出す", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({
          id: "m1",
          todoExtraction: "incomplete",
          todos: [todo({ id: "t1", task: "読めた ToDo" })],
        }),
      ],
    });
    await openMemo(client, "買い物の相談");

    await screen.findByText(
      "ToDo を最後まで読み取れませんでした。欠けているものがあるかもしれません。",
    );
    screen.getByText("読めた ToDo");
  });

  it("読み取りが完全なら不完全の知らせを出さない", async () => {
    await openMemo(new FakeEngineClient({ memos: [memo({ id: "m1" })] }), "買い物の相談");

    await screen.findByText("週末にまとめて買い物をする。");
    expect(screen.queryByText(/読み取れませんでした/)).toBeNull();
  });

  it("メモの中で ToDo を完了にできる", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todos: [todo({ id: "t1", task: "卵を買う" })] })],
    });
    await openMemo(client, "買い物の相談");

    await userEvent.press(await check("卵を買うを完了にする"));

    await check("卵を買うは完了済み");
    expect((await client.getMemo("m1"))?.todos[0]?.status).toBe("done");
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
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todos: [todo({ id: "t1", task: "卵を買う", memoId: "gone" })] })],
    });
    await openTab(client, "ToDo");

    await userEvent.press(await screen.findByText("卵を買う"));

    await screen.findByText("メモが見つかりません");
  });
});

describe("ToDo の一覧", () => {
  it("ToDo がないときは空の状態を出す", async () => {
    await openTab(new FakeEngineClient(), "ToDo");

    await screen.findByText("まだ ToDo はありません");
  });

  it("確定した期限と、種類を出す", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({
          id: "m1",
          todos: [
            todo({ id: "t1", task: "歯医者", kind: "reminder", dueDate: "2026-11-03" }),
            todo({ id: "t2", task: "電球を替える" }),
          ],
        }),
      ],
    });
    await openTab(client, "ToDo");

    await screen.findByText("歯医者");
    screen.getByText("期限 11月3日(火)");
    screen.getByText("予定");
    screen.getByText("電球を替える");
    expect(screen.queryByText(/期限の候補/)).toBeNull();
  });

  it("候補の期限は、確定の期限と見分けられるように出す", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({
          id: "m1",
          todos: [todo({ id: "t1", task: "鉢を出す", dueDate: "2026-11-09", status: "candidate" })],
        }),
      ],
    });
    await openTab(client, "ToDo");

    await screen.findByText("期限の候補 11月9日(月)");
    expect(screen.queryByText(/^期限 /)).toBeNull();
  });

  it("日付を捨てた候補は、期限が未確定だと出す", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({ id: "m1", todos: [todo({ id: "t1", task: "本を読む", status: "candidate" })] }),
      ],
    });
    await openTab(client, "ToDo");

    await screen.findByText("期限は未確定");
  });

  it("完了にすると完了の欄へ移り、もう押せない", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todos: [todo({ id: "t1", task: "卵を買う" })] })],
    });
    await openTab(client, "ToDo");
    await screen.findByRole("header", { name: "これから" });

    await userEvent.press(await check("卵を買うを完了にする"));

    await screen.findByRole("header", { name: "完了" });
    expect(screen.queryByRole("header", { name: "これから" })).toBeNull();
    expect(await check("卵を買うは完了済み")).toBeDisabled();
    expect((await client.listTodos({ limit: 1 }))[0]?.status).toBe("done");
  });

  it("候補の ToDo も完了にでき、完了後は候補の札を出さない", async () => {
    const client = new FakeEngineClient({
      memos: [
        memo({
          id: "m1",
          todos: [todo({ id: "t1", task: "鉢を出す", dueDate: "2026-11-09", status: "candidate" })],
        }),
      ],
    });
    await openTab(client, "ToDo");

    await userEvent.press(await check("鉢を出すを完了にする"));

    await check("鉢を出すは完了済み");
    expect(screen.queryByText(/期限/)).toBeNull();
  });

  it("完了に失敗したら知らせて、やり直せる", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todos: [todo({ id: "t1", task: "卵を買う" })] })],
    });
    client.failNextComplete(new Error("書けない"));
    await openTab(client, "ToDo");

    await userEvent.press(await check("卵を買うを完了にする"));

    await screen.findByText("ToDo を完了にできませんでした。もう一度試してください。");

    await userEvent.press(await check("卵を買うを完了にする"));

    await check("卵を買うは完了済み");
    expect(screen.queryByText(/完了にできませんでした/)).toBeNull();
  });

  it("読み取りが不完全なメモがあれば知らせる", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todoExtraction: "incomplete" })],
    });
    await openTab(client, "ToDo");

    await screen.findByText(
      "ToDo を読み取りきれなかったメモがあります。欠けているものがあるかもしれません。",
    );
  });

  it("行を押すと抽出元のメモを開き、戻ると ToDo へ帰る", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todos: [todo({ id: "t1", task: "卵を買う" })] })],
    });
    await openTab(client, "ToDo");

    await userEvent.press(await screen.findByText("卵を買う"));

    await screen.findByText("週末にまとめて買い物をする。");

    await userEvent.press(screen.getByRole("button", { name: "戻る" }));

    await waitFor(() => expect(screen.queryByText("週末にまとめて買い物をする。")).toBeNull());
    screen.getByRole("tab", { name: "ToDo", selected: true });
  });

  it("別の画面で完了にしても、一覧に反映される", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todos: [todo({ id: "t1", task: "卵を買う" })] })],
    });
    await openTab(client, "ToDo");
    await check("卵を買うを完了にする");

    await act(async () => {
      await client.completeTodo("t1");
    });

    await check("卵を買うは完了済み");
  });
});

describe("ナビゲーション", () => {
  it("最初は文字起こしを開き、タブで行き来できる", async () => {
    const client = new FakeEngineClient({
      memos: [memo({ id: "m1", todos: [todo({ id: "t1", task: "卵を買う" })] })],
    });
    await render(<App client={client} memoClient={client} />);

    await screen.findByText("止まっています");
    screen.getByRole("tab", { name: "文字起こし", selected: true });

    await userEvent.press(screen.getByRole("tab", { name: "ToDo" }));
    await screen.findByText("卵を買う");

    await userEvent.press(screen.getByRole("tab", { name: "メモ" }));
    await screen.findByText("買い物の相談");

    await userEvent.press(screen.getByRole("tab", { name: "文字起こし" }));
    await screen.findByRole("button", { name: "聞き取りを始める" });
  });
});

describe("アプリの Fake", () => {
  it("見本を使う Fake では、開発用だと知らせる", async () => {
    await openTab(new FakeEngineClient({ memoMode: "development", memos: sampleMemos }), "メモ");

    await screen.findByText("開発用: メモと ToDo は本物ではありません");
    await screen.findByText(sampleMemos[0]?.title ?? "");
  });

  it("使えないときは、そのことを知らせる", async () => {
    const client = new FakeEngineClient({ memoMode: "unavailable" });
    await openTab(client, "メモ");

    await screen.findByText("メモと ToDo の機能はまだ入っていません。");
  });

  it("本物では開発用の表示を出さない", async () => {
    await openTab(new FakeEngineClient(), "メモ");

    await screen.findByText("まだメモはありません");
    expect(screen.queryByText(/開発用/)).toBeNull();
  });
});

describe("表示の形", () => {
  it("期限の日付は端末の時差でずれない", () => {
    // 文字列のまま Date に渡すと UTC になり、西の時差では前の日になる
    expect(formatDueDate("2026-11-03")).toBe("11月3日(火)");
    expect(formatDueDate("2026-01-01")).toBe("1月1日(木)");
  });

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

    await screen.findByText(
      "区切りがつくと止まります。モデルの読み込み中は、読み込みの後に止まります。",
    );
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
