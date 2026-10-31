import type { MemoDetail, Speaker, Todo, Utterance } from "./types.ts";

// 開発中の画面で使う見本。すべて架空の内容で、実在の人物や会話とは関係ない

const at = (day: number, hour: number, minute: number): number =>
  new Date(2026, 10, day, hour, minute).getTime();

const said = (id: string, startedAt: number, text: string, speaker: Speaker): Utterance => ({
  id,
  startedAt,
  text,
  speaker,
});

const todo = (overrides: Partial<Todo> & Pick<Todo, "id" | "memoId" | "task">): Todo => ({
  kind: "todo",
  dueDate: null,
  status: "open",
  createdAt: at(1, 22, 0),
  ...overrides,
});

export const sampleMemos: MemoDetail[] = [
  {
    id: "sample-memo-1",
    title: "週末の庭の手入れの段取り",
    summary:
      "週末に庭のプランターを植え替えることにした。土は土曜の午前に買いに行き、古い鉢は月曜に出す。",
    todoExtraction: "complete",
    startedAt: at(1, 10, 2),
    endedAt: at(1, 10, 9),
    utterances: [
      said("sample-utterance-1", at(1, 10, 2), "プランターの土、もう固くなってきたね", "other"),
      said("sample-utterance-2", at(1, 10, 4), "土曜の午前に買いに行こうか", "self"),
      said("sample-utterance-3", at(1, 10, 8), "古い鉢は月曜に出しておくよ", "self"),
    ],
    todos: [
      todo({
        id: "sample-todo-1",
        memoId: "sample-memo-1",
        task: "園芸店で培養土を買う",
        dueDate: "2026-11-07",
      }),
      todo({
        id: "sample-todo-2",
        memoId: "sample-memo-1",
        task: "古い鉢を出す",
        dueDate: "2026-11-09",
        status: "candidate",
      }),
    ],
  },
  {
    id: "sample-memo-2",
    title: "読書会の日程",
    summary: "次の読書会は来月の第2日曜の予定。課題の本は当日までに読んでおく。",
    todoExtraction: "incomplete",
    startedAt: at(1, 15, 30),
    endedAt: at(1, 15, 38),
    utterances: [
      said("sample-utterance-4", at(1, 15, 30), "次の読書会、来月の第2日曜でどうかな", "other"),
      said("sample-utterance-5", at(1, 15, 33), "いいね、課題の本も読んでおく", "self"),
    ],
    todos: [
      todo({
        id: "sample-todo-3",
        memoId: "sample-memo-2",
        task: "読書会",
        kind: "reminder",
        dueDate: "2026-12-13",
      }),
      todo({
        id: "sample-todo-4",
        memoId: "sample-memo-2",
        task: "課題の本を読む",
        status: "candidate",
      }),
    ],
  },
  {
    id: "sample-memo-3",
    title: null,
    summary: null,
    todoExtraction: "complete",
    startedAt: at(1, 19, 45),
    endedAt: at(1, 19, 47),
    utterances: [
      said("sample-utterance-6", at(1, 19, 45), "えっと、なんの話だったかな", "self"),
      said("sample-utterance-7", at(1, 19, 46), "さっきのあれのことじゃない", "other"),
    ],
    todos: [],
  },
];
