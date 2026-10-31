# Third-party notices

Libraries bundled in the app:

| Component | Version | Licence | Source |
|---|---|---|---|
| AndroidX WebKit | 1.12.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/webkit |
| Eclipse Paho MQTT Java client (mqttv3) | 1.2.5 | EPL-2.0 or EDL-1.0 (BSD-3-Clause), at the user's choice | https://github.com/eclipse/paho.mqtt.java |
| ZXing core | 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing |
| Kotlin standard library | 2.0.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |

Only in the voice build (`openpane-voice-*.apk`), for the wake word:

| Component | Version | Licence | Source |
|---|---|---|---|
| TensorFlow Lite | 2.16.1 | Apache-2.0 | https://github.com/tensorflow/tensorflow |
| microWakeWord models: Okay Nabu, Hey Jarvis, Hey Mycroft (by Kevin Ahrendt) | v2, commit 40ff33f57f8f | Apache-2.0 | https://github.com/esphome/micro-wake-word-models |

Test-only (not shipped): JUnit 4.13.2 (EPL-1.0), JSON-java 20240303 (Public Domain).

Code adapted from other projects (each file keeps its notice):

- `app/src/voice/.../wake/MicroFrontend.kt` is a Kotlin port of TensorFlow Lite Micro's audio frontend (`tensorflow/lite/experimental/microfrontend`, Copyright 2018 The TensorFlow Authors, Apache-2.0) and of Kiss FFT's fixed-point real FFT (Copyright (c) 2003-2010, Mark Borgerding, BSD-3-Clause):

  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met: Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution. Neither the author nor the names of any contributors may be used to endorse or promote products derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- The unit test's reference features (`app/src/testVoice/resources/wake/`) were made with pymicro-features 2.0.2 (Apache-2.0).

When code is adapted from another project, list it here with its licence and keep its copyright notice in the file.

Interface compatibility: the `/notify` endpoint accepts the same JSON fields as TvOverlay's public REST API, so existing automations keep working. No TvOverlay code is used. Home Assistant's external auth and external bus protocols are implemented from Home Assistant's public developer documentation.
