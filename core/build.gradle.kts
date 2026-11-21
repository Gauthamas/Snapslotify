import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM: pipeline logic and data types, no Android imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // Harness only: the Paddle models on the laptop (PaddleLaptopRun, PADDLE_LAPTOP=1). Never reaches an APK.
    testImplementation(libs.onnxruntime.jvm)
}

// Code probe for harnass/code_probe.ipynb (harness only, not in any app build): a runnable jar of the core and test
// classes. Its manifest carries the SHA-256 of the sources it was built from, so the notebook can refuse a stale jar.
val probeSources = fileTree("src/main") + fileTree("src/test/kotlin/in/gauthama/snapslotify/core/probe") + files("build.gradle.kts")

fun probeSourceHash(): String {
    val md = MessageDigest.getInstance("SHA-256")
    probeSources.files.map { it.relativeTo(projectDir).invariantSeparatorsPath to it }.sortedBy { it.first }.forEach { (p, f) ->
        md.update(p.toByteArray()); md.update(0); md.update(f.readBytes()); md.update(0)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

tasks.register<Jar>("probeJar") {
    archiveFileName.set("code-probe.jar")
    destinationDirectory.set(layout.buildDirectory.dir("probe"))
    inputs.files(probeSources)
    from(sourceSets.main.get().output, sourceSets.test.get().output)
    dependsOn(configurations.testRuntimeClasspath)
    from({ configurations.testRuntimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        manifest.attributes(mapOf("Main-Class" to "in.gauthama.snapslotify.core.probe.CodeProbeKt", "Probe-Source-Hash" to probeSourceHash()))
    }
}

tasks.test {
    // The seen-case report and the parity checks print their tables.
    testLogging { showStandardStreams = true }
    // FINAL=1 runs the sealed set once (SealedFinalRun); the env value is an input so the task is not skipped as up to date
    inputs.property("FINAL", System.getenv("FINAL") ?: "")
    inputs.property("PADDLE_LAPTOP", System.getenv("PADDLE_LAPTOP") ?: "")
    inputs.property("VL_TRIAL", (System.getenv("PADDLE_IMAGES") ?: "") + (System.getenv("VL_RESOLVE_IN") ?: "") + (System.getenv("VL_PIPELINE_IN") ?: ""))
}
