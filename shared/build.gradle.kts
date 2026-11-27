import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}
kotlin {
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ZenShared"
            isStatic = true
            // Line tables in release builds too, so the dSYM can name the Kotlin function and line of a crash.
            freeCompilerArgs += "-Xadd-light-debug=enable"
        }
    }
    // Android and desktop are both JVMs: files, hashing and the crash handler are written once in jvmCommonMain.
    applyDefaultHierarchyTemplate { common { group("jvmCommon") { withAndroidTarget(); withJvm() } } }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            api(libs.datetime)
            implementation(libs.ktor.core)
            implementation(libs.sql.runtime)
            implementation("com.fleeksoft.ksoup:ksoup:0.2.5")
            implementation("app.cash.sqldelight:coroutines-extensions:2.1.0")
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.sql.android)
        }
        iosMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:2.1.0")
            implementation("io.ktor:ktor-client-darwin:3.2.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        jvmTest.dependencies { implementation(libs.sql.jvm) }
    }
}
compose.resources {
    packageOfResClass = "io.github.binoverfl0w.zencampus.resources"
}
android {
    namespace = "io.github.binoverfl0w.zencampus.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
sqldelight {
    databases {
        create("CampusDatabase") {
            packageName.set("io.github.binoverfl0w.zencampus.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
