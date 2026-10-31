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
}
