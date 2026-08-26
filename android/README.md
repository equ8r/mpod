# MicahPod (Android)

MicahPod is a podcast player with Android Auto support that connects to the MicahPod REST API.

## Requirements

- Android Studio or Android SDK command line tools
- JDK 17
- Android SDK Platform 34 and Build Tools 34.0.0

## Android SDK setup (CLI)

```
sdkmanager --install "platforms;android-34" "build-tools;34.0.0"
```

## Gradle wrapper

This repo intentionally does not include the Gradle wrapper JAR. Generate it locally:

```
gradle wrapper --gradle-version 8.5
```

## Build

```
./gradlew assembleDebug
```
