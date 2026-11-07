import { spacing } from "@ambient-memo/design-tokens";
import type { AskPhase, MemoAnswer } from "@ambient-memo/engine-client";
import { useOrganizerModel } from "@ambient-memo/engine-client/react";
import { Button, EmptyState, ListItem, Screen, StatusBanner, Text } from "@ambient-memo/ui-native";
import { useNavigation, useRoute } from "@react-navigation/native";
import { ScrollView, View } from "react-native";

import type { RootNavigation, RootRoute } from "../navigation/types.ts";
import { OrganizerModelSection } from "../transcript/ModelDownload.tsx";
import { useAsk } from "./AskContext.tsx";
import { isPending, outcomeBanners, phaseMessages } from "./askFormat.ts";
import { formatMemoPeriod, UNTITLED_MEMO } from "./format.ts";
import { MemoModeBanner } from "./MemoModeBanner.tsx";

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

// 1つの質問の進み具合と答え
export function QuestionDetailScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { params } = useRoute<RootRoute<"QuestionDetail">>();
  const organizerModel = useOrganizerModel();
  const { questions, phase, cancel } = useAsk();
  const item = questions.find((question) => question.id === params.questionId);
  const openMemo = (memoId: string) => navigation.navigate("MemoDetail", { memoId });

  return (
    <Screen>
      <View style={{ paddingHorizontal: spacing.lg, gap: spacing.md, paddingBottom: spacing.md }}>
        <View style={{ alignSelf: "flex-start" }}>
          <Button label="戻る" variant="ghost" onPress={() => navigation.goBack()} />
        </View>
        <MemoModeBanner />
        <OrganizerModelSection {...organizerModel} />
      </View>
      {item === undefined ? (
        <EmptyState title="質問が見つかりません" description="質問と答えは保存していません。" />
      ) : (
        <ScrollView contentContainerStyle={{ paddingHorizontal: spacing.lg, gap: spacing.md }}>
          <Text variant="title" accessibilityRole="header" selectable>
            {item.question}
          </Text>
          {isPending(item) && <RunningSection phase={phase} cancel={cancel} />}
          {item.answer && <AnswerBody answer={item.answer} onOpenMemo={openMemo} />}
          {item.failure === "cancelled" && (
            <StatusBanner tone="neutral" message="質問を止めました。" />
          )}
          {item.failure === "unknown" && (
            <StatusBanner tone="danger" message={outcomeBanners.failed.message} />
          )}
        </ScrollView>
      )}
    </Screen>
  );
}
