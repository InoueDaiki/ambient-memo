package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.domain.OrganizeAttempts
import java.io.File
import java.util.Properties

/**
 * 回数をファイルに残す。発話の id と回数だけで、発話の中身は書かない。
 *
 * スキーマを増やすほどの情報ではなく、失っても数え直すだけなので、DB に入れない。
 * 書き込みは一時ファイルからの名前の変更にして、書きかけのファイルを残さない。1つのプロセスからだけ使う。
 */
class FileOrganizeAttempts(private val file: File) : OrganizeAttempts {
    override fun count(firstUtteranceId: Long): Int =
        read().getProperty(firstUtteranceId.toString())?.toIntOrNull() ?: 0

    override fun set(firstUtteranceId: Long, count: Int) {
        write(read().apply { setProperty(firstUtteranceId.toString(), count.toString()) })
    }

    override fun clear(firstUtteranceId: Long) {
        val properties = read()
        if (properties.remove(firstUtteranceId.toString()) != null) write(properties)
    }

    private fun read(): Properties = Properties().apply {
        if (file.isFile) file.inputStream().use(::load)
    }

    private fun write(properties: Properties) {
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { properties.store(it, null) }
        check(temporary.renameTo(file)) { "回数を保存できません" }
    }
}
