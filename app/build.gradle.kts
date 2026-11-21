import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The Play upload key, read from keystore.properties in the project root (gitignored, never committed):
//   storeFile=C:/path/to/upload.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Without that file the release build is left unsigned, as before.
val uploadKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "in.gauthama.snapslotify"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "in.gauthama.snapslotify"
        minSdk = 26  // provisional, see docs/DECISIONS.md
        targetSdk = 36
        versionCode = 18
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!uploadKey.getProperty("storePassword").isNullOrBlank()) create("upload") {
            storeFile = file(uploadKey.getProperty("storeFile"))
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The debug build carries ONNX Runtime and llama.cpp for arm64-v8a only: the phone, and nothing else.
        debug {
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // the on-device models (chat + try-harder vision): an install-time asset pack in the AAB, copied out by assist/ModelFiles
    assetPacks += listOf(":models")
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    // A second guard, should fixtures ever be pointed at an asset folder again.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!sealed:!snapslotify_bills_flyers_100:!photos:!split.json"
    }
    sourceSets {
        // Instrumented tests read DEV images (never the sealed set) from the test APK's own assets. The folder is gitignored:
        // these images live on this machine, so ReviewFlowTest cannot run from a fresh clone without them.
        getByName("androidTest") {
            assets.srcDir("$rootDir/fixtures/synthetic100/dev")
            // ML Kit's saved output for one DEV image, from the phone's own OCR run (gitignored, made by a harness script)
            assets.srcDir("$rootDir/fixtures/saved_ocr")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // local totals only (taps saved, model timeouts); no network
    implementation(libs.androidx.datastore.preferences)
    implementation(project(":core"))
    // Bundled Latin model inside the APK (not the Play-services download variant): no network needed.
    implementation(libs.mlkit.text.recognition)
    // the app's own camera (a short burst, the sharpest frame kept)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Build 2 step 0 timing spike: llama.cpp via JNI, debug builds only (never in release)
    // The on-device models' runtime (llama.cpp via JNI, arm64-v8a): debug and release; model files live in the app's storage
    implementation(project(":llmspike"))
    // OCR engine switch: PaddleOCR (PP-OCRv5 mobile, ONNX) on ONNX Runtime, debug builds only; release stays on ML Kit
    debugImplementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.test.rules)
}

// Fails the build if a private fixture or photo reaches an APK or the git index. Reads names only, and reports the rule and
// a count, never a file name, because the names of sealed files are private too. The test APK alone may carry DEV images,
// because the instrumented tests read them; it is never shipped. Release must also carry no model runtime.
val neverInAnyApk = mapOf(
    "sealed images" to Regex("""(^|/)sealed/"""),
    "the original 100-image folder, which holds sealed images" to Regex("""(^|/)snapslotify_bills_flyers_100/"""),
    "private photos" to Regex("""(^|/)photos/"""),
    "the split file, which lists sealed ids" to Regex("""(^|/)split\.json$"""),
    "sealed answer keys" to Regex("""sealed_expected"""),
)
val neverInAppApk = neverInAnyApk + mapOf(
    "fixtures" to Regex("""^assets/(screenshots|synthetic100|fixtures_heldout|expected\.json)"""),
    "photos, in any folder" to Regex("""^assets/.*\.(jpe?g|heic|heif)$""", RegexOption.IGNORE_CASE),
    "model files" to Regex("""\.gguf$"""),
)
val neverInRelease = neverInAppApk + mapOf(
    "the OCR model runtime (debug only)" to Regex("""^lib/[^/]+/libonnxruntime[^/]*\.so$"""),
    "OCR model files (debug only)" to Regex("""\.onnx$|^assets/paddle/"""),
)
val neverInGit = mapOf(
    "fixtures" to Regex("""^fixtures/"""),
    "the raw sample pool" to Regex("""^datasets_raw/"""),
    "sealed images or answers" to Regex("""(^|/)sealed/|sealed_expected|sealed_ocr"""),
    "photos, in any folder" to Regex("""\.(jpe?g|heic|heif)$""", RegexOption.IGNORE_CASE),
    "model files" to Regex("""\.gguf$|\.onnx$"""),
)
val checkRepoFixtures = tasks.register("checkRepoFixtures") {
    val root = rootDir
    doLast {
        val p = ProcessBuilder("git", "ls-files").directory(root).redirectErrorStream(true).start()
        val names = p.inputStream.bufferedReader().readLines()
        check(p.waitFor() == 0) { "git ls-files failed, so the repo could not be checked" }
        val found = neverInGit.mapValues { (_, r) -> names.count(r::containsMatchIn) }.filterValues { it > 0 }
        if (found.isNotEmpty()) throw GradleException("the git index contains " + found.entries.joinToString { "${it.key} (${it.value} files)" })
        logger.lifecycle("repo check: git index ok, ${names.size} files")
    }
}

fun fixtureCheck(name: String, apkDir: String, rules: Map<String, Regex>) = tasks.register(name) {
    val dir = layout.buildDirectory.dir(apkDir)
    doLast {
        val apks = dir.get().asFile.walkTopDown().filter { it.extension == "apk" }.toList()
        check(apks.isNotEmpty()) { "no APK to check in $apkDir" }
        for (apk in apks) {
            val names = ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.toList() }
            val found = rules.mapValues { (_, r) -> names.count(r::containsMatchIn) }.filterValues { it > 0 }
            if (found.isNotEmpty()) throw GradleException("${apk.name} contains " + found.entries.joinToString { "${it.key} (${it.value} files)" })
            logger.lifecycle("fixtures check: ${apk.name} ok, ${names.size} entries")
        }
    }
}

val checkDebugApkFixtures = fixtureCheck("checkDebugApkFixtures", "outputs/apk/debug", neverInAppApk)
val checkReleaseApkFixtures = fixtureCheck("checkReleaseApkFixtures", "outputs/apk/release", neverInRelease)
val checkTestApkFixtures = fixtureCheck("checkTestApkFixtures", "outputs/apk/androidTest/debug", neverInAnyApk)

// Share target guard: the merged manifest's ReviewActivity must stay exported and accept SEND and SEND_MULTIPLE for
// any image type with the DEFAULT category, or the app drops out of the share sheet. Runs after each manifest merge.
// (Line comments on purpose: the mime pattern written inside a block comment would open a nested comment.)
fun shareFilterCheck(variant: String) = tasks.register("check${variant}ShareFilters") {
    val manifest = layout.buildDirectory.file("intermediates/merged_manifest/${variant.lowercase()}/process${variant}MainManifest/AndroidManifest.xml")
    doLast {
        val file = manifest.get().asFile
        check(file.exists()) { "no merged manifest at $file" }
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        fun elements(parent: org.w3c.dom.Element, tag: String) =
            parent.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
        val review = elements(doc.documentElement, "activity").firstOrNull { it.getAttributeNS(ns, "name").endsWith(".review.ReviewActivity") }
            ?: throw GradleException("share filters: ReviewActivity missing from the $variant manifest")
        if (review.getAttributeNS(ns, "exported") != "true") throw GradleException("share filters: ReviewActivity is not exported in $variant")
        fun values(e: org.w3c.dom.Element, tag: String, attr: String) = elements(e, tag).map { it.getAttributeNS(ns, attr) }
        for (action in listOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE")) {
            val ok = elements(review, "intent-filter").any { f ->
                action in values(f, "action", "name") && "android.intent.category.DEFAULT" in values(f, "category", "name") &&
                    "image/*" in values(f, "data", "mimeType")
            }
            if (!ok) throw GradleException("share filters: $variant ReviewActivity lost $action + DEFAULT + image/*")
        }
        logger.lifecycle("share filters check: $variant ok")
    }
}
val checkDebugShareFilters = shareFilterCheck("Debug")
val checkReleaseShareFilters = shareFilterCheck("Release")

afterEvaluate {
    tasks.named("processDebugMainManifest") { finalizedBy(checkDebugShareFilters) }
    tasks.named("processReleaseMainManifest") { finalizedBy(checkReleaseShareFilters) }
    tasks.named("packageDebug") { finalizedBy(checkDebugApkFixtures, checkRepoFixtures) }
    tasks.named("packageRelease") { finalizedBy(checkReleaseApkFixtures, checkRepoFixtures) }
    tasks.named("packageDebugAndroidTest") { finalizedBy(checkTestApkFixtures) }
}