import { spacing } from "@ambient-memo/design-tokens";
import {
  EngineStartError,
  TuningError,
  type TuningItem,
  type TuningKey,
  type TuningValues,
} from "@ambient-memo/engine-client";
import { useListening, useSpeakers, useTuning } from "@ambient-memo/engine-client/react";
import { Button, Screen, StatusBanner, Stepper, Text } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { useEffect, useState } from "react";
import { ScrollView, View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";
import { stepTuning, tuningCopy, tuningProblemMessage, tuningSections } from "./tuningFormat.ts";

function errorMessage(error: unknown): string {
  if (error instanceof TuningError) return tuningProblemMessage(error.problem);
  if (error instanceof EngineStartError) {
    return "値は保存しましたが、聞き取りを始め直せませんでした。文字起こしの画面から始めてください。";
  }
  return "保存できませんでした。もう一度試してください。";
}

// 既定値と違う値だけを残す。書かない項目はエンジンが既定値にする
function changedValues(items: TuningItem[], draft: TuningValues): TuningValues {
  const values: TuningValues = {};
  for (const item of items) {
    const value = draft[item.key] ?? item.value;
    if (value !== item.defaultValue) values[item.key] = value;
  }
  return values;
}

// 判定や検知の値を端末の中で変える。値はエンジンが確かめて保存する
export function TuningScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { tuning, isSaving, error, save } = useTuning();
  const { speakers } = useSpeakers();
  const { status } = useListening();
  const [draft, setDraft] = useState<TuningValues>({});
  const [hasSaved, setHasSaved] = useState(false);
  const [isConfirming, setIsConfirming] = useState(false);

  // 保存の後や、ほかの画面で変わったときは、エンジンの値から書き直す
  useEffect(() => setDraft({}), [tuning]);

  if (tuning === undefined) {
    return <Screen>{null}</Screen>;
  }

  const items = new Map(tuning.items.map((item) => [item.key, item]));
  const valueOf = (key: TuningKey) => draft[key] ?? items.get(key)?.value ?? 0;
  const isDirty = tuning.items.some((item) => valueOf(item.key) !== item.value);
  const isDefault = tuning.items.every((item) => valueOf(item.key) === item.defaultValue);
  const provisional = speakers?.filter((speaker) => speaker.kind === "provisional").length ?? 0;
  const limit = valueOf("maxProvisionalSpeakers");
  const mergesSpeakers =
    limit < (items.get("maxProvisionalSpeakers")?.value ?? limit) && limit < provisional;

  const change = (key: TuningKey, value: number) => {
    setHasSaved(false);
    setIsConfirming(false);
    setDraft((current) => ({ ...current, [key]: value }));
  };

  const submit = async () => {
    setIsConfirming(false);
    setHasSaved(await save(changedValues(tuning.items, draft)));
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={{ padding: spacing.lg, gap: spacing.lg }}>
        <Button label="戻る" variant="ghost" onPress={() => navigation.goBack()} />
        <Text variant="title" accessibilityRole="header">
          調整
        </Text>
        <Text variant="caption">
          話者の判定、発話の検知、メモの整理の値を変えます。値はこの端末の中にだけ保存します。
          聞き取り中に保存すると、聞き取りを始め直します。メモの整理の値は次の整理から使い、作成済みのメモは変えません。
        </Text>
        {error !== undefined && <StatusBanner tone="danger" message={errorMessage(error)} />}
        {hasSaved && error === undefined && (
          <StatusBanner
            tone="neutral"
            message={status === "listening" ? "保存して、聞き取りを始め直しました" : "保存しました"}
          />
        )}
        {tuningSections.map((section) => (
          <View key={section.title} style={{ gap: spacing.lg }}>
            <Text variant="label" color="fgMuted" accessibilityRole="header">
              {section.title}
            </Text>
            {section.keys.map((key) => {
              const item = items.get(key);
              if (item === undefined) return null;
              const copy = tuningCopy[key];
              const value = valueOf(key);
              return (
                <Stepper
                  key={key}
                  label={copy.label}
                  description={copy.description}
                  valueText={copy.format(value)}
                  hint={
                    value === item.defaultValue
                      ? "既定値"
                      : `既定値は ${copy.format(item.defaultValue)}`
                  }
                  canDecrement={!isSaving && value > item.min}
                  canIncrement={!isSaving && value < item.max}
                  onDecrement={() => change(key, stepTuning(item, value, -1))}
                  onIncrement={() => change(key, stepTuning(item, value, 1))}
                />
              );
            })}
          </View>
        ))}
        {isConfirming ? (
          <View style={{ gap: spacing.sm }}>
            <Text variant="caption">
              仮の話者が {provisional} 人います。上限を {limit}{" "}
              人にすると、近い話者どうしを統合します。取り消せません。
            </Text>
            <Button
              label="統合して保存する"
              variant="danger"
              disabled={isSaving}
              onPress={submit}
            />
            <Button label="やめる" variant="ghost" onPress={() => setIsConfirming(false)} />
          </View>
        ) : (
          <Button
            label={isSaving ? "保存しています" : "保存する"}
            variant="primary"
            disabled={isSaving || !isDirty}
            onPress={() => (mergesSpeakers ? setIsConfirming(true) : void submit())}
          />
        )}
        <Button
          label="すべて既定値に戻す"
          variant="secondary"
          disabled={isSaving || isDefault}
          onPress={() => {
            setHasSaved(false);
            setIsConfirming(false);
            setDraft(Object.fromEntries(tuning.items.map((item) => [item.key, item.defaultValue])));
          }}
        />
      </ScrollView>
    </Screen>
  );
}
