package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.DiagnosticEvent
import dev.ambientmemo.engine.domain.DiagnosticProcess
import dev.ambientmemo.engine.domain.DiagnosticsLog
import dev.ambientmemo.engine.domain.encodeDiagnostic
import java.io.File
import java.io.IOException

private const val DIRECTORY = "diagnostics"
private const val SESSION_FILE = "session"
private const val MAX_RECORDING_MILLIS = 7L * 24 * 60 * 60 * 1_000
private const val MAX_FILE_BYTES = 8L * 1_024 * 1_024

/** [isFull] は大きさの上限に達して、新しい出来事を残していない。 */
data class DiagnosticsStatus(
    val isRecording: Boolean,
    val startedAtMillis: Long?,
    val endsAtMillis: Long?,
    val sizeBytes: Long,
    val isFull: Boolean,
)

/**
 * 診断の記録をファイルに残す。出来事は 1 行の JSON で、[DiagnosticEvent] の数値と列挙だけが入る。
 *
 * 聞き取りと `:organize` が同じ記録に書くので、状態は `session` のファイルで共有し、書き込むファイルはプロセスごとに分ける。
 * 記録は開始の時刻と終わる時刻だけを持ち、止めるときは終わる時刻を今にする。止め忘れても [MAX_RECORDING_MILLIS] で止まる。
 *
 * Android の型に依存しないので、JVM でテストする。
 */
internal class DiagnosticsJournal(
    filesDirectory: File,
    private val process: DiagnosticProcess,
    private val clock: Clock,
) : DiagnosticsLog {
    private val directory = File(filesDirectory, DIRECTORY)
    private val sessionFile = File(directory, SESSION_FILE)
    private val events = File(directory, "${process.name.lowercase()}.jsonl")

    private class Session(val startedAtMillis: Long, val endsAtMillis: Long)

    @Synchronized
    fun status(): DiagnosticsStatus {
        val session = session()
        val files = eventFiles()
        return DiagnosticsStatus(
            isRecording = session.isRunning(),
            startedAtMillis = session?.startedAtMillis,
            endsAtMillis = session?.endsAtMillis,
            sizeBytes = files.sumOf { it.length() },
            isFull = files.any { it.length() >= MAX_FILE_BYTES },
        )
    }

    /** 前の記録を消して始める。 */
    @Synchronized
    fun start() {
        clear()
        val now = clock.nowMillis()
        write(now, now + MAX_RECORDING_MILLIS)
    }

    @Synchronized
    fun stop() {
        session()?.takeIf { it.isRunning() }?.let { write(it.startedAtMillis, clock.nowMillis()) }
    }

    @Synchronized
    fun clear() {
        directory.deleteRecursively()
    }

    /** 書けなくても聞き取りや整理を止めない。その分の記録は諦める。 */
    @Synchronized
    @Suppress("SwallowedException")
    override fun record(event: DiagnosticEvent) {
        if (!session().isRunning() || events.length() >= MAX_FILE_BYTES) return
        try {
            events.appendText(encodeDiagnostic(clock.nowMillis(), process, event) + "\n")
        } catch (e: IOException) {
            // 空き容量がないときなど
        }
    }

    /** [outputDirectory] の前の書き出しを消して、1 つのファイルにまとめる。記録がなければ null を返す。 */
    @Synchronized
    fun export(outputDirectory: File, header: String, footer: String): File? {
        val session = session() ?: return null
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()
        val file = File(outputDirectory, "ambient-memo-diagnostics-${session.startedAtMillis}.jsonl")
        file.bufferedWriter().use { writer ->
            writer.appendLine(header)
            eventFiles().forEach { events -> events.forEachLine { writer.appendLine(it) } }
            writer.appendLine(footer)
        }
        return file
    }

    private fun Session?.isRunning() = this != null && clock.nowMillis() < endsAtMillis

    private fun eventFiles() = directory.listFiles { file -> file.extension == "jsonl" }.orEmpty().sortedBy { it.name }

    private fun write(startedAtMillis: Long, endsAtMillis: Long) {
        directory.mkdirs()
        sessionFile.writeText("$startedAtMillis $endsAtMillis")
    }

    private fun session(): Session? {
        val values = runCatching { sessionFile.readText() }.getOrNull()?.split(" ")?.mapNotNull(String::toLongOrNull)
        return values?.takeIf { it.size == 2 }?.let { Session(it[0], it[1]) }
    }
}
