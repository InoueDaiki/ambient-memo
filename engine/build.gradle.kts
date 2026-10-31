plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
}

subprojects {
    // apps/mobile の Gradle から includeBuild で読むときの座標(dev.ambientmemo.engine:android など)
    group = "dev.ambientmemo.engine"

    apply(plugin = "io.gitlab.arturbosch.detekt")
}
