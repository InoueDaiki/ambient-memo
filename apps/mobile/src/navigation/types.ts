import type { RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

export type RootStackParamList = {
  Tabs: undefined;
  MemoDetail: { memoId: string };
};

export type RootNavigation = NativeStackNavigationProp<RootStackParamList>;
export type RootRoute<Name extends keyof RootStackParamList> = RouteProp<RootStackParamList, Name>;
