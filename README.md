# BaraFrame

A ClockDesk background plugin that fetches artwork from Danbooru and Safebooru. Supports tags, rating filters and automatic updates. Crop and position the background in ClockDesk. SFW by default.

Install the APK and select **BaraFrame** as the background provider in ClockDesk.

## Build

Requires JDK 17+ and Android SDK 36.

```sh
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Every push builds signed APKs for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. Pushes to `master` also publish a [release](https://github.com/sinkclose/BaraFrame/releases) with the commit, file sizes, SHA-256 and SHA-512 checksums. Builds can also be started manually from Actions.
