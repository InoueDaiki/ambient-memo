// Android の sherpa-onnx を使うアダプター。アプリはこちらを使う。
// JVM の jar と同じパッケージのクラスを持つので、adapters-sherpa-jvm と同じクラスパスに置かない
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ambientmemo.engine.sherpa.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":adapters-sherpa"))
    implementation(libs.sherpa.onnx.android) { artifact { type = "aar" } }
}
