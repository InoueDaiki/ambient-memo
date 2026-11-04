package dev.ambientmemo.engine.domain.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.io.File

/**
 * `engine/spec/` の共通テストを流す。ファイルごとにまとめ、ケースごとに名前の付いたテストにする。
 *
 * ファイルは `src/test/resources/spec`(`engine/spec` へのシンボリックリンク)からテストのリソースとして読む。
 * リソースにすると Gradle が JSON の変更を入力として扱い、JSON だけを直したときにもテストが流れ直す。
 */
class SpecTest {
    @TestFactory
    fun cases(): List<DynamicContainer> = specFiles().map { file ->
        val spec = Json.parseToJsonElement(file.readText()).jsonObject
        val area = spec.getValue("area").jsonPrimitive.content
        val handler = SpecAreas.handlers[area] ?: fail("$area の実行役がありません")
        dynamicContainer(
            area,
            spec.getValue("cases").jsonArray.map { element ->
                val case = element.jsonObject
                val name = case.getValue("name").jsonPrimitive.content
                dynamicTest(name) { check(handler, case.getValue("input").jsonObject, case.getValue("expected")) }
            },
        )
    }

    @Test
    fun `ファイル名と area が揃い、ケースの名前が重ならず、どの area にも実行役がある`() {
        val files = specFiles()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val spec = Json.parseToJsonElement(file.readText()).jsonObject
            val area = spec.getValue("area").jsonPrimitive.content
            assertEquals("$area.json", file.name)
            assertTrue(area in SpecAreas.handlers) { "$area の実行役がありません" }
            listOf("description", "inputSchema", "expectedSchema").forEach { key ->
                assertTrue(spec[key] != null) { "$area に $key がありません" }
            }
            val names = spec.getValue("cases").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(names.size, names.toSet().size) { "$area のケースの名前が重なっています" }
        }
        val areas = files.map { it.name.removeSuffix(".json") }.toSet()
        assertEquals(SpecAreas.handlers.keys, areas)
    }

    private fun check(handler: (JsonObject) -> JsonElement, input: JsonObject, expected: JsonElement) {
        val actual = handler(input)
        val problems = jsonMismatches(expected, actual)
        if (problems.isNotEmpty()) {
            fail("${problems.joinToString("\n")}\n実際の値: $actual")
        }
    }

    private fun specFiles(): List<File> {
        val url = checkNotNull(SpecTest::class.java.getResource("/spec/README.md")) { "spec のリソースがありません" }
        val directory = File(url.toURI()).parentFile
        return directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    }
}
