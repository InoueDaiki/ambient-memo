import { spacing } from "@ambient-memo/design-tokens";
import { useListening, useSelfEnrollment } from "@ambient-memo/engine-client/react";
import { Button, ProgressBar, Screen, StatusBanner, Text } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { useEffect, useRef } from "react";
import { View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";

// 読み上げる文。内容は照合に使わない。登録に足りる長さの発話を、間を置いて話してもらうためのもの。
// 数はエンジンの EnrollmentConfig.requiredSamples と揃える
const LINES = [
  "今日の予定を確認してから、買い物に出かけます。",
  "週末は友人と会って、ゆっくり話をするつもりです。",
  "帰ったら、忘れないうちに明日の準備をしておきます。",
];

function failureMessage(failure: ReturnType<typeof useSelfEnrollment>["failure"]): string {
  switch (failure) {
    case "not-enough-speech":
      return "声を十分に聞き取れませんでした。静かな場所で、もう一度試してください。";
    case "microphone-permission":
      return "声の登録にはマイクの許可が必要です。";
    case "microphone-unavailable":
      return "マイクを開けませんでした。ほかのアプリが使っていないか確かめてください。";
    case "speaker-model-unavailable":
      return "話者のモデルが端末にないため、声を登録できません。";
    case "listening":
      return "聞き取りを止めてから登録してください。";
    default:
      return "声を登録できませんでした。もう一度試してください。";
  }
}

function lineBanner(acceptedSamples: number, isLastRejected: boolean) {
  if (isLastRejected) {
    return { tone: "danger", message: "短かったので、もう一度読んでください。" } as const;
  }
  return {
    tone: "listening",
    message: acceptedSamples === 0 ? "聞いています。" : "聞き取れました。次の文を読んでください。",
  } as const;
}

// うっかり次の文を読まないように、認識できるまで今の文だけを出す
function Reading({
  acceptedSamples,
  isLastRejected,
}: {
  acceptedSamples: number;
  isLastRejected: boolean;
}) {
  const line = LINES[acceptedSamples];
  if (line === undefined) return <StatusBanner tone="listening" message="声を登録しています。" />;
  return (
    <View style={{ gap: spacing.lg }}>
      <View style={{ gap: spacing.sm }}>
        <Text variant="caption" color="fgMuted">
          {`${acceptedSamples + 1} / ${LINES.length} 文目`}
        </Text>
        <ProgressBar label="声の登録の進み具合" value={acceptedSamples / LINES.length} />
      </View>
      <StatusBanner {...lineBanner(acceptedSamples, isLastRejected)} />
      <Text variant="title">{line}</Text>
      <Text variant="caption" color="fgMuted">
        読み終えたら、少し待ってください。認識できると次の文が出ます。
      </Text>
    </View>
  );
}

// 自分の声を、1文ずつ確かめながら登録する画面
export function SelfEnrollmentScreen() {
  const navigation = useNavigation<RootNavigation>();
  const { status } = useListening();
  const { isEnrolling, isCompleted, failure, acceptedSamples, isLastRejected, enroll, cancel } =
    useSelfEnrollment();
  const isListening = status === "listening";

  // 画面を離れたら、マイクを開いたままにしない
  const isEnrollingRef = useRef(isEnrolling);
  isEnrollingRef.current = isEnrolling;
  useEffect(
    () => () => {
      if (isEnrollingRef.current) cancel();
    },
    [cancel],
  );

  return (
    <Screen>
      <View style={{ flex: 1, paddingHorizontal: spacing.lg, gap: spacing.lg }}>
        <View style={{ alignSelf: "flex-start" }}>
          <Button label="戻る" variant="ghost" onPress={() => navigation.goBack()} />
        </View>
        <Text variant="title" accessibilityRole="header">
          自分の声を登録する
        </Text>
        <View style={{ flex: 1, gap: spacing.lg }}>
          {isEnrolling && (
            <Reading acceptedSamples={acceptedSamples} isLastRejected={isLastRejected} />
          )}
          {!isEnrolling && isCompleted && (
            <StatusBanner tone="listening" message="登録しました。" />
          )}
          {!isEnrolling && !isCompleted && (
            <>
              <Text>
                {`画面に出る文を、1文ずつ声に出して読みます。全部で ${LINES.length} 文です。`}
              </Text>
              <Text variant="caption" color="fgMuted">
                ふだん聞き取りをする端末の置き場所で読むと、見分けやすくなります。録音は端末の外へ送らず、保存もしません。
              </Text>
              {failure !== undefined && (
                <StatusBanner tone="danger" message={failureMessage(failure)} />
              )}
              {isListening && (
                <StatusBanner tone="neutral" message="聞き取りを止めると登録できます。" />
              )}
            </>
          )}
        </View>
        <View style={{ paddingBottom: spacing.lg }}>
          {isEnrolling && <Button label="登録をやめる" variant="danger" onPress={cancel} />}
          {!isEnrolling && isCompleted && (
            <Button label="閉じる" onPress={() => navigation.goBack()} />
          )}
          {!isEnrolling && !isCompleted && (
            <Button label="読み上げを始める" disabled={isListening} onPress={enroll} />
          )}
        </View>
      </View>
    </Screen>
  );
}
