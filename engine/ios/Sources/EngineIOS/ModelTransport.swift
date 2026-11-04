import Foundation

private let TIMEOUT_SECONDS: TimeInterval = 30
private let MAX_REDIRECTS = 5
private let HTTP_OK = 200
private let HTTP_PARTIAL = 206
private let HTTP_RANGE_NOT_SATISFIABLE = 416

/// GitHub Releases は github.com から署名付きの URL へ、Gemma は huggingface.co から `*.hf.co` の CDN へ転送される。
private let MODEL_HOSTS: Set<String> = [
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "huggingface.co",
]

/// CDN の名前は地域で変わるかもしれないので、仮にドメインごと認める。
private let MODEL_HOST_SUFFIX = ".hf.co"

/// カタログの URL と、その転送先として認める場所。Android の `isModelHost` と同じ。
public func isModelHost(_ url: URL) -> Bool {
    guard url.scheme?.lowercased() == "https", let host = url.host?.lowercased() else { return false }
    return MODEL_HOSTS.contains(host) || host.hasSuffix(MODEL_HOST_SUFFIX)
}

/// 頼んだ位置が配布物の範囲外だった。
struct ModelRangeError: Error {}

/// 通信の失敗。文言に取得先の応答を含めない。
struct ModelTransportError: Error, CustomStringConvertible {
    let description: String
}

/// 配布物を読む。モデルの取得のためだけにあり、端末のデータを送らない。
protocol ModelTransport: Sendable {
    /**
     * [url] の配布物の [offsetBytes] から先を読む。[onStart] は本文の先頭の位置を受け取る。
     * 続きを頼んでも、取得先が先頭から返すことがある。[onData] は受け取った順に呼ぶ。
     */
    func fetch(
        _ url: String,
        offsetBytes: Int64,
        onStart: @escaping @Sendable (Int64) throws -> Void,
        onData: @escaping @Sendable (Data) throws -> Void
    ) async throws
}

/**
 * URLSession で読む。送るのは配布物の位置の指定だけ。Cookie もキャッシュも残さない。
 *
 * 転送先も [isModelHost] で確かめる。自動の転送に任せると、確かめずに別の場所へつながるため。
 */
final class HttpModelTransport: ModelTransport {
    func fetch(
        _ url: String,
        offsetBytes: Int64,
        onStart: @escaping @Sendable (Int64) throws -> Void,
        onData: @escaping @Sendable (Data) throws -> Void
    ) async throws {
        guard let target = URL(string: url), isModelHost(target) else {
            throw ModelTransportError(description: "許可していない取得先です")
        }
        let request = Self.request(target, offsetBytes)
        let delegate = FetchDelegate(offsetBytes: offsetBytes, onStart: onStart, onData: onData)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = TIMEOUT_SECONDS
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: queue)
        defer { session.finishTasksAndInvalidate() }
        let task = session.dataTask(with: request)
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                delegate.continuation = continuation
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    static func request(_ url: URL, _ offsetBytes: Int64) -> URLRequest {
        var request = URLRequest(url: url)
        // 圧縮されると、位置の指定が配布物のバイト数と合わなくなる
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        if offsetBytes > 0 { request.setValue("bytes=\(offsetBytes)-", forHTTPHeaderField: "Range") }
        return request
    }
}

/// 1 回の取得の受け口。デリゲートのキューは直列なので、状態はそのキューの中だけで触る。
private final class FetchDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let offsetBytes: Int64
    private let onStart: @Sendable (Int64) throws -> Void
    private let onData: @Sendable (Data) throws -> Void
    var continuation: CheckedContinuation<Void, any Error>?
    private var redirects = 0
    private var failure: (any Error)?

    init(
        offsetBytes: Int64,
        onStart: @escaping @Sendable (Int64) throws -> Void,
        onData: @escaping @Sendable (Data) throws -> Void
    ) {
        self.offsetBytes = offsetBytes
        self.onStart = onStart
        self.onData = onData
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        redirects += 1
        guard redirects <= MAX_REDIRECTS, let url = request.url, isModelHost(url) else {
            failure = ModelTransportError(description: "許可していない取得先です")
            completionHandler(nil)
            task.cancel()
            return
        }
        completionHandler(HttpModelTransport.request(url, offsetBytes))
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        do {
            guard let http = response as? HTTPURLResponse else {
                throw ModelTransportError(description: "HTTP の応答ではありません")
            }
            switch http.statusCode {
            case HTTP_OK: try onStart(0)
            case HTTP_PARTIAL: try onStart(try Self.startOf(http))
            case HTTP_RANGE_NOT_SATISFIABLE: throw ModelRangeError()
            default: throw ModelTransportError(description: "取得先の応答: \(http.statusCode)")
            }
            completionHandler(.allow)
        } catch {
            failure = error
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard failure == nil else { return }
        do {
            try onData(data)
        } catch {
            failure = error
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        let result: Result<Void, any Error> = if let failure {
            .failure(failure)
        } else if let error {
            .failure(error)
        } else {
            .success(())
        }
        continuation?.resume(with: result)
        continuation = nil
    }

    /// `Content-Range: bytes 100-199/200` の 100 を読む。
    private static func startOf(_ response: HTTPURLResponse) throws -> Int64 {
        guard let header = response.value(forHTTPHeaderField: "Content-Range") else {
            throw ModelTransportError(description: "Content-Range を読めません")
        }
        let range = header.hasPrefix("bytes ") ? String(header.dropFirst("bytes ".count)) : header
        guard let start = range.split(separator: "-").first.flatMap({ Int64($0.trimmingCharacters(in: .whitespaces)) }) else {
            throw ModelTransportError(description: "Content-Range を読めません")
        }
        return start
    }
}
