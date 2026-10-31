// adapters-sherpa-android に、端末で契約テストを流すだけのモジュール。アプリには入れない。
// 契約テストのメソッド名に空白があり、DEX に入れるには minSdk 30 が要る。アプリの minSdk を変えないように分けた
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ambientmemo.engine.sherpa.android.test"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            // JUnit の jar がそれぞれ同じ名前のライセンスを持っている
            excludes += "META-INF/LICENSE*"
        }
    }
}

dependencies {
    androidTestImplementation(project(":adapters-sherpa-android"))
    androidTestImplementation(testFixtures(project(":domain")))
    androidTestImplementation(testFixtures(project(":adapters-sherpa")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
