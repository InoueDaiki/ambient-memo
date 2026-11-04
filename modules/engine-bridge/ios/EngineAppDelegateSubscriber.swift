import AmbientMemoEngine
import ExpoModulesCore
import UIKit

/**
 * 起動の途中で、整理の BGProcessingTask を登録する。BGTaskScheduler は起動が終わる前の登録しか受け付けないので、
 * モジュールが作られるのを待たずにここで行う。整理は充電中に OS が始める。
 */
public final class EngineAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    OrganizeSchedule.register { task in AmbientEngine.shared.runScheduledOrganize(task) }
    #if DEBUG
    runDebugCommand()
    #endif
    return true
  }

  #if DEBUG
  /**
   * Debug だけ。画面を触れないシミュレーターで、画面のボタンと同じ操作をする(Android の DebugMemoReceiver に当たる開発用の口)。
   * `xcrun simctl launch booted <アプリ ID> -engineDebug <操作>` で起動すると、前面になった後に 1 回だけ行う。
   * Release にはこのコードが入らない。
   *
   * - start-model-download: 「モデルを取得する」
   * - start-listening: 聞き取りを始める
   */
  private func runDebugCommand() {
    let arguments = ProcessInfo.processInfo.arguments
    guard let index = arguments.firstIndex(of: "-engineDebug"), index + 1 < arguments.count else { return }
    let command = arguments[index + 1]
    let holder = ObserverHolder()
    holder.observer = NotificationCenter.default.addObserver(
      forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
    ) { _ in
      guard let observer = holder.observer else { return }
      NotificationCenter.default.removeObserver(observer)
      holder.observer = nil
      let engine = AmbientEngine.shared
      switch command {
      case "start-model-download":
        engine.startModelDownload(.speech)
      case "start-listening":
        Task {
          do {
            try await engine.startCapture()
          } catch {
            NSLog("AmbientMemoEngine: 聞き取りを始められません: %@", String(describing: error))
          }
        }
      default:
        NSLog("AmbientMemoEngine: 知らない操作です: %@", command)
      }
    }
  }
  #endif
}

#if DEBUG
/// 1 回だけ受け取るための入れ物。メインのスレッドだけで触る。
private final class ObserverHolder: @unchecked Sendable {
  var observer: NSObjectProtocol?
}
#endif
