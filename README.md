# MicahPod (mpod)

Podcast player for web + Android.

## Layout

- `web/` — PHP + vanilla JS site (`micahyoung.us/micahpod`)
- `android/` — Android app (`com.circpros.micahpod`)
- `docs/` — architecture / deployment notes

## Web

Serve `web/` from your host. Keep secrets in a parent `.env` (see `.env.example`).  
API lives under `web/api/`.

## Android

```bat
cd android
gradlew.bat assembleDebug
```

Set `sdk.dir` in `android/local.properties` (not committed).  
Optional: `org.gradle.java.home` in `android/gradle.properties` for a local JDK 17.

## Remote

Production: https://micahyoung.us/micahpod/
