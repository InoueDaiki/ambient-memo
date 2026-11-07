import { TabBar, useColors } from "@ambient-memo/ui-native";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { DarkTheme, DefaultTheme, NavigationContainer } from "@react-navigation/native";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { useMemo } from "react";
import { useColorScheme } from "react-native";

import { AskScreen } from "../memo/AskScreen.tsx";
import { MemoDetailScreen } from "../memo/MemoDetailScreen.tsx";
import { MemoListScreen } from "../memo/MemoListScreen.tsx";
import { QuestionDetailScreen } from "../memo/QuestionDetailScreen.tsx";
import { DiagnosticsScreen } from "../transcript/DiagnosticsScreen.tsx";
import { SelfEnrollmentScreen } from "../transcript/SelfEnrollmentScreen.tsx";
import { SpeakersScreen } from "../transcript/SpeakersScreen.tsx";
import { TranscriptScreen } from "../transcript/TranscriptScreen.tsx";
import type { RootStackParamList } from "./types.ts";

const Stack = createNativeStackNavigator<RootStackParamList>();
const Tab = createBottomTabNavigator();

const tabItems = [
  { key: "Transcript", label: "文字起こし" },
  { key: "Memos", label: "メモ" },
  { key: "Ask", label: "質問" },
];

function Tabs() {
  return (
    <Tab.Navigator
      screenOptions={{ headerShown: false }}
      tabBar={({ state, navigation }) => (
        <TabBar
          items={tabItems}
          activeKey={state.routes[state.index]?.name ?? "Transcript"}
          onSelect={(key) => navigation.navigate(key)}
        />
      )}
    >
      <Tab.Screen name="Transcript" component={TranscriptScreen} />
      <Tab.Screen name="Memos" component={MemoListScreen} />
      <Tab.Screen name="Ask" component={AskScreen} />
    </Tab.Navigator>
  );
}

// 下のタブで3つの画面を切り替え、メモや質問の詳細はその上に重ねる。
// 戻る操作で、開いた元の画面へ帰るため
export function AppNavigator() {
  const palette = useColors();
  const scheme = useColorScheme();
  const theme = useMemo(() => {
    const base = scheme === "dark" ? DarkTheme : DefaultTheme;
    return { ...base, colors: { ...base.colors, background: palette.canvas } };
  }, [palette, scheme]);

  return (
    <NavigationContainer theme={theme}>
      <Stack.Navigator screenOptions={{ headerShown: false }}>
        <Stack.Screen name="Tabs" component={Tabs} />
        <Stack.Screen name="MemoDetail" component={MemoDetailScreen} />
        <Stack.Screen name="QuestionDetail" component={QuestionDetailScreen} />
        <Stack.Screen name="Speakers" component={SpeakersScreen} />
        <Stack.Screen name="SelfEnrollment" component={SelfEnrollmentScreen} />
        <Stack.Screen name="Diagnostics" component={DiagnosticsScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
