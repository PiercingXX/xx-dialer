plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.piercingxx.xxphone.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piercingxx.xxphone.probe"
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
// R8/D8 guard — "no INTERNET permission, ever" (applies to the throwaway too).
// verifyNoInternet runs automatically after :probe:assembleDebug (finalizedBy),
// dumps the debug APK's declared permissions via aapt2, and fails the build if
// android.permission.INTERNET appears (case-insensitive). The detection logic
// is mirrored verbatim by
// app/src/test/java/com/piercingxx/xxphone/util/NoInternetGuardTest.kt —
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

fun internetViolations(dump: String): List<String> =
    dump.lineSequence()
        .map { it.trim() }
        .filter { it.contains("android.permission.INTERNET", ignoreCase = true) }
        .toList()

val verifyNoInternet = tasks.register("verifyNoInternet") {
    group = "verification"
    description =
        "Fails the build if the debug APK declares android.permission.INTERNET (design R8/D8)."
    // Captured Provider — safe under the configuration cache; resolved lazily.
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")

    doLast {
        val aapt2 = requireNotNull(xxAapt2) {
            "[verifyNoInternet] aapt2 not found under $xxSdkDir/build-tools (tried 35.0.0, 37.0.0)"
        }
        val apks = apkDir.get().asFileTree.matching { include("*-debug.apk") }
            .files.sortedBy { it.name }
        if (apks.isEmpty()) {
            throw GradleException(
                "[verifyNoInternet] no *-debug.apk found in ${apkDir.get().asFile}",
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
                    "[verifyNoInternet] aapt2 exited $exit for ${apk.name}:\n$dump",
                )
            }
            if (internetViolations(dump).isNotEmpty()) {
                throw GradleException(
                    "[verifyNoInternet] FAILED: ${apk.name} declares " +
                        "android.permission.INTERNET — forbidden by design.md R8/D8.",
                )
            }
            println(
                "[verifyNoInternet] OK (${project.path}): ${apk.name} declares " +
                    "no INTERNET permission. Full permission dump:",
            )
            dump.trimEnd().lines().forEach { println("[verifyNoInternet]   $it") }
        }
    }
}

// finalizedBy (not dependsOn): verification always runs after the APK exists,
// and the wiring holds under the configuration cache.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(verifyNoInternet)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
