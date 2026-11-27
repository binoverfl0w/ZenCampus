import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val zenVersion = providers.gradleProperty("zencampus.version").get()
fun releaseSetting(property: String, variable: String): String =
    providers.environmentVariable(variable).orElse(providers.gradleProperty(property)).getOrElse("").trim()

// The version and release settings travel inside the jar, so development runs and installed bundles read the same file.
val releaseInfo = tasks.register("releaseInfo") {
    val settings = mapOf("version" to zenVersion,
        "updateRepo" to releaseSetting("zencampus.updateRepo", "ZENCAMPUS_UPDATE_REPO"),
        "crashDsn" to releaseSetting("zencampus.crashDsn", "ZENCAMPUS_CRASH_DSN"))
    val output = layout.buildDirectory.dir("generated/release")
    inputs.properties(settings)
    outputs.dir(output)
    doLast {
        val file = output.get().file("zencampus-release.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(settings.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}
sourceSets.main { resources.srcDir(releaseInfo) }

kotlin {
    jvmToolchain(21)
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation(libs.serialization.json)
    implementation(libs.ktor.core)
    implementation(libs.ktor.okhttp)
    implementation(libs.sql.jvm)
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("net.java.dev.jna:jna:5.17.0")
    // Windows: DPAPI for saved sessions, the Downloads folder and the battery state.
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("me.friwi:jcefmaven:152.0.6")
    val nativePlatform = when {
        System.getProperty("os.name").startsWith("Mac") ->
            if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "macosx-arm64" else "macosx-amd64"
        System.getProperty("os.name").startsWith("Windows") -> "windows-amd64"
        else -> "linux-amd64"
    }
    runtimeOnly("me.friwi:jcef-natives-$nativePlatform:jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.mock)
    testImplementation(compose.desktop.uiTestJUnit4)
}

// JCEF's macOS native view and JOGL access AWT internals on JDK 21.
val macJcefJvmArgs = listOf(
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
)
val isMac = System.getProperty("os.name").startsWith("Mac")

compose.desktop {
    application {
        mainClass = "io.github.binoverfl0w.zencampus.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.awt.xembedserver=true")
        if (isMac) jvmArgs += macJcefJvmArgs
        nativeDistributions {
            when {
                System.getProperty("os.name").startsWith("Mac") -> targetFormats(TargetFormat.Dmg)
                System.getProperty("os.name").startsWith("Windows") -> targetFormats(TargetFormat.Msi)
                else -> targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            }
            // Windows shows this name on the desktop and Start menu shortcuts and in Installed apps. Linux packages need
            // a lower-case name. Windows treats ZenCampus.exe and zencampus.exe as one file, so an older install still
            // finds and restarts the renamed app after it updates itself.
            packageName = if (System.getProperty("os.name").startsWith("Windows")) "ZenCampus" else "zencampus"
            // macOS requires a positive major package version.
            packageVersion = if (System.getProperty("os.name").startsWith("Mac")) "1." + zenVersion.substringAfter('.') else zenVersion
            // Windows names the app by its launcher's file description in notifications and Task Manager.
            description = if (System.getProperty("os.name").startsWith("Windows")) "ZenCampus" else "A quiet campus companion for University of Augsburg"
            vendor = "ZenCampus"
            modules("java.sql", "java.net.http", "jdk.unsupported", "jdk.crypto.ec", "java.management", "java.naming")
            macOS {
                bundleID = "io.github.binoverfl0w.zencampus.desktop"
                dockName = "ZenCampus"
                appCategory = "public.app-category.education"
                project.file("icons/zencampus.icns").takeIf { it.isFile }?.let { iconFile.set(it) }
                // The hardened runtime that notarisation requires would stop the JVM's JIT and the Chromium and JNA
                // libraries that are unpacked at run time; these entitlements allow exactly that.
                entitlementsFile.set(project.file("packaging/macos/entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("packaging/macos/entitlements.plist"))
                // Signing switches on with a Developer ID certificate in the keychain: the identity is the certificate
                // name after "Developer ID Application: ", for example "Jane Doe (ABCDE12345)".
                val identity = providers.environmentVariable("ZENCAMPUS_MAC_SIGN_IDENTITY").getOrElse("").trim()
                if (identity.isNotEmpty()) signing {
                    sign.set(true)
                    this.identity.set(identity.removePrefix("Developer ID Application:").trim())
                    providers.environmentVariable("ZENCAMPUS_MAC_KEYCHAIN").orNull?.takeIf { it.isNotBlank() }?.let { keychain.set(it) }
                }
            }
            windows {
                iconFile.set(project.file("icons/zencampus.ico"))
                // Installs for the current user under %LOCALAPPDATA%: no administrator, and the app can run its own updates.
                perUserInstall = true
                dirChooser = false
                shortcut = true
                menuGroup = "ZenCampus"
                // Never change it: Windows recognises a newer MSI as an upgrade of the installed app by this code.
                upgradeUuid = "be34368a-ef5a-47eb-99ce-3684d9823103"
            }
            linux {
                iconFile.set(project.file("icons/zencampus.png"))
                shortcut = true
                appCategory = "Education"
                menuGroup = "Education"
            }
        }
    }
}

tasks.test {
    systemProperty("java.awt.headless", "true")
    exclude("**/DesktopUiTest*")
    exclude("**/DesktopBrowserTest*")
}
tasks.register<Test>("desktopUiTest") {
    description = "Runs the native desktop UI checks on an available X11/XWayland display."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/DesktopUiTest*")
    include("**/DesktopBrowserTest*")
    systemProperty("java.awt.headless", "false")
    if (isMac) jvmArgs(macJcefJvmArgs)
}

// Compose's native launcher can crash in setenv before starting the JVM on this Linux host.
// Keep the generated configuration/runtime, using the matching JDK's standard java launcher.
tasks.configureEach {
    if (name == "createDistributable") {
        val linuxBundle = layout.buildDirectory.dir("compose/binaries/main/app/zencampus")
        val portableLauncher = project.file("packaging/zencampus")
        inputs.file(portableLauncher)
        outputs.upToDateWhen {
            !System.getProperty("os.name").startsWith("Linux") ||
                linuxBundle.get().file("lib/runtime/bin/java").asFile.isFile
        }
        doLast {
            if (System.getProperty("os.name").startsWith("Linux")) {
                val bundle = linuxBundle.get().asFile
                val javaLauncher = File(bundle, "lib/runtime/bin/java")
                javaLauncher.parentFile.mkdirs()
                File(System.getProperty("java.home"), "bin/java").copyTo(javaLauncher, overwrite = true)
                javaLauncher.setExecutable(true, false)
                val target = File(bundle, "bin/zencampus")
                portableLauncher.copyTo(target, overwrite = true)
                target.setExecutable(true, false)
            }
        }
    }
}
