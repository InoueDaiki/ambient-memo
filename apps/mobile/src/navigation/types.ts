import type { RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

export type RootStackParamList = {
  Tabs: undefined;
  MemoDetail: { memoId: string };
  QuestionDetail: { questionId: number };
  Speakers: undefined;
  SelfEnrollment: undefined;
  Diagnostics: undefined;
};

export type RootNavigation = NativeStackNavigationProp<RootStackParamList>;
export type RootRoute<Name extends keyof RootStackParamList> = RouteProp<RootStackParamList, Name>;
