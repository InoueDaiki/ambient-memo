plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "dev.ambientmemo.engine.adapters"
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
            // 過去の版の DB を読むマイグレーションテスト用
            val schemaDirectory = layout.projectDirectory.dir("src/main/sqldelight/databases")
            it.inputs.dir(schemaDirectory).withPropertyName("schemaSnapshots")
            it.systemProperty("ambientmemo.schemaDir", schemaDirectory.asFile.path)
        }
    }
}

sqldelight {
    databases {
        create("AmbientMemoDatabase") {
            packageName.set("dev.ambientmemo.engine.adapters.db")
            // Android 7(minSdk 24)の SQLite に合わせる。RETURNING などの新しい構文は使わない
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

// .sq と、過去の版に .sqm を当てた結果が一致しなければテストを失敗させる
tasks.withType<Test>().configureEach {
    dependsOn("verifySqlDelightMigration")
}

dependencies {
    api(project(":domain"))
    implementation(libs.sqldelight.android.driver)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.sqldelight.sqlite.driver)
    testRuntimeOnly(libs.junit.platform.launcher)
}
