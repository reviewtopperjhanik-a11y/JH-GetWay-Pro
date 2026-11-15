# Open Source Dependency Licenses — JH GetWay

The active dependencies compiled into **JH GetWay** (verified against `gradle/libs.versions.toml` and `app/build.gradle.kts`) are listed below with their corresponding open-source licenses:

| Library | Group / Artifact | Version | License |
| :--- | :--- | :--- | :--- |
| AndroidX Core KTX | `androidx.core:core-ktx` | `1.18.0` | Apache License 2.0 |
| AndroidX Activity Compose | `androidx.activity:activity-compose` | `1.10.1` | Apache License 2.0 |
| AndroidX Lifecycle (Runtime / ViewModel Compose) | `androidx.lifecycle:lifecycle-*` | `2.8.7` | Apache License 2.0 |
| Jetpack Compose BOM (UI, Graphics, Material3, Icons) | `androidx.compose:compose-bom` | `2024.09.00` | Apache License 2.0 |
| Navigation Compose | `androidx.navigation:navigation-compose` | `2.8.9` | Apache License 2.0 |
| AndroidX Room (Runtime & KTX) | `androidx.room:room-*` | `2.7.0` | Apache License 2.0 |
| AndroidX DataStore Preferences | `androidx.datastore:datastore-preferences` | `1.1.7` | Apache License 2.0 |
| Kotlin Standard Library & Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | `1.10.2` (Kotlin `2.2.10`) | Apache License 2.0 |
| Ktor Server Core & CIO | `io.ktor:ktor-server-core`, `io.ktor:ktor-server-cio` | `3.2.3` | Apache License 2.0 |
| OkHttp & Logging Interceptor | `com.squareup.okhttp3:okhttp`, `logging-interceptor` | `4.10.0` | Apache License 2.0 |
| Moshi Kotlin | `com.squareup.moshi:moshi-kotlin` | `1.15.2` | Apache License 2.0 |

### Test-Only Dependencies (Not Packaged in Release APK)

| Library | Artifact | Version | License |
| :--- | :--- | :--- | :--- |
| JUnit 4 | `junit:junit` | `4.13.2` | Eclipse Public License 1.0 |
| AndroidX Test Core / JUnit / Runner / Espresso | `androidx.test.*` | `1.6.1` / `1.3.0` / `1.6.2` / `3.7.0` | Apache License 2.0 |
| Robolectric | `org.robolectric:robolectric` | `4.16.1` | MIT License |
| Kotlinx Coroutines Test | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | Apache License 2.0 |
| Jetpack Compose UI Test JUnit4 | `androidx.compose.ui:ui-test-junit4` | Compose BOM `2024.09.00` | Apache License 2.0 |
