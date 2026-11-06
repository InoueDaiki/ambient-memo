import { spacing } from "@ambient-memo/design-tokens";
import type { AskPhase, MemoAnswer, MemoAnswerOutcome } from "@ambient-memo/engine-client";
import {
  useAskMemos,
  useMemoMode,
  useOrganizerModel,
  type AskedQuestion,
} from "@ambient-memo/engine-client/react";
import {
  Button,
  EmptyState,
  ListItem,
  Screen,
  StatusBanner,
  Text,
  TextField,
} from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { useState } from "react";
import { FlatList, View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";
import { OrganizerModelSection } from "../transcript/ModelDownload.tsx";
import { formatMemoPeriod, UNTITLED_MEMO } from "./format.ts";
import { MemoModeBanner } from "./MemoModeBanner.tsx";

// 最初の段階が届くまでは、モデルを読み込んでいるものとして出す
const phaseMessages: Record<AskPhase, string> = {
  preparing: "モデルを読み込んでいます",
  searching: "メモを探しています",
  answering: "答えを作っています",
};

const outcomeBanners: Record<
  Exclude<MemoAnswerOutcome, "answered">,
  { tone: "neutral" | "danger"; message: string }
> = {
  "not-found": {
    tone: "neutral",
    message: "関係するメモが見つかりませんでした。言葉を変えて聞いてみてください。",
  },
  busy: {
    tone: "neutral",
    message:
      "整理か別の質問が動いているので、答えられませんでした。終わってからもう一度試してください。",
  },
  "model-unavailable": {
    tone: "neutral",
    message: "整理のモデルがないので答えられませんでした。上の案内から取得してください。",
  },
  failed: { tone: "danger", message: "答えを作れませんでした。もう一度試してください。" },
};

function RunningSection({ phase, cancel }: { phase: AskPhase | undefined; cancel: () => void }) {
  return (
    <View style={{ gap: spacing.sm }}>
      <Text variant="label">{phaseMessages[phase ?? "preparing"]}</Text>
      <Text variant="caption" color="fgMuted">
        端末の中で答えを作るので、数十秒から数分かかります。
      </Text>
      <Button label="止める" variant="danger" onPress={cancel} />
    </View>
  );
}

function AnswerBody({
  answer,
  onOpenMemo,
}: {
  answer: MemoAnswer;
  onOpenMemo: (memoId: string) => void;
}) {
  if (answer.outcome !== "answered") return <StatusBanner {...outcomeBanners[answer.outcome]} />;
  return (
    <View style={{ gap: spacing.sm }}>
      <Text selectable>{answer.answer ?? ""}</Text>
      {answer.sources.length > 0 && (
        <Text variant="caption" color="fgMuted" accessibilityRole="header">
          {`根拠のメモ(${answer.sources.length} 件)`}
        </Text>
      )}
      {/* ListItem は左右の余白を自分で持つので、外側の余白を打ち消す */}
      <View style={{ marginHorizontal: -spacing.lg }}>
        {answer.sources.map((memo) => (
          <ListItem
            key={memo.id}
            text={memo.title ?? UNTITLED_MEMO}
            label={formatMemoPeriod(memo)}
            onPress={() => onOpenMemo(memo.id)}
          />
        ))}
      </View>
    </View>
  );
}

function QuestionItem({
  item,
  isAsking,
  phase,
  cancel,
  onOpenMemo,
}: {
  item: AskedQuestion;
  isAsking: boolean;
  phase: AskPhase | undefined;
  cancel: () => void;
  onOpenMemo: (memoId: string) => void;
}) {
  return (
    <View style={{ paddingHorizontal: spacing.lg, paddingVertical: spacing.md, gap: spacing.sm }}>
      <Text variant="label" selectable>
        {item.question}
      </Text>
      {isAsking && <RunningSection phase={phase} cancel={cancel} />}
      {item.answer && <AnswerBody answer={item.answer} onOpenMemo={onOpenMemo} />}
      {item.failure === "cancelled" && <StatusBanner tone="neutral" message="質問を止めました。" />}
      {item.failure === "unknown" && (
        <StatusBanner tone="danger" message={outcomeBanners.failed.message} />
      )}
    </View>
  );
}

// メモに質問する画面。質問と答えはこの画面にいる間だけ残し、保存しない
export function AskScreen() {
  const navigation = useNavigation<RootNavigation>();
  const mode = useMemoMode();
  const organizerModel = useOrganizerModel();
  const { questions, isAsking, phase, ask, cancel } = useAskMemos();
  const [question, setQuestion] = useState("");
  const hasNoModel =
    organizerModel.status !== undefined && organizerModel.status.state !== "installed";
  const canAsk = mode !== "unavailable" && !hasNoModel && !isAsking && question.trim() !== "";

  const send = () => {
    ask(question);
    setQuestion("");
  };
  const openMemo = (memoId: string) => navigation.navigate("MemoDetail", { memoId });

  return (
    <Screen bottomInset={false}>
      <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md, paddingBottom: spacing.sm }}>
        <Text variant="title" accessibilityRole="header">
          質問
        </Text>
        <MemoModeBanner />
        <OrganizerModelSection {...organizerModel} />
        <TextField
          label="メモへの質問"
          value={question}
          onChangeText={setQuestion}
          placeholder="例: 読書会はいつ?"
        />
        <Button label="質問する" variant="primary" onPress={send} disabled={!canAsk} />
      </View>

      <View style={{ flex: 1 }}>
        {questions.length === 0 ? (
          <EmptyState
            title="メモに質問できます"
            description="過去のメモを探して、端末の中で答えます。質問と答えは保存しません。"
          />
        ) : (
          <FlatList
            data={questions}
            keyExtractor={(item) => String(item.id)}
            renderItem={({ item, index }) => (
              <QuestionItem
                item={item}
                // 答えを待つのは、いちばん新しい質問だけ
                isAsking={isAsking && index === 0}
                phase={phase}
                cancel={cancel}
                onOpenMemo={openMemo}
              />
            )}
          />
        )}
      </View>
    </Screen>
  );
}
