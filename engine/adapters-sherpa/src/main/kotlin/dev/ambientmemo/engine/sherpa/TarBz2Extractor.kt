package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.ModelDownloadException
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * tar.bz2 から必要なファイルだけを取り出す。[onRead] は配布物を読んだバイト数の累計を受け取る。
 *
 * 名前がカタログと一致するファイルだけを書く。配布物の中のパスは書き込み先に使わない。
 */
internal class TarBz2Extractor(private val job: Job, private val onRead: (Long) -> Unit) {
    /** [directory] の下の [files] を、[archive] から [target] へ取り出す。 */
    fun extract(files: List<ModelFile>, directory: String, archive: File, target: File) {
        val wanted = files.associateByTo(HashMap()) { "$directory/${it.name}" }
        try {
            CountingInputStream(FileInputStream(archive), onRead).use { counted ->
                val bzip2 = BZip2CompressorInputStream(BufferedInputStream(counted, BUFFER_BYTES), true)
                extractEntries(TarArchiveInputStream(bzip2), wanted, target)
            }
        } catch (e: ExtractWriteException) {
            throw storageFailure(target.usableSpace, e)
        } catch (e: IOException) {
            throw ModelDownloadException(ModelDownloadFailure.CORRUPTED, cause = e)
        }
    }

    /** 必要なファイルがすべて見つかったら、残りは読まない。 */
    private fun extractEntries(tar: TarArchiveInputStream, wanted: MutableMap<String, ModelFile>, target: File) {
        while (wanted.isNotEmpty()) {
            val entry = tar.nextEntry ?: throw ModelDownloadException(ModelDownloadFailure.CORRUPTED)
            val file = wanted.remove(entry.name.removePrefix("./"))
            if (file == null) drain(tar) else extractFile(tar, file, File(target, file.name))
        }
    }

    /** 大きな不要なファイルの読み飛ばしも中断できるように、自分で読む。 */
    private fun drain(input: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        do {
            job.ensureActive()
        } while (input.read(buffer) >= 0)
    }

    private fun extractFile(input: InputStream, expected: ModelFile, target: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        val isValid = FileOutputStream(target).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L
            while (written <= expected.sizeBytes) {
                job.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                digest.update(buffer, 0, count)
                write(output, buffer, count)
            }
            written == expected.sizeBytes
        }
        if (!isValid || digest.hex() != expected.sha256) throw ModelDownloadException(ModelDownloadFailure.CORRUPTED)
    }

    private fun write(output: OutputStream, buffer: ByteArray, count: Int) {
        try {
            output.write(buffer, 0, count)
        } catch (e: IOException) {
            throw ExtractWriteException(e)
        }
    }
}

/** 読み取りの失敗と分けて、書き込みの失敗を伝える。 */
private class ExtractWriteException(cause: IOException) : IOException(cause)

/** 読んだバイト数の累計を知らせる。展開の進み具合は、圧縮された側の位置で表す。 */
private class CountingInputStream(
    input: InputStream,
    private val onRead: (Long) -> Unit,
) : FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) add(it) }

    private fun add(count: Int) {
        total += count
        onRead(total)
    }
}
