import com.android.build.api.artifact.SingleArtifact
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
fun releaseSetting(property: String, variable: String): String =
    providers.environmentVariable(variable).orElse(providers.gradleProperty(property)).getOrElse("").trim()
val releaseKeystore = providers.environmentVariable("ZENCAMPUS_KEYSTORE").orNull?.takeIf { file(it).isFile }
android {
    namespace = "io.github.binoverfl0w.zencampus.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.binoverfl0w.zencampus"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("zencampus.versionCode").get().toInt()
        versionName = providers.gradleProperty("zencampus.version").get()
        buildConfigField("String", "UPDATE_REPO", "\"${releaseSetting("zencampus.updateRepo", "ZENCAMPUS_UPDATE_REPO")}\"")
        buildConfigField("String", "CRASH_DSN", "\"${releaseSetting("zencampus.crashDsn", "ZENCAMPUS_CRASH_DSN")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    // Release builds are signed only when the keystore is provided (the release workflow decodes it from a secret).
    // Every published APK must use this same key, or Android refuses it as an update.
    if (releaseKeystore != null) signingConfigs.create("release") {
        storeFile = file(releaseKeystore)
        storePassword = System.getenv("ZENCAMPUS_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("ZENCAMPUS_KEY_ALIAS")
        keyPassword = System.getenv("ZENCAMPUS_KEY_PASSWORD")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
/**
 * Writes the ID Sentry gives this build's R8 mapping into the APK, so crash reports can name it. The ID is the one
 * `sentry-cli upload-proguard` derives from the file: a name-based (v5) UUID of its contents.
 */
abstract class CrashMappingIdTask : DefaultTask() {
    @get:InputFile abstract val mapping: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun write() {
        val directory = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        val proguard = uuid5(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "guardsquare.com".toByteArray())
        File(directory, "crash-mapping-id.txt").writeText(uuid5(proguard, mapping.get().asFile.readBytes()).toString())
    }

    private fun uuid5(namespace: UUID, name: ByteArray): UUID {
        val prefix = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
        val hash = MessageDigest.getInstance("SHA-1").digest(prefix + name).copyOf(16)
        hash[6] = (hash[6].toInt() and 0x0f or 0x50).toByte()
        hash[8] = (hash[8].toInt() and 0x3f or 0x80).toByte()
        return ByteBuffer.wrap(hash).let { UUID(it.long, it.long) }
    }
}
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val mappingId = tasks.register<CrashMappingIdTask>("${variant.name}CrashMappingId") {
            mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(mappingId, CrashMappingIdTask::output)
    }
}
dependencies {
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.work)
    implementation(libs.lifecycle.process)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.okhttp)
    implementation(libs.sql.android)
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.4")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.4")
}
