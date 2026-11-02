import type { Todo } from "@ambient-memo/engine-client";
import { CheckItem } from "@ambient-memo/ui-native";

import { candidateTag, confirmedDue, todoKindLabels } from "./format.ts";

export interface TodoRowProps {
  todo: Todo;
  isCompleting: boolean;
  onComplete: (id: string) => void;
  // 渡すと、行を押して抽出元のメモを開ける
  onOpenMemo?: (memoId: string) => void;
  // タイトルの代わりに内容を全部出す
  showsTask?: boolean;
}

export function TodoRow({ todo, isCompleting, onComplete, onOpenMemo, showsTask }: TodoRowProps) {
  const isDone = todo.status === "done";
  const text = showsTask ? todo.task : todo.title;
  return (
    <CheckItem
      text={text}
      label={todoKindLabels[todo.kind]}
      meta={confirmedDue(todo)}
      tag={candidateTag(todo)}
      checked={isDone}
      checkLabel={isDone ? `${text}は完了済み` : `${text}を完了にする`}
      // 完了を取り消す操作はまだない
      disabled={isDone || isCompleting}
      onCheck={() => onComplete(todo.id)}
      onPress={onOpenMemo && (() => onOpenMemo(todo.memoId))}
    />
  );
}
