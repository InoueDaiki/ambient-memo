import { spacing } from "@ambient-memo/design-tokens";
import { MAX_PROVISIONAL_SPEAKERS, type Utterance } from "@ambient-memo/engine-client";
import { useSpeakerActions, useSpeakers } from "@ambient-memo/engine-client/react";
import { Button, StatusBanner, Text, useColors } from "@ambient-memo/ui-native";
import { Modal, ScrollView, View } from "react-native";

// 一覧の行から話者を直すシート。直した結果は以後の判定に反映される
export function SpeakerSheet({
  utterance,
  onClose,
}: {
  utterance: Utterance;
  onClose: () => void;
}) {
  const palette = useColors();
  const { speakers } = useSpeakers();
  const { isPending, error, setUtteranceSpeaker } = useSpeakerActions();
  const self = speakers?.find((speaker) => speaker.kind === "self");
  const others = speakers?.filter((speaker) => speaker.kind !== "self") ?? [];
  const provisional = others.filter((speaker) => speaker.kind === "provisional").length;

  const choose = (target: string) => {
    void setUtteranceSpeaker(utterance.id, target).then((done) => {
      if (done) onClose();
    });
  };

  return (
    <Modal animationType="slide" onRequestClose={onClose}>
      <View style={{ flex: 1, backgroundColor: palette.canvas, padding: spacing.lg }}>
        <View style={{ flex: 1, gap: spacing.md }}>
          <Text variant="label" accessibilityRole="header">
            この発話の話者を直す
          </Text>
          <Text variant="caption" color="fgMuted" numberOfLines={2}>
            {utterance.text}
          </Text>
          {error !== undefined && (
            <StatusBanner tone="danger" message="直せませんでした。もう一度試してください。" />
          )}
          <ScrollView contentContainerStyle={{ gap: spacing.sm }}>
            {self !== undefined && (
              <Button
                label="自分"
                variant="secondary"
                disabled={isPending || utterance.speaker === "self"}
                onPress={() => choose("self")}
              />
            )}
            {others.map((speaker) => (
              <Button
                key={speaker.id}
                label={speaker.name}
                variant="secondary"
                disabled={isPending || utterance.speakerId === speaker.id}
                onPress={() => choose(speaker.id)}
              />
            ))}
            {provisional < MAX_PROVISIONAL_SPEAKERS && (
              <Button
                label="他の話者(新しく作る)"
                variant="secondary"
                disabled={isPending}
                onPress={() => choose("new")}
              />
            )}
            <Button
              label="不明にする"
              variant="secondary"
              disabled={isPending || utterance.speaker === "unknown"}
              onPress={() => choose("unknown")}
            />
          </ScrollView>
          <Button label="閉じる" onPress={onClose} />
        </View>
      </View>
    </Modal>
  );
}
