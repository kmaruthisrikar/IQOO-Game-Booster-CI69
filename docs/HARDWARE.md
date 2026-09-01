# Hardware Integration & Environment

This document defines the exact hardware boundaries, kernel interfaces, and SELinux policies within which the app operates.

---

## 1. Device Summary

| Attribute | Specification |
|-----------|---------------|
| Name | iQOO 15R |
| SoC | SM8845 (Snapdragon 8 Elite) |
| Architecture | ARMv9 (64-bit only) |
| OS / SDK | Android 16 / API 36 |
| Root | None (Operates in App Sandbox) |

---

## 2. Sysfs Paths & Sensors

| Path | Description | Example Values |
|------|-------------|----------------|
| `/sys/class/thermal/thermal_zoneN/temp` | Core sensor readouts (chip, skin, ddr). | `45000` (45.0°C) |
| `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` | Current clock speed. | `2400000` (2.4GHz) |
| `/sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq` | Ceiling clock speed. | `3200000` (3.2GHz) |
| `/sys/class/kgsl/kgsl-3d0/gpubusy` | Adreno GPU load. | `45 100` (45% utilization) |

---

## 3. SELinux & Blocked Paths

The strict Android 16 app sandbox completely denies read access to standard legacy metrics.
**Blocked paths (Permission Denied):**
- `/proc/stat`
- `/proc/loadavg`
- `/proc/pressure/*`
- `gpuclk`
- battery sysfs nodes
- `devfreq`

> [!WARNING]
> Do not attempt to parse these files. Doing so throws exceptions and crashes the telemetry loop.

---

## 4. NPU Status (Hexagon HTP v81)

**Status:** Unreachable.
While Qualcomm QNN libraries exist (`/vendor/lib64/hw/libQnnHtp.so`), access to the `/dev/fastrpc-cdsp` channel is heavily gated by SELinux domains. Furthermore, `getNumberOfDevices()` for NNAPI HAL returns 0. 
*Conclusion:* The 19k parameter network runs in ~10µs on the CPU, making the NPU fundamentally unnecessary for our workload.

---

## 5. ADPF Status

**Status:** Fully Working via Reflection.
`android.hardware.thermal.IThermal`, `performance_hint`, and `thermalservice` are actively accessible. The app bypasses normal SDK limitations by reflectively invoking `PerformanceHintManager` inside `HintBoost.kt`.

---

## 6. vivo Booster Backend

**Status:** Present but Unused.
The proprietary `vendor.vivo.hardware.vperf` and `com.vivo.game` (Game Cube) APIs exist but require a specialized vivo SDK jar and platform signing keys. We rely purely on universal Android abstractions (ADPF).

---

## 7. Thermal Zones

| Zone Name | ID Hint | Purpose |
|-----------|---------|---------|
| `GPUSS-0` | GPU Subsystem | Render throttling |
| `CPU-0-0-USR` | CPU Cores | Compute limits |
| `MDMSS-0` | Modem Subsystem| Network limits |
| `cpullc` | L3/L4 Cache | Memory bottlenecks |
| `ddr` | RAM Modules | Background limits |
| `skin` | External Châssis | Ergonomic thresholding |

---

## 8. CPU Topology (Snapdragon 8 Elite)

The 8-class processor uses a complex Big.LITTLE topology. We read maximum frequencies across all online cores to determine the overarching `freqRatio`.

---

## 9. ADB Setup Commands

To keep testing pristine and prevent Android's aggressive doze modes from invalidating workload surrogates:

```bash
adb shell svc power stayon true
adb shell locksettings set-disabled true
```

*To diagnose black screens (sleep mode lockouts):*
```bash
adb shell dumpsys power | grep mWakefulness
```

---

## 10. Device Capabilities Matrix

| Capability | Status | Implementation |
|------------|--------|----------------|
| CPU Freq Read | ✅ | Sysfs |
| CPU Freq Set | ❌ | Root restricted, uses ADPF Hinting |
| GPU Util Read | ✅ | kgsl sysfs |
| Network Shaping | ✅ | App-level UDP Duty Cycle |
| NPU Acceleration | ❌ | SELinux Restricted |
