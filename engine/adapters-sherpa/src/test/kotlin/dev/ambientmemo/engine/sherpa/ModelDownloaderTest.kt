@file:Suppress("FunctionNaming", "MagicNumber") // テスト名は日本語の文にする

package dev.ambientmemo.engine.sherpa

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ambientmemo.engine.domain.ModelDownloadException
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import dev.ambientmemo.engine.domain.ModelDownloadPhase
import dev.ambientmemo.engine.domain.ModelDownloadPlan
import dev.ambientmemo.engine.domain.ModelDownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

private const val TIMEOUT_MILLIS = 10_000L
private const val ARTIFACT_BYTES = 1 shl 20

private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun modelFile(name: String, bytes: ByteArray) = ModelFile(name, bytes.size.toLong(), sha256(bytes))

private fun tarBz2(entries: Map<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
        entries.forEach { (name, content) ->
            tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
            tar.write(content)
            tar.closeArchiveEntry()
        }
    }
    return bytes.toByteArray()
}

/** Range を読む、本物の HTTP のサーバー。振る舞いの崩れ方を差し替えられる。 */
private class TestServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val artifacts = HashMap<String, ByteArray>()

    /** 記録した Range の見出し。null は指定なし。 */
    val ranges: MutableList<String?> = Collections.synchronizedList(ArrayList())

    /** 次の応答を、この長さまで送って接続を切る。 */
    @Volatile
    var cutAfterBytes: Int? = null

    /** Range を無視して先頭から返す。 */
    @Volatile
    var ignoresRange = false

    /** 送り始めた後に、この門が開くまで止まる。 */
    @Volatile
    var gate: CountDownLatch? = null

    init {
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.start()
    }

    fun put(path: String, bytes: ByteArray) {
        artifacts[path] = bytes
    }

    fun redirect(from: String, to: String) {
        server.createContext(from) { exchange ->
            exchange.use {
                it.responseHeaders.add("Location", to)
                it.sendResponseHeaders(302, -1)
            }
        }
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val artifact = artifacts[exchange.requestURI.path]
        if (artifact == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")
        ranges += range
        val start = range?.takeUnless { ignoresRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        if (start >= artifact.size && start > 0) {
            exchange.sendResponseHeaders(416, -1)
            return
        }
        val length = artifact.size - start
        if (start > 0) {
            exchange.responseHeaders.add("Content-Range", "bytes $start-${artifact.size - 1}/${artifact.size}")
        }
        exchange.sendResponseHeaders(if (start > 0) 206 else 200, length.toLong())
        val cut = cutAfterBytes
        cutAfterBytes = null
        val sent = minOf(length, cut ?: length)
        val gate = gate
        val first = if (gate == null) 0 else sent / 2
        exchange.responseBody.write(artifact, start, first)
        exchange.responseBody.flush()
        gate?.await()
        exchange.responseBody.write(artifact, start + first, sent - first)
    }
}

class ModelDownloaderTest {
    @TempDir
    lateinit var root: File

    private val server = TestServer()
    private val encoder = Random(1).nextBytes(ARTIFACT_BYTES)
    private val tokens = Random(2).nextBytes(1024)
    private val unused = Random(3).nextBytes(ARTIFACT_BYTES)
    private val single = Random(4).nextBytes(ARTIFACT_BYTES)

    private val encoderFile = modelFile("encoder.onnx", encoder)
    private val tokensFile = modelFile("tokens.txt", tokens)
    private val singleFile = modelFile("gemma.litertlm", single)

    private val archive = tarBz2(
        mapOf(
            "pkg/unused.onnx" to unused,
            "pkg/encoder.onnx" to encoder,
            "pkg/test_wavs/0.wav" to unused,
            "pkg/tokens.txt" to tokens,
        ),
    )

    private val store by lazy { ModelStore(File(root, "models")) }
    private val work by lazy { File(root, "work") }
    private val progress = Collections.synchronizedList(ArrayList<ModelDownloadProgress>())
    private var usable = Long.MAX_VALUE

    private fun downloader() = ModelDownloader(
        store = store,
        workRoot = work,
        transport = HttpModelTransport { it.host == "127.0.0.1" },
        usableBytes = { usable },
    )

    private fun archivePackage(bytes: ByteArray = archive, files: List<ModelFile> = listOf(encoderFile, tokensFile)) =
        ModelPackage(
            "pkg",
            files,
            ModelSource.TarBz2(server.url("/pkg.tar.bz2"), bytes.size.toLong(), sha256(bytes), "pkg"),
        ).also { server.put("/pkg.tar.bz2", bytes) }

    private fun singlePackage() =
        ModelPackage("gemma", listOf(singleFile), ModelSource.SingleFile(server.url("/gemma"), singleFile))
            .also { server.put("/gemma", single) }

    private fun download(vararg packages: ModelPackage) = runBlocking {
        withTimeout(TIMEOUT_MILLIS) { downloader().download(packages.asList()) { progress += it } }
    }

    private fun failure(vararg packages: ModelPackage): ModelDownloadException =
        assertThrows(ModelDownloadException::class.java) { download(*packages) }

    private fun installed(modelPackage: ModelPackage, file: ModelFile) = File(store.pathOf(modelPackage, file))

    private fun part(modelPackage: ModelPackage) = File(File(work, modelPackage.id), "artifact.part")

    @AfterEach
    fun stopServer() {
        server.gate?.countDown()
        server.stop()
    }

    @Test
    fun `単一ファイルの配布物を取得して置く`() {
        val modelPackage = singlePackage()

        download(modelPackage)

        assertArrayEquals(single, installed(modelPackage, singleFile).readBytes())
        assertTrue(store.verify(modelPackage))
        assertFalse(File(work, modelPackage.id).exists())
    }

    @Test
    fun `tar_bz2 から必要なファイルだけを取り出し、配布物を消す`() {
        val modelPackage = archivePackage()

        download(modelPackage)

        assertArrayEquals(encoder, installed(modelPackage, encoderFile).readBytes())
        assertArrayEquals(tokens, installed(modelPackage, tokensFile).readBytes())
        val names = File(root, "models/pkg").list().orEmpty().toSet()
        assertEquals(setOf("encoder.onnx", "tokens.txt", ".verified"), names)
        assertFalse(File(work, "pkg").exists())
        assertTrue(store.verify(modelPackage))
    }

    @Test
    fun `取得、確認、展開の順に進み具合を知らせる`() {
        val modelPackage = archivePackage()

        download(modelPackage)

        assertEquals(
            listOf(ModelDownloadPhase.DOWNLOADING, ModelDownloadPhase.VERIFYING, ModelDownloadPhase.EXTRACTING),
            progress.map { it.phase }.distinct(),
        )
        val finalDownload = progress.last { it.phase == ModelDownloadPhase.DOWNLOADING }
        assertEquals(archive.size.toLong(), finalDownload.completedBytes)
        assertEquals(archive.size.toLong(), finalDownload.totalBytes)
    }

    @Test
    fun `複数のパッケージの進み具合は合計で数える`() {
        val first = singlePackage()
        val second = archivePackage()

        download(first, second)

        val total = single.size.toLong() + archive.size
        val final = progress.last { it.phase == ModelDownloadPhase.DOWNLOADING }
        assertEquals(ModelDownloadProgress(ModelDownloadPhase.DOWNLOADING, total, total), final)
    }

    @Test
    fun `入っているパッケージは通信しない`() {
        val modelPackage = singlePackage()
        download(modelPackage)
        server.ranges.clear()

        download(modelPackage)

        assertEquals(emptyList<String?>(), server.ranges.toList())
        assertEquals(ModelDownloadPlan(0, 0), downloader().plan(listOf(modelPackage)))
    }

    @Test
    fun `配布物のハッシュが合わなければ何も置かず、途中のファイルも残さない`() {
        val modelPackage = ModelPackage(
            "pkg",
            listOf(encoderFile, tokensFile),
            ModelSource.TarBz2(server.url("/pkg.tar.bz2"), archive.size.toLong(), "0".repeat(64), "pkg"),
        )
        server.put("/pkg.tar.bz2", archive)

        assertEquals(ModelDownloadFailure.CORRUPTED, failure(modelPackage).failure)

        assertFalse(File(root, "models/pkg").exists())
        assertFalse(File(work, "pkg").exists())
    }

    @Test
    fun `取り出したファイルのハッシュが合わなければ何も置かない`() {
        val wrong = ModelFile("tokens.txt", tokens.size.toLong(), "0".repeat(64))
        val modelPackage = archivePackage(files = listOf(encoderFile, wrong))

        assertEquals(ModelDownloadFailure.CORRUPTED, failure(modelPackage).failure)

        assertFalse(File(root, "models/pkg").exists())
        assertFalse(File(work, "pkg").exists())
    }

    @Test
    fun `配布物に必要なファイルがなければ何も置かない`() {
        val missing = modelFile("joiner.onnx", tokens)
        val modelPackage = archivePackage(files = listOf(encoderFile, tokensFile, missing))

        assertEquals(ModelDownloadFailure.CORRUPTED, failure(modelPackage).failure)

        assertFalse(File(root, "models/pkg").exists())
    }

    @Test
    fun `配布物の中のパスで外へ書かない`() {
        val evil = tarBz2(
            mapOf(
                "pkg/../../escaped.txt" to tokens,
                "pkg/encoder.onnx" to encoder,
                "pkg/tokens.txt" to tokens,
            ),
        )

        download(archivePackage(evil))

        assertFalse(root.walkTopDown().any { it.name == "escaped.txt" })
    }

    @Test
    fun `本来より長い配布物は書き続けずに止める`() {
        val modelPackage = ModelPackage(
            "gemma",
            listOf(singleFile),
            ModelSource.SingleFile(server.url("/gemma"), singleFile),
        )
        server.put("/gemma", single + ByteArray(ARTIFACT_BYTES))

        assertEquals(ModelDownloadFailure.CORRUPTED, failure(modelPackage).failure)

        assertFalse(File(work, "gemma").exists())
    }

    @Test
    fun `途中で切れたら途中までを残し、次は続きから読む`() {
        val modelPackage = archivePackage()
        val cut = archive.size / 2
        server.cutAfterBytes = cut

        assertEquals(ModelDownloadFailure.NETWORK, failure(modelPackage).failure)

        assertFalse(store.isInstalled(modelPackage))
        val kept = part(modelPackage).length()
        assertTrue(kept in 1..cut.toLong())
        assertEquals(ModelDownloadPlan(archive.size.toLong(), kept), downloader().plan(listOf(modelPackage)))

        download(modelPackage)

        assertEquals(listOf(null, "bytes=$kept-"), server.ranges.toList())
        assertArrayEquals(encoder, installed(modelPackage, encoderFile).readBytes())
        assertFalse(File(work, "pkg").exists())
    }

    @Test
    fun `取得先が Range を無視したら最初から書き直す`() {
        val modelPackage = singlePackage()
        server.cutAfterBytes = single.size / 2
        failure(modelPackage)
        server.ignoresRange = true

        download(modelPackage)

        assertArrayEquals(single, installed(modelPackage, singleFile).readBytes())
    }

    @Test
    fun `取得を終えた配布物は読み直さない`() {
        val modelPackage = singlePackage()
        part(modelPackage).parentFile.mkdirs()
        part(modelPackage).writeBytes(single)

        download(modelPackage)

        assertEquals(emptyList<String?>(), server.ranges.toList())
        assertArrayEquals(single, installed(modelPackage, singleFile).readBytes())
    }

    @Test
    fun `配布物より長い途中のファイルは捨てる`() {
        val modelPackage = singlePackage()
        part(modelPackage).parentFile.mkdirs()
        part(modelPackage).writeBytes(single + ByteArray(1))

        download(modelPackage)

        assertEquals(listOf<String?>(null), server.ranges.toList())
    }

    @Test
    fun `途中のファイルの中身が違えば消して、次は最初から取得する`() {
        val modelPackage = singlePackage()
        part(modelPackage).parentFile.mkdirs()
        part(modelPackage).writeBytes(ByteArray(single.size / 2))

        assertEquals(ModelDownloadFailure.CORRUPTED, failure(modelPackage).failure)
        assertFalse(part(modelPackage).exists())

        download(modelPackage)

        assertArrayEquals(single, installed(modelPackage, singleFile).readBytes())
    }

    @Test
    fun `空き容量が足りなければ通信を始めない`() {
        val modelPackage = archivePackage()
        usable = 1024

        val failure = failure(modelPackage)

        assertEquals(ModelDownloadFailure.INSUFFICIENT_STORAGE, failure.failure)
        assertTrue(failure.shortageBytes > archive.size + encoder.size - usable)
        assertEquals(emptyList<String?>(), server.ranges.toList())
    }

    @Test
    fun `空き容量は残りの取得の分と展開の分を見る`() {
        val modelPackage = archivePackage()
        server.cutAfterBytes = archive.size / 2
        failure(modelPackage)
        val kept = part(modelPackage).length()
        val reserved = 64L * 1024 * 1024
        usable = archive.size - kept + encoder.size + tokens.size + reserved - 1

        assertEquals(1L, failure(modelPackage).shortageBytes)

        usable += 1
        download(modelPackage)
    }

    @Test
    fun `取得先が応じなければ通信の失敗にする`() {
        val modelPackage = ModelPackage(
            "gemma",
            listOf(singleFile),
            ModelSource.SingleFile(server.url("/missing"), singleFile),
        )

        assertEquals(ModelDownloadFailure.NETWORK, failure(modelPackage).failure)
    }

    @Test
    fun `転送は追いかける`() {
        val modelPackage = ModelPackage(
            "gemma",
            listOf(singleFile),
            ModelSource.SingleFile(server.url("/old"), singleFile),
        )
        server.put("/gemma", single)
        server.redirect("/old", server.url("/gemma"))

        download(modelPackage)

        assertArrayEquals(single, installed(modelPackage, singleFile).readBytes())
    }

    @Test
    fun `許可していない場所への転送は追わない`() {
        val transport = HttpModelTransport { it.path != "/gemma" }
        server.put("/gemma", single)
        server.redirect("/old", server.url("/gemma"))

        assertThrows(IOException::class.java) { transport.open(server.url("/old"), 0) }

        assertEquals(emptyList<String?>(), server.ranges.toList())
    }

    @Test
    fun `取得先として認めるのは GitHub の https だけ`() {
        val url = ModelCatalog.ZIPFORMER_REAZON.modelPackage.source?.url

        assertTrue(isModelHost(URL(url)))
        assertFalse(isModelHost(URL("http://github.com/model")))
        assertFalse(isModelHost(URL("https://example.com/model")))
    }

    @Test
    fun `キャンセルしても途中までを残し、続きから読む`() {
        val modelPackage = singlePackage()
        val gate = CountDownLatch(1)
        server.gate = gate

        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job = async(Dispatchers.IO) {
                downloader().download(listOf(modelPackage)) { started.complete(Unit) }
            }
            withTimeout(TIMEOUT_MILLIS) { started.await() }
            job.cancel(CancellationException("テスト"))
            gate.countDown()
            job.cancelAndJoin()
        }
        server.gate = null

        assertFalse(store.isInstalled(modelPackage))
        val kept = part(modelPackage).length()
        assertTrue(kept > 0)

        download(modelPackage)

        assertEquals("bytes=$kept-", server.ranges.last())
        assertArrayEquals(single, installed(modelPackage, singleFile).readBytes())
    }

    @Test
    fun `カタログから外れたモデルの途中のファイルを消す`() {
        val stale = File(work, "old-model/artifact.part")
        stale.parentFile.mkdirs()
        stale.writeBytes(ByteArray(10))

        download(singlePackage())

        assertFalse(stale.exists())
    }

    @Test
    fun `入ったパッケージの作業のファイルが残っていたら消す`() {
        val modelPackage = singlePackage()
        download(modelPackage)
        part(modelPackage).parentFile.mkdirs()
        part(modelPackage).writeBytes(single)

        download(modelPackage)

        assertFalse(File(work, modelPackage.id).exists())
    }

    @Test
    fun `取得先のないパッケージは取得できない`() {
        val modelPackage = ModelPackage("gemma", listOf(singleFile))

        assertThrows(IllegalStateException::class.java) { download(modelPackage) }
    }
}
