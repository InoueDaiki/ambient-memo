@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.sherpa

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class ModelStoreTest {
    @TempDir
    lateinit var root: File

    private val content = "モデルの中身".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val file = ModelFile("model.onnx", content.size.toLong(), hash)
    private val modelPackage = ModelPackage("pkg", listOf(file))
    private val models = SpeechModels(
        VadModel(modelPackage, file),
        AsrModel.SenseVoice(modelPackage, file, file),
    )
    private val store by lazy { ModelStore(root) }
    private val path by lazy { File(store.pathOf(modelPackage, file)) }

    private fun install(bytes: ByteArray = content) {
        path.parentFile.mkdirs()
        path.writeBytes(bytes)
    }

    @Test
    fun `ファイルがなければ入っていない`() {
        assertFalse(store.isInstalled(models))
        assertFalse(store.verify(models))
    }

    @Test
    fun `ハッシュが合えば使える`() {
        install()

        assertTrue(store.isInstalled(models))
        assertTrue(store.verify(models))
    }

    @Test
    fun `大きさが同じでも中身が違うファイルは消す`() {
        install(ByteArray(content.size))

        assertFalse(store.verify(models))
        assertFalse(path.exists())
    }

    @Test
    fun `途中までのファイルは入っていない扱いにする`() {
        install(content.copyOf(content.size - 1))

        assertFalse(store.isInstalled(models))
    }

    @Test
    fun `一度確かめたら計算を省き、ファイルが欠けたらやり直す`() {
        install()
        assertTrue(store.verify(models))

        // 中身を変えても印があれば計算しない
        path.writeBytes(ByteArray(content.size))
        assertTrue(store.verify(models))

        path.delete()
        assertFalse(store.verify(models))
    }
}
