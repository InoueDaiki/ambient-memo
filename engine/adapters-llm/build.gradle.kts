// LiteRT-LM の AAR に依存しない部分。読み込みと解放の管理と、失敗の見分けを JVM でテストする
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
