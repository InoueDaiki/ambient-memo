pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx の jar と AAR。apps/mobile の同じ指定は modules/engine-bridge/app.plugin.js にある
        ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.k2fsa") }
        }
    }
}

rootProject.name = "engine"

include(":domain", ":adapters", ":adapters-sherpa", ":adapters-sherpa-jvm", ":adapters-sherpa-android", ":adapters-sherpa-android-test", ":android")
