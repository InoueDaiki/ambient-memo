// JVM の sherpa-onnx を使うアダプター。テストと、Mac で動かす評価ツールから使う。
// アプリには入れない。Android は adapters-sherpa-android を使う
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

// 手元の Apple Silicon の Mac と、CI の Linux のネイティブライブラリ
val sherpaNativeLibrary = when (val os = System.getProperty("os.name")) {
    "Mac OS X" -> libs.sherpa.onnx.native.osx.aarch64
    "Linux" -> libs.sherpa.onnx.native.linux.x64
    else -> error("sherpa-onnx の JVM のネイティブライブラリを選べません: $os")
}

dependencies {
    api(project(":adapters-sherpa"))
    implementation(libs.sherpa.onnx.jvm)
    runtimeOnly(sherpaNativeLibrary)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(testFixtures(project(":adapters-sherpa")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
