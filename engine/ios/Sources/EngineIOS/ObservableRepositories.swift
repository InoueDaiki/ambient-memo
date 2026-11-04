import EngineDomain
import Foundation

/// 画面が取り直す対象。
public enum MemoChange: Sendable { case memos, todos }

/// 保存のたびに [onChanged] を呼ぶ。画面に一覧の取り直しを知らせるため。Android の ObservableUtteranceRepository と同じ。
final class ObservableUtteranceRepository: UtteranceRepository {
    private let delegate: any UtteranceRepository
    private let onChanged: @Sendable () -> Void

    init(_ delegate: any UtteranceRepository, onChanged: @escaping @Sendable () -> Void) {
        self.delegate = delegate
        self.onChanged = onChanged
    }

    func insert(_ utterance: NewUtterance) async throws -> Utterance {
        let saved = try await delegate.insert(utterance)
        onChanged()
        return saved
    }

    func insertAll(_ utterances: [NewUtterance]) async throws -> [Utterance] {
        let saved = try await delegate.insertAll(utterances)
        onChanged()
        return saved
    }

    func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [Utterance] {
        try await delegate.listOverlapping(fromMillis: fromMillis, toMillis: toMillis)
    }

    func listRecent(limit: Int) async throws -> [Utterance] { try await delegate.listRecent(limit: limit) }

    func find(id: Int64) async throws -> Utterance? { try await delegate.find(id: id) }

    func setSpeaker(id: Int64, speakerId: Int64?) async throws -> Bool {
        let changed = try await delegate.setSpeaker(id: id, speakerId: speakerId)
        onChanged()
        return changed
    }

    func countBySpeaker() async throws -> [Int64: Int] { try await delegate.countBySpeaker() }
}

/// 書き込みが変更を生んだときに、戻る前に [onChanged] を呼ぶ。Android の ObservableMemoRepository と同じ。
final class ObservableMemoRepository: MemoRepository {
    private let delegate: any MemoRepository
    private let onChanged: @Sendable (MemoChange) -> Void

    init(_ delegate: any MemoRepository, onChanged: @escaping @Sendable (MemoChange) -> Void) {
        self.delegate = delegate
        self.onChanged = onChanged
    }

    func listUnorganizedUtterances() async throws -> [Utterance] { try await delegate.listUnorganizedUtterances() }

    func recordMemo(_ memo: NewMemo) async throws -> Memo? {
        let recorded = try await delegate.recordMemo(memo)
        if recorded != nil {
            onChanged(.memos)
            if !memo.todos.isEmpty { onChanged(.todos) }
        }
        return recorded
    }

    func markOrganized(_ utteranceIds: [Int64], organizedAtMillis: Int64) async throws {
        try await delegate.markOrganized(utteranceIds, organizedAtMillis: organizedAtMillis)
    }

    func listMemos() async throws -> [Memo] { try await delegate.listMemos() }

    func listUtteranceIds(memoId: Int64) async throws -> [Int64] { try await delegate.listUtteranceIds(memoId: memoId) }

    func listTodos(memoId: Int64) async throws -> [Todo] { try await delegate.listTodos(memoId: memoId) }

    func listRecentMemos(limit: Int) async throws -> [Memo] { try await delegate.listRecentMemos(limit: limit) }

    func findMemo(memoId: Int64) async throws -> Memo? { try await delegate.findMemo(memoId: memoId) }

    func listMemoUtterances(memoId: Int64) async throws -> [Utterance] {
        try await delegate.listMemoUtterances(memoId: memoId)
    }

    func listTodosByPriority(limit: Int) async throws -> [Todo] { try await delegate.listTodosByPriority(limit: limit) }

    func completeTodo(todoId: Int64, completedAtMillis: Int64) async throws -> TodoCompletion {
        let result = try await delegate.completeTodo(todoId: todoId, completedAtMillis: completedAtMillis)
        if result == .COMPLETED { onChanged(.todos) }
        return result
    }
}
