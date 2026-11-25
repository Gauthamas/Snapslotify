// The on-device models as a Play asset pack, install-time: they come with the install as a split APK (no Play library,
// no extra permissions), and assist/ModelFiles copies each file once into the app's storage, because llama.cpp opens a
// model by path. The files sit in src/main/assets (gitignored, copied in
// locally): vl/LFM2.5-VL-450M-mix-Q4_0.gguf (posters and the chat jobs), vl/mmproj-LFM2.5-VL-450m-Q8_0.gguf,
// plus the LFM licence and a notice of the fine-tuning, which the licence asks to travel with the files.
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

// Without the model files the bundle still builds, and the app runs on code only. Say so when a bundle is built, so an
// empty asset pack is never a surprise. Where to get the files: README, "Where the models are hosted".
// The folder is gitignored, so a fresh clone lacks it; the asset pack step fails on a missing folder, not an empty one.
file("src/main/assets").mkdirs()
val modelFiles = listOf("vl/LFM2.5-VL-450M-mix-Q4_0.gguf", "vl/mmproj-LFM2.5-VL-450m-Q8_0.gguf")
gradle.taskGraph.whenReady {
    if (allTasks.none { it.name.startsWith("bundle") }) return@whenReady
    val missing = modelFiles.filterNot { file("src/main/assets/$it").isFile }
    if (missing.isNotEmpty()) logger.warn(
        "Snapslotify: model files missing from models/src/main/assets: ${missing.joinToString()}. The bundle builds " +
            "without them and the app runs on code only (no model chips). See README.md, \"Where the models are hosted\"."
    )
}
