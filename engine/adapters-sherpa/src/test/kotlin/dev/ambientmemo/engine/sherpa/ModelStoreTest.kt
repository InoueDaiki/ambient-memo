@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.sherpa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
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
    fun `アプリが使うモデルは、すべて固定の https の URL から取得できる`() {
        ModelCatalog.ACTIVE.packages.forEach { modelPackage ->
            val source = checkNotNull(modelPackage.source) { modelPackage.id }
            assertTrue(isModelHost(URL(source.url)), source.url)
            assertTrue(source.sizeBytes > 0, source.url)
            assertTrue(source.sha256.matches(Regex("[0-9a-f]{64}")), source.url)
        }
    }

    @Test
    fun `Gemma は固定のリビジョンの単一ファイルで、聞き取りのモデルと別に持つ`() {
        val model = ModelCatalog.ACTIVE_LLM.modelPackage
        val source = model.source as ModelSource.SingleFile

        assertTrue(source.url.startsWith("https://huggingface.co/litert-community/"))
        assertTrue("/resolve/e6a3892a8c22cf87ff99583996149cf3cb32452b/" in source.url)
        assertEquals(listOf(source.file), model.files)
        assertTrue(ModelCatalog.ACTIVE.packages.none { it.id == model.id })
    }

    @Test
    fun `許可していない取得先のパッケージは取得できない扱いにする`() {
        assertTrue(ModelCatalog.ACTIVE.packages.all { it.isDownloadAllowed })
        assertFalse(ModelCatalog.ACTIVE_LLM.modelPackage.isDownloadAllowed)
        assertFalse(ModelPackage("source-none", emptyList()).isDownloadAllowed)
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
