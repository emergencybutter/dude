plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM for the same reason as :asp-core — the fusion rules for "is the car parked" are full of
// cases you would otherwise only hit by driving around, so they are kept free of Android types and
// tested directly.

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
