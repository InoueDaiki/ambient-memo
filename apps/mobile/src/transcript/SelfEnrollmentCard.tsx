import { spacing } from "@ambient-memo/design-tokens";
import { useSelfEnrollment } from "@ambient-memo/engine-client/react";
import { Button, Text } from "@ambient-memo/ui-native";
import { useNavigation } from "@react-navigation/native";
import { View } from "react-native";

import type { RootNavigation } from "../navigation/types.ts";

// 自分の声を登録する案内。登録そのものは SelfEnrollmentScreen で行う。
// 初回は登録すると消える。again を渡すと、登録済みのときも登録し直す操作を出す
export function SelfEnrollmentCard({
  isListening,
  again = false,
}: {
  isListening: boolean;
  again?: boolean;
}) {
  const navigation = useNavigation<RootNavigation>();
  const { status } = useSelfEnrollment();
  const isAgain = again && status === "enrolled";
  if (status !== "not-enrolled" && !isAgain) return null;

  return (
    <View style={{ gap: spacing.sm }}>
      <Text variant="label" accessibilityRole="header">
        {isAgain ? "声を登録し直す" : "自分の声を登録する"}
      </Text>
      <Text variant="caption" color="fgMuted">
        {isAgain
          ? "ふだん聞き取りをする端末の置き場所で読むと、見分けやすくなります。過去の発話の話者は変わりません。"
          : "「自分」と他の人を見分けるために使います。録音は端末の外へ送らず、保存もしません。"}
      </Text>
      <Button
        label={isAgain ? "声を登録し直す" : "声を登録する"}
        variant="secondary"
        disabled={isListening}
        onPress={() => navigation.navigate("SelfEnrollment")}
      />
    </View>
  );
}
