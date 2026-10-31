// LiteRT-LM の AAR を呼ぶアダプター。アプリはこちらを使う
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ambientmemo.engine.llm.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":adapters-llm"))
    implementation(libs.litertlm.android)
}
