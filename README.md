# Snapslotify photo to calendar

An Android app that turns a shared screenshot or photo (a chat, poster, bill, ticket, email or month calendar) into
calendar events, on the phone, with no network.

- **No network.** The app has no INTERNET permission in any build. Text is read with on-device OCR (Google ML Kit,
  bundled Latin model), and events are found by plain Kotlin code.
- **Optional on-device model.** A small vision-language model (LiquidAI LFM2.5-VL-450M, run with llama.cpp) can offer
  extra suggestions as chips. It never fills a field on its own. Without the model files, the app runs on code only.
- **The user stays in charge.** Every event opens in a review screen first. A value the code is unsure of is flagged,
  and "Add to calendar" hands the event to the phone's calendar app.

## Layout

| Module | What it holds |
|---|---|
| `core` | Plain Kotlin/JVM, no Android imports: OCR clean-up, date and time parsing, page routing (chat, poster, bill, programme, calendar grid), the review session. Most of the logic and the unit tests live here. |
| `app` | The Android app: XML views with ViewBinding, the photo picker and share target, the camera, ML Kit OCR, the model runners. |
| `llmspike` | A JNI wrapper around llama.cpp (arm64-v8a only). |
| `models` | A Play asset pack (install-time) that carries the model files in a release bundle. |

## Requirements

- Android Studio, or the command line with JDK 17.
- Android SDK platform 36, NDK `27.0.12077973` and CMake `3.31.6` (install them from the SDK Manager).
- Git, to fetch the llama.cpp source.

From the command line, point Gradle at the SDK with `ANDROID_HOME`, or with a `local.properties` file holding
`sdk.dir=<path to your Android SDK>` (Android Studio writes it for you).

## Build from a clean checkout

1. **Get llama.cpp.** The source is not in this repository. The app is built against the commit pinned in
   `llmspike/LLAMA_COMMIT`. This script fetches exactly that commit into `llmspike/third_party/llama.cpp`:

   ```sh
   sh tools/get_llama_cpp.sh
   ```

   To do it by hand instead:

   ```sh
   git clone https://github.com/ggml-org/llama.cpp llmspike/third_party/llama.cpp
   git -C llmspike/third_party/llama.cpp checkout 5d7793d6fcce4eb4b11ab5be856dfa4e4c8fde9b
   ```

2. **Build and test.**

   ```sh
   ./gradlew :core:test                 # the pipeline's unit tests
   ./gradlew :app:testDebugUnitTest     # the app's unit tests
   ./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
   ```

   On Windows, use `gradlew.bat`.

Some tests also read private evaluation data (real screenshots and their OCR) that is not published. They skip
themselves when it is absent, so a clean checkout passes without it.

A release build is unsigned unless you add your own signing key. `app/build.gradle.kts` reads it from a
`keystore.properties` file in the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). That file is
gitignored; never commit it.

## Models

No model files are in this repository. The app works without them: it reads the page with ML Kit and finds the events
in code, and the model chips simply do not appear.

### The vision-language model (LFM2.5-VL-450M)

Download two files from https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF:

- `LFM2.5-VL-450M-Q4_0.gguf`: the base language model.
- `mmproj-LFM2.5-VL-450m-Q8_0.gguf`: the image encoder.

The Play release uses a fine-tuned copy of the language model, `LFM2.5-VL-450M-mix-Q4_0.gguf` (see
`THIRD_PARTY_NOTICES.md`). Those tuned weights are not published, so builds from this repository use the base model.

**Debug build.** The app looks for the files in its own storage, under `files/vl/`. Install the debug APK, then copy
them in with adb:

```sh
adb push LFM2.5-VL-450M-Q4_0.gguf mmproj-LFM2.5-VL-450m-Q8_0.gguf /data/local/tmp/
adb shell run-as in.gauthama.snapslotify mkdir -p files/vl
adb shell run-as in.gauthama.snapslotify cp /data/local/tmp/LFM2.5-VL-450M-Q4_0.gguf files/vl/
adb shell run-as in.gauthama.snapslotify cp /data/local/tmp/mmproj-LFM2.5-VL-450m-Q8_0.gguf files/vl/
adb shell rm /data/local/tmp/LFM2.5-VL-450M-Q4_0.gguf /data/local/tmp/mmproj-LFM2.5-VL-450m-Q8_0.gguf
```

Then open the debug settings screen in the app and choose **Base LFM2.5-VL 450M**. The tuned model is the default
choice there, and it is absent.

The model needs a phone with at least 3 GB of RAM. On smaller phones the app stays on code only.

**Release bundle.** The `models` asset pack takes its files from `models/src/main/assets/` (gitignored):

```
models/src/main/assets/
├── vl/LFM2.5-VL-450M-mix-Q4_0.gguf
├── vl/mmproj-LFM2.5-VL-450m-Q8_0.gguf
├── LICENSE_LFM_Open_License_v1.0.txt   (a copy of licenses/LFM-Open-License-v1.0.txt)
└── NOTICE.txt                          (what was changed in the model, as the licence asks)
```

The release build reads only the tuned file name. Without the tuned weights, leave the folder empty and the release app
runs on code only. The LFM Open License asks for its text, and a notice of any changes, to travel with the model files.

### PaddleOCR (debug builds only, optional)

Debug builds can switch the OCR engine to PP-OCRv5 mobile on ONNX Runtime, to compare it with ML Kit. ML Kit is the
default, and the only engine in release builds. To try PaddleOCR:

1. Download `inference.onnx` and `inference.yml` from https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx into
   `det/`, and from https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx into `rec/`.
2. Add the PaddleOCR `LICENSE` file (Apache 2.0) to the same folder.
3. Run `python -I tools/fetch_paddle_models.py <that folder>`. It needs PyYAML.

The script copies the files into `app/src/debug/assets/paddle/` and checks each file against the hash it was tested
with. If the recogniser on Hugging Face has been updated since, its hash will not match and the script stops.

## Privacy

There is no network permission, no analytics and no account. Images, OCR text and events stay on the phone. "Report a
problem" opens the user's own email app with a draft they can read before sending.

## Licence

The app's own code is under the Apache License 2.0 (`LICENSE`). Third-party code and models keep their own licences,
listed in `THIRD_PARTY_NOTICES.md`. The LFM Open License v1.0 text is in `licenses/`.
