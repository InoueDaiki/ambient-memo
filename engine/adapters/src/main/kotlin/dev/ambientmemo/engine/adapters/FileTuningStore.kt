package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.domain.Tuning
import java.io.File

/**
 * 調整の値をファイルに残す。数だけで、発話や声は書かない。
 *
 * 整理は別のプロセスで動くので、どちらのプロセスからも読めるファイルにする。書くのは画面のあるプロセスだけ。
 * 書き込みは一時ファイルからの名前の変更にして、読む側に書きかけのファイルを見せない。
 * スキーマを増やさないので、値を消しても端末のデータは変わらない。読めなければ既定値を使う。
 */
class FileTuningStore(private val file: File) {
    fun load(): Tuning = if (file.isFile) Tuning.decode(file.readText()) else Tuning.DEFAULT

    fun save(tuning: Tuning) {
        if (tuning == Tuning.DEFAULT) {
            if (file.exists()) check(file.delete()) { "調整の値を消せません" }
            return
        }
        val temporary = File(file.path + ".tmp")
        temporary.writeText(tuning.encode())
        check(temporary.renameTo(file)) { "調整の値を保存できません" }
    }
}
