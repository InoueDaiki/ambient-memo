import UIKit
import UniformTypeIdentifiers

/**
 * 書き出した動作の記録を OS の画面に渡す。どちらも利用者の操作でだけ動き、送り先と保存先は利用者が選ぶ。
 *
 * - 共有: UIActivityViewController(Android の共有の画面)
 * - 保存: ファイル App への書き出し(Android の「ダウンロードに保存」に当たる)。UIDocumentPickerViewController で写す
 */
@MainActor
enum DiagnosticsSharing {
    static func share(_ file: URL) async {
        guard let presenter = topViewController() else { return }
        let controller = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        // iPad では吹き出しの位置が要る
        controller.popoverPresentationController?.sourceView = presenter.view
        controller.popoverPresentationController?.sourceRect = CGRect(
            x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 0, height: 0
        )
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            controller.completionWithItemsHandler = { _, _, _, _ in continuation.resume() }
            presenter.present(controller, animated: true)
        }
    }

    /// 保存したら true、利用者がやめたら false。
    static func export(_ file: URL) async -> Bool {
        guard let presenter = topViewController() else { return false }
        let picker = UIDocumentPickerViewController(forExporting: [file], asCopy: true)
        let delegate = ExportDelegate()
        picker.delegate = delegate
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            delegate.onFinish = { saved in
                continuation.resume(returning: saved)
                // 閉じるまでデリゲートを持つ
                _ = delegate
            }
            presenter.present(picker, animated: true)
        }
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

private final class ExportDelegate: NSObject, UIDocumentPickerDelegate {
    var onFinish: ((Bool) -> Void)?

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        finish(true)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finish(false)
    }

    private func finish(_ saved: Bool) {
        let callback = onFinish
        onFinish = nil
        callback?(saved)
    }
}
