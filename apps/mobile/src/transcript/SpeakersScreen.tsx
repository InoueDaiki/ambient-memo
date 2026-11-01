import { spacing } from "@ambient-memo/design-tokens";
import type { SpeakerDiagnostics, SpeakerInfo } from "@ambient-memo/engine-client";
import {
  useListening,
  useSpeakerActions,
  useSpeakerDiagnostics,
  useSpeakers,
} from "@ambient-memo/engine-client/react";
import { Button, Screen, StatusBanner, Text, TextField } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { useState } from "react";
import { ScrollView, View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";
import { SelfEnrollmentCard } from "./SelfEnrollmentCard.tsx";

const BAR_WIDTH = 20;

type Editing = { speakerId: string; mode: "rename" | "merge" } | undefined;

function RenameForm({
  speaker,
  onSubmit,
  onCancel,
}: {
  speaker: SpeakerInfo;
  onSubmit: (name: string) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(speaker.name);
  return (
    <View style={{ gap: spacing.sm }}>
      <TextField label={`${speaker.name} の名前`} value={name} onChangeText={setName} />
      <Button label="名前を保存する" variant="primary" onPress={() => onSubmit(name)} />
      <Button label="やめる" variant="ghost" onPress={onCancel} />
    </View>
  );
}

function MergeForm({
  speaker,
  speakers,
  onSubmit,
  onCancel,
}: {
  speaker: SpeakerInfo;
  speakers: SpeakerInfo[];
  onSubmit: (into: SpeakerInfo) => void;
  onCancel: () => void;
}) {
  const [into, setInto] = useState<SpeakerInfo>();
  if (into !== undefined) {
    return (
      <View style={{ gap: spacing.sm }}>
        <Text variant="caption">
          {speaker.name} の発話を {into.name} に移します。取り消せません。
        </Text>
        <Button label="統合する" variant="danger" onPress={() => onSubmit(into)} />
        <Button label="やめる" variant="ghost" onPress={onCancel} />
      </View>
    );
  }
  return (
    <View style={{ gap: spacing.sm }}>
      <Text variant="caption">統合先を選んでください。</Text>
      {speakers
        .filter((item) => item.id !== speaker.id)
        .map((item) => (
          <Button
            key={item.id}
            label={`${item.name} へ`}
            variant="secondary"
            onPress={() => setInto(item)}
          />
        ))}
      <Button label="やめる" variant="ghost" onPress={onCancel} />
    </View>
  );
}

function Diagnostics({ diagnostics }: { diagnostics: SpeakerDiagnostics }) {
  const total = diagnostics.selfRuns + diagnostics.otherRuns + diagnostics.unknownRuns;
  const unknownRate = total === 0 ? 0 : Math.round((diagnostics.unknownRuns / total) * 100);
  const peak = Math.max(1, ...diagnostics.histogram.map((b) => b.self + b.other + b.unknown));
  return (
    <View style={{ gap: spacing.xs }}>
      <Text variant="label" accessibilityRole="header">
        判定の記録(直近 {diagnostics.days} 日)
      </Text>
      <Text variant="caption" color="fgMuted">
        端末の中だけで集計しています。音声も声の特徴も含まず、外へ送りません。
      </Text>
      <Text variant="caption">
        判定 {total} 回: 自分 {diagnostics.selfRuns} / 他の人 {diagnostics.otherRuns} / 不明{" "}
        {diagnostics.unknownRuns}({unknownRate}%)
      </Text>
      <Text variant="caption">
        時間: 自分 {diagnostics.selfSeconds} 秒 / 他の人 {diagnostics.otherSeconds} 秒 / 不明{" "}
        {diagnostics.unknownSeconds} 秒
      </Text>
      <Text variant="caption">
        仮の話者 {diagnostics.provisionalSpeakers} 人 / 声の更新{" "}
        {diagnostics.hasAdaptedVoice ? "あり" : "なし"} / しきい値{" "}
        {diagnostics.selfThreshold.toFixed(2)}、不明 {diagnostics.unknownFloor.toFixed(2)}
      </Text>
      <Text variant="caption" color="fgMuted">
        自分との類似度(自/他/不明)
      </Text>
      {diagnostics.histogram.map((band) => {
        const count = band.self + band.other + band.unknown;
        const bar = "■".repeat(Math.max(1, Math.round((count / peak) * BAR_WIDTH)));
        return (
          <Text key={band.floor} variant="caption">
            {band.floor.toFixed(2)} {bar} {band.self}/{band.other}/{band.unknown}
          </Text>
        );
      })}
    </View>
  );
}

// 取り消せず、自分の声も消えるので、押してすぐには消さない
function ResetSpeakers({ isListening, onReset }: { isListening: boolean; onReset: () => void }) {
  const [isConfirming, setIsConfirming] = useState(false);
  if (!isConfirming) {
    return (
      <View style={{ gap: spacing.sm }}>
        <Button
          label="話者をリセットする"
          variant="danger"
          disabled={isListening}
          onPress={() => setIsConfirming(true)}
        />
        {isListening && (
          <Text variant="caption" color="fgMuted">
            聞き取りを止めるとリセットできます。
          </Text>
        )}
      </View>
    );
  }
  return (
    <View style={{ gap: spacing.sm }}>
      <Text>
        自分の声を含むすべての話者と判定の記録を消します。これまでの発話の話者は「不明」になり、元に戻せません。文字起こしとメモは残ります。リセットのあとは、声を登録し直してください。
      </Text>
      <Button
        label="すべての話者を消す"
        variant="danger"
        disabled={isListening}
        onPress={() => {
          setIsConfirming(false);
          onReset();
        }}
      />
      <Button label="やめる" variant="ghost" onPress={() => setIsConfirming(false)} />
    </View>
  );
}

// 話者の名前、統合、声の登録のやり直しと、判定の記録を見る画面
export function SpeakersScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { speakers, error: loadError } = useSpeakers();
  const { error, renameSpeaker, mergeSpeakers, resetSpeakers } = useSpeakerActions();
  const diagnostics = useSpeakerDiagnostics();
  const { status } = useListening();
  const [editing, setEditing] = useState<Editing>();

  const close = () => setEditing(undefined);

  return (
    <Screen>
      <ScrollView contentContainerStyle={{ paddingHorizontal: spacing.lg, gap: spacing.lg }}>
        <Button label="戻る" variant="ghost" onPress={() => navigation.goBack()} />
        <Text variant="title" accessibilityRole="header">
          話者
        </Text>
        {loadError !== undefined && (
          <StatusBanner tone="danger" message="話者を読み込めませんでした。" />
        )}
        {error !== undefined && (
          <StatusBanner tone="danger" message="直せませんでした。もう一度試してください。" />
        )}
        {speakers?.map((speaker) => {
          const isEditing = editing?.speakerId === speaker.id;
          return (
            <View key={speaker.id} style={{ gap: spacing.sm }}>
              <Text variant="label">
                {speaker.name}({speaker.utteranceCount} 件)
              </Text>
              {isEditing && editing.mode === "rename" && (
                <RenameForm
                  speaker={speaker}
                  onCancel={close}
                  onSubmit={(name) => {
                    close();
                    void renameSpeaker(speaker.id, name);
                  }}
                />
              )}
              {isEditing && editing.mode === "merge" && (
                <MergeForm
                  speaker={speaker}
                  speakers={speakers}
                  onCancel={close}
                  onSubmit={(into) => {
                    close();
                    void mergeSpeakers(speaker.id, into.id);
                  }}
                />
              )}
              {!isEditing && (
                <View style={{ flexDirection: "row", gap: spacing.sm }}>
                  <View style={{ flex: 1 }}>
                    <Button
                      label={`${speaker.name} の名前を変える`}
                      variant="secondary"
                      onPress={() => setEditing({ speakerId: speaker.id, mode: "rename" })}
                    />
                  </View>
                  {speaker.kind !== "self" && speakers.length > 1 && (
                    <View style={{ flex: 1 }}>
                      <Button
                        label={`${speaker.name} を統合する`}
                        variant="secondary"
                        onPress={() => setEditing({ speakerId: speaker.id, mode: "merge" })}
                      />
                    </View>
                  )}
                </View>
              )}
            </View>
          );
        })}
        <SelfEnrollmentCard isListening={status === "listening"} again />
        {diagnostics !== undefined && <Diagnostics diagnostics={diagnostics} />}
        <ResetSpeakers isListening={status === "listening"} onReset={() => void resetSpeakers()} />
      </ScrollView>
    </Screen>
  );
}
