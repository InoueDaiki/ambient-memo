plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ambientmemo.engine.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    api(project(":domain"))
    implementation(project(":adapters"))
    implementation(project(":adapters-sherpa"))
    implementation(project(":adapters-sherpa-android"))
    implementation(project(":adapters-llm-android"))
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // JS の境界の契約テストを、本物の SQLite で流す
    testImplementation(libs.sqldelight.sqlite.driver)
    testRuntimeOnly(libs.junit.platform.launcher)
}
