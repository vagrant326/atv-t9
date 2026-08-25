plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No Android dependencies here, ever. The simulator that measures KSPC and the IME that ships
// resolve candidates through the same code, otherwise a measured figure describes a keyboard
// nobody can install.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * KSPC over the query corpus, against the shipped dictionaries. Lives in the test source set so
 * the runner never reaches the APK.
 *
 * The figure this prints is the one that decides whether T9 is worth building at all. Published
 * T9 KSPC is 1.0072 and assumes every word is in the dictionary; the queries here are
 * deliberately not, because a TV search box is mostly proper nouns.
 */
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Measures KSPC over bench/queries-v1.tsv"
    mainClass.set("io.github.vagrant326.atvt9.core.bench.BenchmarkKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    args(
        "--queries", "bench/queries-v1.tsv",
        "--dictionary-pl", "app/src/main/assets/dictionary-pl.bin",
        "--dictionary-en", "app/src/main/assets/dictionary-en.bin",
    )
}
