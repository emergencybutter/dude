plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain JVM module with no Android dependency. The alternate side rule engine is the
// part of this app most likely to be wrong and most expensive to be wrong about, so it has to be
// testable in milliseconds without an emulator. `java.time` is available on the app's minSdk (26),
// so the same code runs unchanged on device.

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
