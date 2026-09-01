# Build & Deployment Guide

This document outlines the strict compilation, signing, and environment requirements necessary to maintain the zero-dependency standard of this project.

---

## 1. Prerequisites

- **Java:** JDK 17
- **Gradle:** Gradle 8.13 (Must run direct bin, NOT wrapper)
- **SDK:** Android SDK 36 Platform Tools
- **ADB:** USB Debugging Authorized
- **Maven:** Explicitly bypassed to enforce zero-dependency rules.

---

## 2. Build Commands

> [!IMPORTANT]
> Never use `./gradlew`. It risks fetching network dependencies.

**Debug APK:**
```bash
~/gradle/gradle-8.13/bin/gradle assembleDebug --no-daemon -q
```

**Release APK:**
```bash
~/gradle/gradle-8.13/bin/gradle assembleRelease --no-daemon -q
```

---

## 3. Install Commands

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
*Note: Use `-d` for downgrades if experimenting with older APK states.*

---

## 4. Signing Config

The app is repacked and signed utilizing `apksigner v1/v2/v3` formats. The configuration strictly relies on local keystores defined within `gradle.properties`.

```properties
# gradle.properties
RELEASE_STORE_FILE=my-release-key.keystore
RELEASE_STORE_PASSWORD=*****
RELEASE_KEY_ALIAS=my-key-alias
RELEASE_KEY_PASSWORD=*****
```

---

## 5. APK Output Paths

- **Debug:** `app/build/outputs/apk/debug/app-debug.apk`
- **Release:** `app/build/outputs/apk/release/app-release.apk`

---

## 6. Version History

| Version | Date | Artifact | Key Features |
|---------|------|----------|--------------|
| v0.1.0 | 2026-08 | `dist/app-v0.1.0.apk` | Initial LoadGenerator surrogate |
| v0.5.0 | 2026-08 | `dist/app-v0.5.0.apk` | First offline RL training loop |
| v1.0.0 | 2026-09 | `dist/app-v1.0.0.apk` | Heavier surrogate baseline, network actuators |
| **v1.1.0** | 2026-09 | `dist/app-v1.1.0.apk` | **Cross-profile model persistence doctrine** |

---

## 7. Zero-Dependency Policy

This project uses **Pure Android Views** and **Pure Kotlin ML**.
There is absolutely no inclusion of `androidx.*`, Jetpack Compose, or third-party ML frameworks like TensorFlow or PyTorch. This minimizes IPC overhead, limits APK bloat, and guarantees absolute deterministic execution times on the main thread.

---

## 8. Package & Debugging Access

Package Name: `com.iqoo.perfcollect`

To read app-private data files (`gamemode_trace.csv`, etc.) without root:
```bash
adb shell run-as com.iqoo.perfcollect cat files/gamemode_trace.csv
```

---

## 9. Extending Telemetry Sensors

To add a new hardware sensor:
1. Extract it in `LiveTelemetry.kt`.
2. Append it to the 8-dim array.
3. Update `PolicyConfig.NORM_MEAN` and `NORM_STD` to accommodate the new dimension.
4. Wipe the old traces and **retrain** entirely.

---

## 10. Modifying Rewards

Locate `PolicyConfig.kt` and modify `reward()`. 
*Note:* Modifying the base reward requires tweaking the `MODE_W` arrays to ensure Battery and Cool profiles scale accurately under the new logic.

---

## 11. Changing Hyperparameters

Hyperparameters are hardcoded in `Trainer.kt`:
- `LEARNING_RATE = 0.001f`
- `GAMMA = 0.97f`
- `BATCH_SIZE = 32`
- `GRAD_CLIP = 5.0f`

---

## 12. Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| Screen Black | Doze mode aggressive sleep | `adb shell svc power stayon true` |
| Build Fails | Maven / Network dependency | Verify Gradle is local, check for rogue imports |
| No ADPF | API level mismatch | Ensure device is API 34+ for reflection to work |
| NaN Training | Exploding gradients | Check `gamemode_live.csv` for values exceeding `z-score` limits of ±10 |
| FPS Millions | JIT dead-code elimination | Ensure `@Volatile var blackhole = acc` is present in `LoadGenerator.kt` |
