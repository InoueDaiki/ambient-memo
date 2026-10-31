package dev.ambientmemo.engine.sherpa

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val CONNECT_TIMEOUT_MILLIS = 30_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val MAX_REDIRECTS = 5
private const val HTTP_TEMPORARY_REDIRECT = 307
private const val HTTP_PERMANENT_REDIRECT = 308
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

private val REDIRECTS = setOf(
    HttpURLConnection.HTTP_MOVED_PERM,
    HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER,
    HTTP_TEMPORARY_REDIRECT,
    HTTP_PERMANENT_REDIRECT,
)

/** GitHub Releases の配布物は、github.com から署名付きの URL へ転送される。 */
private val MODEL_HOSTS = setOf(
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
)

/** カタログの URL と、その転送先として認める場所。 */
fun isModelHost(url: URL): Boolean = url.protocol == "https" && url.host in MODEL_HOSTS

/** [offsetBytes] は [body] の先頭の位置。続きを頼んでも、取得先が先頭から返すことがある。 */
class ModelResponse(
    val offsetBytes: Long,
    val body: InputStream,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() = onClose()
}

/** 頼んだ位置が配布物の範囲外だった。 */
class ModelRangeException : IOException("範囲外の位置を頼みました")

/** 配布物を読む。モデルの取得のためだけにあり、端末のデータを送らない。 */
interface ModelTransport {
    /** [url] の配布物の [offsetBytes] から先を読む。読めないときは [IOException] を投げる。 */
    fun open(url: String, offsetBytes: Long): ModelResponse
}

/**
 * HTTP で読む。送るのは配布物の位置の指定だけ。
 *
 * 転送先も [isAllowed] で確かめる。自動の転送に任せると、確かめずに別の場所へつながるため。
 */
class HttpModelTransport(private val isAllowed: (URL) -> Boolean = ::isModelHost) : ModelTransport {
    override fun open(url: String, offsetBytes: Long): ModelResponse {
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (!isAllowed(target)) throw IOException("許可していない取得先です")
            val connection = connect(target, offsetBytes)
            val status = connection.responseCode
            if (status !in REDIRECTS) return responseOf(connection, status)
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            target = URL(target, location ?: throw IOException("転送先がありません"))
        }
        throw IOException("転送が多すぎます")
    }

    private fun connect(url: URL, offsetBytes: Long): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            // 圧縮されると、位置の指定が配布物のバイト数と合わなくなる
            setRequestProperty("Accept-Encoding", "identity")
            if (offsetBytes > 0) setRequestProperty("Range", "bytes=$offsetBytes-")
        }

    private fun responseOf(connection: HttpURLConnection, status: Int): ModelResponse = try {
        when (status) {
            HttpURLConnection.HTTP_OK -> ModelResponse(0, connection.inputStream, connection::disconnect)
            HttpURLConnection.HTTP_PARTIAL ->
                ModelResponse(startOf(connection), connection.inputStream, connection::disconnect)
            HTTP_RANGE_NOT_SATISFIABLE -> throw ModelRangeException()
            else -> throw IOException("取得先の応答: $status")
        }
    } catch (e: IOException) {
        connection.disconnect()
        throw e
    }

    /** `Content-Range: bytes 100-199/200` の 100 を読む。 */
    private fun startOf(connection: HttpURLConnection): Long =
        connection.getHeaderField("Content-Range")
            ?.removePrefix("bytes ")
            ?.substringBefore('-')
            ?.trim()
            ?.toLongOrNull()
            ?: throw IOException("Content-Range を読めません")
}
