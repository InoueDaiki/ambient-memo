import { spacing } from "@ambient-memo/design-tokens";
import { useSelfEnrollment } from "@ambient-memo/engine-client/react";
import { Button, StatusBanner, Text } from "@ambient-memo/ui-native";
import { View } from "react-native";

// 読み上げる文。内容は照合に使わない。登録に足りる長さの発話を、間を置いて話してもらうためのもの
const PROMPT =
  "今日の予定を確認してから、買い物に出かけます。\n週末は友人と会って、ゆっくり話をするつもりです。\n帰ったら、忘れないうちに明日の準備をしておきます。";

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

// 初回に自分の声を登録する案内。登録すると消える。声は端末の中だけで扱い、音声は残さない
export function SelfEnrollmentCard({ isListening }: { isListening: boolean }) {
  const { status, isEnrolling, failure, enroll, cancel } = useSelfEnrollment();
  if (status !== "not-enrolled") return null;

  return (
    <View style={{ gap: spacing.sm }}>
      <Text variant="label" accessibilityRole="header">
        自分の声を登録する
      </Text>
      <Text variant="caption" color="fgMuted">
        「自分」と他の人を見分けるために使います。録音は端末の外へ送らず、保存もしません。
      </Text>
      {isEnrolling && (
        <>
          <StatusBanner
            tone="listening"
            message="聞いています。次の文を、1文ずつ間を置いて読んでください。"
          />
          <Text>{PROMPT}</Text>
        </>
      )}
      {failure !== undefined && <StatusBanner tone="danger" message={failureMessage(failure)} />}
      <Button
        label={isEnrolling ? "登録をやめる" : "声を登録する"}
        variant="secondary"
        disabled={isListening}
        onPress={isEnrolling ? cancel : enroll}
      />
    </View>
  );
}
