import { FAKE_ANSWER, FakeEngineClient, type MemoDetail } from "@ambient-memo/engine-client";
import { describe, expect, it } from "@jest/globals";
import { act, render, screen, userEvent, waitFor } from "@testing-library/react-native";

import { App } from "../App.tsx";

const at = (day: number, hour: number, minute = 0) =>
  new Date(2026, 9, day, hour, minute).getTime();

const memo = (overrides: Partial<MemoDetail> & { id: string }): MemoDetail => ({
  title: "読書会の日程",
  summary: "次の読書会は来月の第2日曜。",
  startedAt: at(31, 14, 32),
  endedAt: at(31, 14, 40),
  utterances: [],
  ...overrides,
});

const memos = [
  memo({ id: "m1", title: "読書会の日程", startedAt: at(30, 9), endedAt: at(30, 10) }),
  memo({ id: "m2", title: "読書会の本", summary: "課題の本を読んでおく。" }),
  memo({ id: "m3", title: "庭の手入れ", summary: "土曜に土を買う。" }),
];

async function openAsk(client: FakeEngineClient) {
  await render(<App client={client} memoClient={client} />);
  await screen.findByText(/^(止まっています|聞き取り中)$/);
  await userEvent.press(screen.getByRole("tab", { name: "質問" }));
  await screen.findByRole("header", { name: "質問" });
}

async function askQuestion(question: string) {
  await userEvent.type(screen.getByLabelText("メモへの質問"), question);
  await userEvent.press(screen.getByRole("button", { name: "質問する" }));
}

const sendButton = () => screen.getByRole("button", { name: "質問する" });

describe("メモへの質問", () => {
  it("質問する前は、できることを案内し、空の質問は送れない", async () => {
    await openAsk(new FakeEngineClient({ memos }));

    screen.getByText("メモに質問できます");
    expect(sendButton()).toBeDisabled();

    await userEvent.type(screen.getByLabelText("メモへの質問"), "   ");
    expect(sendButton()).toBeDisabled();
  });

  it("答えと根拠のメモを出し、入力欄を空にする", async () => {
    await openAsk(new FakeEngineClient({ memos }));

    await askQuestion("読書会");

    await screen.findByText(FAKE_ANSWER);
    screen.getByRole("header", { name: "根拠のメモ(2 件)" });
    const sources = screen.getAllByText(/^読書会の(日程|本)$/);
    expect(sources.map((node) => node.props.children)).toEqual(["読書会の本", "読書会の日程"]);
    expect(screen.queryByText("庭の手入れ")).toBeNull();
    expect(screen.getByLabelText("メモへの質問").props.value).toBe("");
  });

  it("根拠のメモを押すと詳細を開き、戻ると質問へ帰る。答えは残る", async () => {
    await openAsk(new FakeEngineClient({ memos }));
    await askQuestion("課題");

    await userEvent.press(await screen.findByRole("button", { name: /読書会の本/ }));

    await screen.findByText("課題の本を読んでおく。");
    await userEvent.press(screen.getByRole("button", { name: "戻る" }));
    await waitFor(() => expect(screen.queryByText("課題の本を読んでおく。")).toBeNull());
    screen.getByRole("tab", { name: "質問", selected: true });
    screen.getByText(FAKE_ANSWER);
  });

  it("質問と答えは新しい順に並べる", async () => {
    await openAsk(new FakeEngineClient({ memos }));

    await askQuestion("読書会");
    await screen.findByText(FAKE_ANSWER);
    await askQuestion("引っ越し");
    await screen.findByText(/関係するメモが見つかりませんでした/);

    const asked = screen.getAllByText(/^(読書会|引っ越し)$/);
    expect(asked.map((node) => node.props.children)).toEqual(["引っ越し", "読書会"]);
  });

  it("関係するメモがなければ、言葉を変えるよう案内する", async () => {
    await openAsk(new FakeEngineClient({ memos }));

    await askQuestion("引っ越し");

    await screen.findByText("関係するメモが見つかりませんでした。言葉を変えて聞いてみてください。");
    expect(screen.queryByText(/根拠のメモ/)).toBeNull();
  });

  it("答えを作る間は段階ごとに進み具合を出し、送れない", async () => {
    const client = new FakeEngineClient({ memos });
    client.holdNextAsk();
    await openAsk(client);

    await askQuestion("読書会");

    await screen.findByText("モデルを読み込んでいます");
    expect(sendButton()).toBeDisabled();
    await act(async () => client.reportAskProgress("searching"));
    await screen.findByText("メモを探しています");
    await act(async () => client.reportAskProgress("answering"));
    await screen.findByText("答えを作っています");

    await act(async () => client.finishAsk());

    await screen.findByText(FAKE_ANSWER);
    expect(screen.queryByRole("button", { name: "止める" })).toBeNull();
  });

  it("止めると、止めたことを出して、また質問できる", async () => {
    const client = new FakeEngineClient({ memos });
    client.holdNextAsk();
    await openAsk(client);
    await askQuestion("読書会");

    await userEvent.press(await screen.findByRole("button", { name: "止める" }));

    await screen.findByText("質問を止めました。");
    expect(screen.queryByText("モデルを読み込んでいます")).toBeNull();
    await askQuestion("読書会");
    await screen.findByText(FAKE_ANSWER);
  });

  it("整理が動いている間は、終わってから試すよう案内する", async () => {
    const client = new FakeEngineClient({ memos, organizeBacklog: { pendingChunks: 1 } });
    await client.startOrganize();
    await openAsk(client);

    await askQuestion("読書会");

    await screen.findByText(
      "整理か別の質問が動いているので、答えられませんでした。終わってからもう一度試してください。",
    );
  });

  it("実行基盤が動かなかったら知らせる", async () => {
    const client = new FakeEngineClient({ memos });
    client.failNextAsk();
    await openAsk(client);

    await askQuestion("読書会");

    await screen.findByText("答えを作れませんでした。もう一度試してください。");
  });

  it("整理のモデルがなければ取得へ案内し、質問を送れない", async () => {
    const client = new FakeEngineClient({
      memos,
      organizerModel: { state: "idle", downloadedBytes: 0, totalBytes: 3_660_000_000 },
    });
    await openAsk(client);

    await userEvent.type(screen.getByLabelText("メモへの質問"), "読書会");

    expect(sendButton()).toBeDisabled();
    screen.getByText(/メモに質問したりするには、別のモデルが必要です/);
    await userEvent.press(screen.getByRole("button", { name: "整理のモデルを取得する" }));
    expect(client.organizerModelStarts).toBe(1);
  });

  it("答えを待つ間にモデルが消えたら、取得へ案内する", async () => {
    const client = new FakeEngineClient({ memos });
    client.holdNextAsk();
    await openAsk(client);
    await askQuestion("読書会");
    await screen.findByText("モデルを読み込んでいます");

    await act(async () => {
      client.setOrganizerModel({ state: "idle", downloadedBytes: 0, totalBytes: 1 });
      client.finishAsk();
    });

    await screen.findByText(
      "整理のモデルがないので答えられませんでした。上の案内から取得してください。",
    );
    screen.getByRole("button", { name: "整理のモデルを取得する" });
  });
});
