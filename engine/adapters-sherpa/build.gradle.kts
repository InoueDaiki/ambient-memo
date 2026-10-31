// sherpa-onnx の JVM の jar と Android の AAR で共有する部分。どちらの API にも依存しない。
// 本物の呼び出しは adapters-sherpa-jvm と adapters-sherpa-android に分ける。jar と AAR は API が違い、
// どちらも同じパッケージのクラスを持つので、同じクラスパスに置かない
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))
    // モデルの配布物(tar.bz2)の展開。Android にも JDK にも bzip2 の実装がない
    implementation(libs.commons.compress)

    testFixturesApi(testFixtures(project(":domain")))

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
