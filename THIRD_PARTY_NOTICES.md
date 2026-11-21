# Third-party notices

Snapslotify photo to calendar is licensed under the Apache License 2.0 (`LICENSE`). It uses the third-party code and
models below, each under its own licence. Libraries pulled from Maven are not copied into this repository; the
model files and the llama.cpp source are not in it either (see `README.md`).

## Models

### LFM2.5-VL-450M (Liquid AI): the on-device model

- **Model.** LiquidAI/LFM2.5-VL-450M (https://huggingface.co/LiquidAI/LFM2.5-VL-450M), Copyright Liquid AI, Inc.,
  under the **LFM Open License v1.0**. The full text is in `licenses/LFM-Open-License-v1.0.txt`, copied unchanged from
  the model repository.
- **Files used.** The GGUF files from LiquidAI/LFM2.5-VL-450M-GGUF: the Q4_0 language model and the Q8_0 image
  encoder (`mmproj`).
- **A modified copy.** The Play release uses a derivative, `LFM2.5-VL-450M-mix-Q4_0.gguf`, which is modified from the
  original:
  - a LoRA, rank 16, was trained on synthetic event posters and synthetic chat and page text (calendar titles and
    date phrases), and only on the language layers;
  - the LoRA was merged into the base weights;
  - the result was converted to GGUF with llama.cpp and quantised to Q4_0.

  The image encoder is unchanged. The modified weights are not published in this repository.
- **How it ships.** The Play release carries the model files in an install-time asset pack, together with the licence
  text and a notice of the changes (section 4 of the licence). Debug builds use files copied onto a phone by hand.
- **Commercial use.** Under section 5 of the licence, commercial use is licensed only for entities below US$10 million
  in annual revenue, or for qualifying non-profits.
- **Trademarks.** The licence grants no right to Liquid AI's names or marks beyond describing the model's origin.

### PP-OCRv5 mobile (PaddleOCR): debug builds only

The PP-OCRv5 mobile text detection and recognition models (PaddlePaddle, https://github.com/PaddlePaddle/PaddleOCR)
are under the Apache License 2.0. They are optional, ship only in debug builds, and are not in this repository; when
added, their licence text goes beside them as `app/src/debug/assets/paddle/LICENSE-PaddleOCR.txt`.

### ML Kit text recognition (Google): the bundled Latin model

The release OCR is Google ML Kit text recognition (`com.google.mlkit:text-recognition`), whose Latin model is bundled
in the library. It is used under the ML Kit Terms of Service (https://developers.google.com/ml-kit/terms) and the
Google APIs Terms of Service. It is not open source and is not part of this repository.

## Native code

### llama.cpp and ggml

llama.cpp (https://github.com/ggml-org/llama.cpp), MIT License, Copyright (c) 2023-2026 The ggml authors. The app
builds the `llama`, `ggml` and `mtmd` libraries from the commit pinned in `llmspike/LLAMA_COMMIT` and links them
statically into its JNI library. llama.cpp carries third-party code under its own licences (for example stb_image,
miniaudio and nlohmann/json); see the `LICENSE` file and the `vendor/` and `licenses/` folders of that checkout.

The MIT License text:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
> rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
> persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
> Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
> WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Libraries (from Maven, in the APK)

| Library | Licence |
|---|---|
| AndroidX: core, appcompat, activity, fragment, lifecycle, savedstate, datastore, CameraX and their AndroidX dependencies | Apache License 2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache License 2.0 |
| Guava, AutoValue annotations, javax.inject, Okio, JSpecify (dependencies of the above) | Apache License 2.0 |
| ML Kit text recognition and its Google Play services, Firebase and datatransport dependencies | ML Kit Terms of Service / Google APIs Terms of Service (proprietary); some parts Apache License 2.0 |
| ONNX Runtime for Android (`com.microsoft.onnxruntime`), debug builds only | MIT License |

## Build and test tools (not in the APK)

| Tool | Licence |
|---|---|
| Gradle and the Gradle wrapper | Apache License 2.0 |
| Android Gradle Plugin, Kotlin Gradle plugins | Apache License 2.0 |
| JUnit 4 | Eclipse Public License 1.0 |
| AndroidX Test, Espresso | Apache License 2.0 |
| PyYAML (used by `tools/fetch_paddle_models.py`) | MIT License |
