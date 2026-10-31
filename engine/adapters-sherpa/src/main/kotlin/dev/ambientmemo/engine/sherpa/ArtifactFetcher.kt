package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.ModelDownloadException
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal const val BUFFER_BYTES = 1 shl 16

/** 書き込みに失敗したときに、空き容量がこれを切っていれば容量不足とみなす。 */
private const val FULL_DISK_BYTES = 1L * 1024 * 1024

internal fun storageFailure(usableBytes: Long, cause: IOException): ModelDownloadException {
    val isFull = usableBytes < FULL_DISK_BYTES
    val failure = if (isFull) ModelDownloadFailure.INSUFFICIENT_STORAGE else ModelDownloadFailure.UNKNOWN
    return ModelDownloadException(failure, cause = cause)
}

/** 配布物を [part] へ読む。[part] があれば続きから読み、取得先が先頭から返したら書き直す。 */
internal class ArtifactFetcher(
    private val transport: ModelTransport,
    private val usableBytes: () -> Long,
) {
    fun fetch(source: ModelSource, part: File, job: Job, onDownloaded: (Long) -> Unit) {
        if (part.length() > source.sizeBytes) part.delete()
        if (part.length() == source.sizeBytes) return

        open(source, part).use { response ->
            val written = FileOutputStream(part, response.offsetBytes > 0).use { output ->
                copy(response, output, source.sizeBytes, job, onDownloaded)
            }
            if (written != source.sizeBytes) throw ModelDownloadException(ModelDownloadFailure.NETWORK)
        }
    }

    private fun open(source: ModelSource, part: File): ModelResponse = try {
        try {
            transport.open(source.url, part.length())
        } catch (_: ModelRangeException) {
            // 残っているファイルが配布物と合っていない
            part.delete()
            transport.open(source.url, 0)
        }
    } catch (e: IOException) {
        throw ModelDownloadException(ModelDownloadFailure.NETWORK, cause = e)
    }

    private fun copy(
        response: ModelResponse,
        output: OutputStream,
        sizeBytes: Long,
        job: Job,
        onDownloaded: (Long) -> Unit,
    ): Long {
        var written = response.offsetBytes
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            job.ensureActive()
            val count = read(response.body, buffer)
            if (count < 0) return written
            written += count
            if (written > sizeBytes) throw ModelDownloadException(ModelDownloadFailure.CORRUPTED)
            write(output, buffer, count)
            onDownloaded(written)
        }
    }

    private fun read(body: InputStream, buffer: ByteArray): Int = try {
        body.read(buffer)
    } catch (e: IOException) {
        throw ModelDownloadException(ModelDownloadFailure.NETWORK, cause = e)
    }

    private fun write(output: OutputStream, buffer: ByteArray, count: Int) {
        try {
            output.write(buffer, 0, count)
        } catch (e: IOException) {
            throw storageFailure(usableBytes(), e)
        }
    }
}
