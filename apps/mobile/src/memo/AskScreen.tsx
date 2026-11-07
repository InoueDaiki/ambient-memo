import { spacing } from "@ambient-memo/design-tokens";
import { useMemoMode, useOrganizerModel } from "@ambient-memo/engine-client/react";
import { Button, EmptyState, ListItem, Screen, Text, TextField } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { useState } from "react";
import { FlatList, View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";
import { OrganizerModelSection } from "../transcript/ModelDownload.tsx";
import { useAsk } from "./AskContext.tsx";
import { questionStatus } from "./askFormat.ts";
import { MemoModeBanner } from "./MemoModeBanner.tsx";

// 新しい質問と、これまでの質問の一覧。答えは質問の詳細で見る
export function AskScreen() {
  const navigation = useNavigation<RootNavigation>();
  const mode = useMemoMode();
  const organizerModel = useOrganizerModel();
  const { questions, isAsking, phase, ask } = useAsk();
  const [question, setQuestion] = useState("");
  const hasNoModel =
    organizerModel.status !== undefined && organizerModel.status.state !== "installed";
  const canAsk = mode !== "unavailable" && !hasNoModel && !isAsking && question.trim() !== "";

  const openQuestion = (questionId: number) =>
    navigation.navigate("QuestionDetail", { questionId });
  // 聞いたら、進み具合と答えを見られるよう詳細を開く
  const send = () => {
    const id = ask(question);
    if (id === undefined) return;
    setQuestion("");
    openQuestion(id);
  };

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
            renderItem={({ item }) => (
              <ListItem
                text={item.question}
                label={questionStatus(item, phase)}
                onPress={() => openQuestion(item.id)}
              />
            )}
          />
        )}
      </View>
    </Screen>
  );
}
