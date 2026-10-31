import type { Todo } from "@ambient-memo/engine-client";
import { CheckItem } from "@ambient-memo/ui-native";

import { candidateTag, confirmedDue, todoKindLabels } from "./format.ts";

export interface TodoRowProps {
  todo: Todo;
  isCompleting: boolean;
  onComplete: (id: string) => void;
  // 渡すと、行を押して抽出元のメモを開ける
  onOpenMemo?: (memoId: string) => void;
}

export function TodoRow({ todo, isCompleting, onComplete, onOpenMemo }: TodoRowProps) {
  const isDone = todo.status === "done";
  return (
    <CheckItem
      text={todo.task}
      label={todoKindLabels[todo.kind]}
      meta={confirmedDue(todo)}
      tag={candidateTag(todo)}
      checked={isDone}
      checkLabel={isDone ? `${todo.task}は完了済み` : `${todo.task}を完了にする`}
      // 完了を取り消す操作はまだない
      disabled={isDone || isCompleting}
      onCheck={() => onComplete(todo.id)}
      onPress={onOpenMemo && (() => onOpenMemo(todo.memoId))}
    />
  );
}
