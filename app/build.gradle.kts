plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.piercingxx.xxdialer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piercingxx.xxdialer"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // UI stack (Views + viewBinding, D7).
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle and coroutines.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Room (FactStore, design §11) — annotation processing via KSP.
    val room = "2.7.2"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // Gson backup/restore (D12) and libphonenumber parity with :core.
    implementation("com.google.code.gson:gson:2.13.2")

    // Pure policy core — the part that must be correct (design §5).
    implementation(project(":core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))

    // androidTest instrumented skeletons for the on-caiman §16 suite (design
    // §16; PROBE.md procedures). JUnit4 + androidx.test runner/rules/ext +
    // coroutines-test + Room's migration harness.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
}

// ---------------------------------------------------------------------------
// R8/D8 guard — "no INTERNET permission, ever".
// verifyNoInternet runs automatically after :app:assembleDebug (finalizedBy),
// dumps the debug APK's declared permissions via aapt2, and fails the build if
// android.permission.INTERNET appears (case-insensitive). The detection logic
// is mirrored verbatim by
// app/src/test/java/com/piercingxx/xxdialer/util/NoInternetGuardTest.kt —
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
