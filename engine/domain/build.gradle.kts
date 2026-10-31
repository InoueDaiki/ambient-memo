// Android のプラグインを入れない。Android の API を呼ぶとコンパイルが通らない
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // LLM の自由形式の JSON を JsonElement として読むだけなので、コンパイラプラグインは入れない
    implementation(libs.kotlinx.serialization.json)

    // Fake と契約テストは adapters のテストからも使う
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
