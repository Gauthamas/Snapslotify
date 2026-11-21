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
