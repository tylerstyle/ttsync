# Licenses and notices

## TT Calendar Sync

TT Calendar Sync is free software under the [Apache License 2.0](../LICENSE). See also the
[NOTICE](../NOTICE) file.

Two files in `app/src/main/kotlin/de/tergelius/ttsync/ui/theme/` (`Color.kt`, `Theme.kt`) come from the
Android Studio project template, Copyright The Android Open Source Project, also under the Apache
License 2.0. Their license headers are kept.

## Libraries in the app

The APK contains the following libraries. The list is the resolved `releaseRuntimeClasspath` of
version 0.1.0; the licenses come from each artifact's Maven POM and were checked against the
projects. R8 removes unused code from the release APK, so not all of it ends up in the app.

| Library | Maven artifacts | Version | License | Project |
|---|---|---|---|---|
| Kotlin standard library | `org.jetbrains.kotlin:kotlin-stdlib` | 2.3.20 | Apache-2.0 | <https://github.com/JetBrains/kotlin> |
| kotlinx.coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `-core-jvm`, `-android`, `-bom` | 1.10.2 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| kotlinx.serialization | `org.jetbrains.kotlinx:kotlinx-serialization-core`, `-core-jvm`, `-json`, `-json-jvm`, `-bom` | 1.11.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| kotlinx atomicfu | `org.jetbrains.kotlinx:atomicfu`, `atomicfu-jvm` | 0.28.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx-atomicfu> |
| JetBrains Java annotations | `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | <https://github.com/JetBrains/java-annotations> |
| OkHttp | `com.squareup.okhttp3:okhttp`, `okhttp-android` | 5.5.1 | Apache-2.0 | <https://github.com/square/okhttp> |
| Public Suffix List (bundled in OkHttp as `assets/PublicSuffixDatabase.list`) | – | – | MPL-2.0 | <https://publicsuffix.org/> |
| Okio | `com.squareup.okio:okio`, `okio-jvm` | 3.18.1 | Apache-2.0 | <https://github.com/square/okio> |
| Guava ListenableFuture | `com.google.guava:listenablefuture` | 1.0 | Apache-2.0 | <https://github.com/google/guava> |
| JSpecify | `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 | <https://jspecify.dev/> |
| Protocol Buffers (lite), repackaged by AndroidX DataStore | `androidx.datastore:datastore-preferences-external-protobuf` | 1.3.0 | BSD-3-Clause | <https://github.com/protocolbuffers/protobuf> |
| AndroidX (Jetpack) libraries | see the next table | | Apache-2.0 | <https://android.googlesource.com/platform/frameworks/support> |

AndroidX libraries, all under the Apache License 2.0
(<https://developer.android.com/jetpack/androidx>). Most come in a plain and an `-android` or
`-jvm` variant; both are listed once.

| Group | Artifacts | Version |
|---|---|---|
| `androidx.activity` | `activity`, `activity-ktx`, `activity-compose` | 1.13.0 |
| `androidx.annotation` | `annotation` | 1.10.0 |
| `androidx.annotation` | `annotation-experimental` | 1.4.1 |
| `androidx.arch.core` | `core-common`, `core-runtime` | 2.2.0 |
| `androidx.autofill` | `autofill` | 1.0.0 |
| `androidx.collection` | `collection`, `collection-ktx` | 1.6.0 |
| `androidx.compose` | `compose-bom` | 2026.03.01 |
| `androidx.compose.animation` | `animation`, `animation-core` | 1.10.6 |
| `androidx.compose.foundation` | `foundation`, `foundation-layout` | 1.10.6 |
| `androidx.compose.material` | `material-ripple` | 1.10.6 |
| `androidx.compose.material3` | `material3` | 1.4.0 |
| `androidx.compose.runtime` | `runtime`, `runtime-annotation`, `runtime-retain`, `runtime-saveable` | 1.10.6 |
| `androidx.compose.ui` | `ui`, `ui-geometry`, `ui-graphics`, `ui-text`, `ui-tooling-preview`, `ui-unit`, `ui-util` | 1.10.6 |
| `androidx.concurrent` | `concurrent-futures`, `concurrent-futures-ktx` | 1.1.0 |
| `androidx.core` | `core`, `core-ktx` | 1.18.0 |
| `androidx.core` | `core-viewtree` | 1.0.0 |
| `androidx.customview` | `customview-poolingcontainer` | 1.0.0 |
| `androidx.datastore` | `datastore`, `datastore-core`, `datastore-core-okio`, `datastore-preferences`, `datastore-preferences-core`, `datastore-preferences-proto` | 1.3.0 |
| `androidx.documentfile` | `documentfile` | 1.0.0 |
| `androidx.dynamicanimation` | `dynamicanimation` | 1.0.0 |
| `androidx.emoji2` | `emoji2` | 1.4.0 |
| `androidx.graphics` | `graphics-path` | 1.0.1 |
| `androidx.interpolator` | `interpolator` | 1.0.0 |
| `androidx.legacy` | `legacy-support-core-utils` | 1.0.0 |
| `androidx.lifecycle` | `lifecycle-common`, `-common-java8`, `-livedata`, `-livedata-core`, `-livedata-core-ktx`, `-process`, `-runtime`, `-runtime-compose`, `-runtime-ktx`, `-service`, `-viewmodel`, `-viewmodel-compose`, `-viewmodel-ktx`, `-viewmodel-navigation3`, `-viewmodel-savedstate` | 2.10.0 |
| `androidx.loader` | `loader` | 1.0.0 |
| `androidx.localbroadcastmanager` | `localbroadcastmanager` | 1.0.0 |
| `androidx.navigation3` | `navigation3-runtime`, `navigation3-ui` | 1.0.1 |
| `androidx.navigationevent` | `navigationevent`, `navigationevent-compose` | 1.0.2 |
| `androidx.print` | `print` | 1.0.0 |
| `androidx.profileinstaller` | `profileinstaller` | 1.4.0 |
| `androidx.room` | `room-common`, `room-runtime` (used inside WorkManager) | 2.7.0 |
| `androidx.savedstate` | `savedstate`, `savedstate-ktx`, `savedstate-compose` | 1.4.0 |
| `androidx.sqlite` | `sqlite`, `sqlite-framework` | 2.5.0 |
| `androidx.startup` | `startup-runtime` | 1.2.0 |
| `androidx.tracing` | `tracing`, `tracing-ktx` | 2.0.1 |
| `androidx.transition` | `transition` | 1.6.0 |
| `androidx.versionedparcelable` | `versionedparcelable` | 1.1.1 |
| `androidx.window` | `window`, `window-core` | 1.5.0 |
| `androidx.work` | `work-runtime` | 2.12.0 |

Debug builds add `androidx.compose.ui:ui-tooling`, `ui-tooling-data`, `ui-test-manifest` and
`androidx.compose.material:material` (1.10.6, Apache-2.0). They are not in release builds.

## Test-only dependencies

Used to build and run the tests; never part of the app.

| Library | Maven artifacts | Version | License | Project |
|---|---|---|---|---|
| JUnit 4 | `junit:junit` | 4.13.2 | EPL-1.0 | <https://junit.org/junit4/> |
| Hamcrest | `org.hamcrest:hamcrest-core`, `hamcrest-library` | 1.3 | BSD-3-Clause | <https://hamcrest.org/JavaHamcrest/> |
| kotlinx-coroutines-test | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| MockWebServer 3 | `com.squareup.okhttp3:mockwebserver3` | 5.5.1 | Apache-2.0 | <https://github.com/square/okhttp> |
| SQLite JDBC (bundles SQLite, public domain) | `org.xerial:sqlite-jdbc` | 3.53.4.0 | Apache-2.0 | <https://github.com/xerial/sqlite-jdbc> |
| AndroidX Test (declared for instrumented tests; none exist yet) | `androidx.test:core` 1.7.0, `runner` 1.7.0, `monitor` 1.8.0, `androidx.test.ext:junit` 1.3.0, `androidx.test.services:storage` 1.6.0, `androidx.test.espresso:espresso-core`, `espresso-idling-resource` 3.7.0, `androidx.compose.ui:ui-test`, `ui-test-junit4` 1.10.6 | | Apache-2.0 | <https://github.com/android/android-test> |
| Their transitive annotations | `com.google.code.findbugs:jsr305` 3.0.2, `com.google.errorprone:error_prone_annotations` 2.30.0, `javax.inject:javax.inject` 1 | | Apache-2.0 | – |

## Build tools

Not shipped in the APK: Gradle 9.9.1 and its wrapper (`gradle/wrapper/gradle-wrapper.jar` is in
this repository), the Android Gradle Plugin 9.5.1, the Kotlin Compose compiler and serialization
plugins 2.3.20 and the foojay toolchain resolver 1.0.0, all under the Apache License 2.0.
`tools/tt-probe.py` uses only the Python standard library.

## Notices required by non-Apache licenses

### Protocol Buffers (BSD-3-Clause)

Contained in `androidx.datastore:datastore-preferences-external-protobuf`, which ships this license
text; the copyright line is Protocol Buffers' own.

```text
Copyright 2008 Google Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Public Suffix List (MPL-2.0)

OkHttp ships a copy of the Public Suffix List, which the app contains unchanged as
`assets/PublicSuffixDatabase.list`. This Source Code Form is subject to the terms of the Mozilla
Public License, v. 2.0. If a copy of the MPL was not distributed with this file, you can obtain one
at <https://mozilla.org/MPL/2.0/>. The list itself is published at <https://publicsuffix.org/list/>.

## Updating this page

After changing dependencies, regenerate the list and check the license of every new artifact:

```sh
./gradlew --no-daemon :app:dependencies --configuration releaseRuntimeClasspath
./gradlew --no-daemon :app:dependencies --configuration debugUnitTestRuntimeClasspath
```

New dependencies must be compatible with the Apache License 2.0.

## Trademarks and the TimeTree API

TT Calendar Sync is an independent, unofficial project. It is not affiliated with, endorsed or
sponsored by TimeTree, Inc. TimeTree is a trademark of its owner. Google, Google Calendar and
Android are trademarks of Google LLC. Other names may be trademarks of their respective owners.
They are used here only to describe what the app works with.

The app talks to TimeTree's **unofficial** web API, the one TimeTree's own website uses. TimeTree
has not approved this use, may change or block the API at any time, and its terms of service apply
to your account. No TimeTree code, logo or other material is included in this repository.
