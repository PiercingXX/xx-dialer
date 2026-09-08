plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.piercingxx.xxdialer.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piercingxx.xxdialer.probe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "probe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// R8/D8 guard — "no network use outside opt-in VVM" (todo.md D1).
// verifyVvmInternetOnly runs automatically after :probe:assembleDebug
// (finalizedBy), dumps the debug APK's declared permissions via aapt2, and
// fails the build if any network permission OTHER than the allowed set
// (INTERNET + ACCESS_NETWORK_STATE, which back the opt-in VVM IMAP client)
// appears. The detection logic is mirrored verbatim by
// app/src/test/java/com/piercingxx/xxdialer/util/VvmInternetGuardTest.kt —
// keep the two in sync.
// ---------------------------------------------------------------------------

// SDK location: local.properties sdk.dir, else ANDROID_HOME/ANDROID_SDK_ROOT,
// else ~/Android/Sdk.
val xxSdkDir: File = run {
    val declared = rootProject.file("local.properties").takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.trim().startsWith("sdk.dir=") }
        ?.substringAfter("sdk.dir=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    sequenceOf(
        declared,
        System.getenv("ANDROID_HOME")?.trim()?.takeIf { it.isNotEmpty() },
        System.getenv("ANDROID_SDK_ROOT")?.trim()?.takeIf { it.isNotEmpty() },
    ).filterNotNull().firstOrNull()?.let(::File)
        ?: File(System.getProperty("user.home"), "Android/Sdk")
}

// build-tools preference: 35.0.0 (compileSdk match) then 37.0.0.
val xxAapt2: File? = listOf("35.0.0", "37.0.0")
    .map { File(xxSdkDir, "build-tools/$it/aapt2") }
    .firstOrNull { it.isFile }

// The only network permissions the dialer may declare (todo.md D1): INTERNET +
// ACCESS_NETWORK_STATE back the opt-in VVM IMAP client. Any other network
// permission (WIFI/BLUETOOTH/NFC/CHANGE_NETWORK_STATE/...) is a signal of
// network use outside VVM and fails the build.
val xxForbiddenNetworkPermissions = listOf(
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.ACCESS_WIFI_MULTICAST_STATE",
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.NFC",
    "android.permission.USE_WIFI_P2P",
)

fun networkViolations(dump: String): List<String> =
    dump.lineSequence()
        .map { it.trim() }
        .filter { line ->
            xxForbiddenNetworkPermissions.any { line.contains(it, ignoreCase = true) }
        }
        .toList()

val verifyVvmInternetOnly = tasks.register("verifyVvmInternetOnly") {
    group = "verification"
    description =
        "Fails the build if the debug APK declares a network permission outside " +
            "the opt-in VVM set (INTERNET + ACCESS_NETWORK_STATE). (todo.md D1)"
    // Captured Provider — safe under the configuration cache; resolved lazily.
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")

    doLast {
        val aapt2 = requireNotNull(xxAapt2) {
            "[verifyVvmInternetOnly] aapt2 not found under $xxSdkDir/build-tools (tried 35.0.0, 37.0.0)"
        }
        val apks = apkDir.get().asFileTree.matching { include("*-debug.apk") }
            .files.sortedBy { it.name }
        if (apks.isEmpty()) {
            throw GradleException(
                "[verifyVvmInternetOnly] no *-debug.apk found in ${apkDir.get().asFile}",
            )
        }
        apks.forEach { apk ->
            val process = ProcessBuilder(
                aapt2.absolutePath, "dump", "permissions", apk.absolutePath,
            ).redirectErrorStream(true).start()
            val dump = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                throw GradleException(
                    "[verifyVvmInternetOnly] aapt2 exited $exit for ${apk.name}:\n$dump",
                )
            }
            if (networkViolations(dump).isNotEmpty()) {
                throw GradleException(
                    "[verifyVvmInternetOnly] FAILED: ${apk.name} declares a network " +
                        "permission outside the opt-in VVM set (INTERNET + " +
                        "ACCESS_NETWORK_STATE) — forbidden by todo.md D1.",
                )
            }
            println(
                "[verifyVvmInternetOnly] OK (${project.path}): ${apk.name} declares " +
                    "no network permission outside the opt-in VVM set. Full permission dump:",
            )
            dump.trimEnd().lines().forEach { println("[verifyVvmInternetOnly]   $it") }
        }
    }
}

// finalizedBy (not dependsOn): verification always runs after the APK exists,
// and the wiring holds under the configuration cache.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(verifyVvmInternetOnly)
}

// `./gradlew installDebug` at the repo root would otherwise install this
// next to the real dialer and put "XX-Probe" on the launcher. Probe is
// test-only and opt-in: :probe:installProbe.
tasks.configureEach {
    if (name == "installDebug" || name == "installRelease") {
        enabled = false
        group = null
        description = "Disabled — use :probe:installProbe (test-only, no launcher)."
    }
}

tasks.register("installProbe") {
    group = "install"
    description = "adb install -t the test-only probe APK (no launcher icon)."
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/probe-debug.apk").get().asFile
        if (!apk.isFile) {
            throw GradleException(":probe:installProbe — missing $apk")
        }
        val adb = sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).filterNotNull().map { File(it, "platform-tools/adb") }.firstOrNull { it.isFile }
            ?: File(System.getProperty("user.home"), "Android/Sdk/platform-tools/adb")
        exec {
            commandLine(adb.absolutePath, "install", "-t", "-r", apk.absolutePath)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
