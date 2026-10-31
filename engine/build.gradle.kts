import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.sqldelight) apply false
}

subprojects {
    // apps/mobile の Gradle から includeBuild で読むときの座標(dev.ambientmemo.engine:android など)
    group = "dev.ambientmemo.engine"

    apply(plugin = "io.gitlab.arturbosch.detekt")

    tasks.withType<Detekt>().configureEach {
        source("src/testFixtures/kotlin")
    }

    // 本物のモデルを使うテストの置き場所。モデルの有無でテストの結果が変わるので、入力にも入れる
    tasks.withType<Test>().configureEach {
        val modelsDir = System.getenv("AMBIENT_MEMO_MODELS_DIR") ?: "$rootDir/.cache/models"
        systemProperty("ambientmemo.modelsDir", modelsDir)
        systemProperty("ambientmemo.requireModels", System.getenv("AMBIENT_MEMO_REQUIRE_MODELS") ?: "false")
        inputs.property("modelsInstalled", file(modelsDir).list()?.sorted().orEmpty())
    }
}
