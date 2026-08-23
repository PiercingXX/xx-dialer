// WS2 — the pure-JVM policy core (design §5/§6). Imports nothing from android.*;
// everything in this module must stay provable with plain JVM tests (todo rule #5).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.37")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
