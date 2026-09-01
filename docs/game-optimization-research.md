# Gaming Performance-Optimization Algorithms — Deep Research

Study of 30+ optimization technologies across **Apple iPhone**, **Android ADPF**, and **Qualcomm SDK**, done to guide the PerfCollect → on-device Hexagon NPU performance/device-life model.

PerfCollect context: vivo iQOO 15R (Snapdragon 8 Gen 5, Adreno 829, Android 16, no root). Data available: 17 exported sessions (benchmarks, charge, idle), CPU util via cpuidle sysfs, per-policy freq residency, ADPF headroom/status, temps, power draw, GPS/motion.

---

## 1. Apple / iPhone (public knowledge)

### 1.1 iOS 18 Game Mode
- Auto-enables when a game launches. Grants the game **highest CPU/GPU priority**, minimizes background process activity, and **doubles the Bluetooth sampling rate** for controllers/AirPods → lower input + audio latency.
- User-visible Control Center Game Overlay; per-game toggle. Renders window into lowest-latency display mode.

### 1.2 Sustained Execution Mode (`com.apple.developer.sustained-execution`)
- Entitlement for game-like apps. The system **caps burst (boost) performance at launch** so the device does not hit a thermal cliff 30s in. Result: **consistent, predictable performance** for the whole session instead of a high-then-throttle sawtooth.
- Key insight: A flatter performance envelope (lower peak, sustained) is often better UX than burst-then-drop. This is the exact failure mode seen in PerfCollect S4/S5/S9 (GPU 85–95°C, CPU pkg 105–115°C, ADPF SEVERE).

### 1.3 `NSProcessInfo.thermalState`
- 4 states: `.nominal`, `.fair`, `.serious`, `.critical`. Apps get notified and **proactively reduce CPU/GPU/IO and frame rate** instead of waiting for hardware throttle. (≈ Android's ADPF Thermal Headroom API.)

### 1.4 Xcode Thermal Conditioner
- Simulator that lets devs test the game **under simulated thermal stress** to see how their adaptive pipeline behaves before shipping.

### 1.5 MetalFX Spatial Upscaling
- Single-pass scale-up from a lower render resolution. Cheap, one-frame latency, no temporal state. Baseline for "render low → present high."

### 1.6 MetalFX Temporal AA + Upscaling (the interesting one)
- **Temporal sampling**: each frame the render target uses a slightly different sub-pixel sample position (jitter), accumulating over frames → effective supersampling without extra fill cost.
- Inputs: jittered color, motion vectors, depth, and **previous-frame history**.
- Uses **Halton(2,3) 32-sample jitter sequence** (radical-inverse, quasi-random, good low-discrepancy distribution).
- Mip bias set to `log2(renderW / targetW) - 1` so textures stay sharp after upscale.
- `resetHistory()` on scene cuts / camera cuts / heavy HUD changes to avoid ghosting/smearing.
- **8 jitter samples per output pixel** for quality setting; different sample counts per quality tier.
- Motion-vector + depth history validation filters out invalid history (disocclusion).
- *Relevance*: this is the gold-standard mobile temporal upscaler; SGSR2 (Qualcomm) follows the same recipe. The algorithm is heavy on shader ALU + memory bandwidth for history buffers — measurable in PerfCollect via GPU load.

### 1.7 ProMotion adaptive refresh
- 10–120Hz (iPhone) / 24–120Hz (iPad). The **system** owns the actual displayed rate; the app only supplies a `CADisplayLink.preferredFrameRateRange`.
- In **Low Power Mode or high thermal**, the system caps the rate → power saving + cooling, app doesn't need to change logic.
- At 120Hz: **triple buffering**. At 60Hz: dual buffering. Triple buffer = lower frame-to-vsync time, less pipe starvation under jitter.
- Display must pick a rate that is a factor of the panel max (e.g. on a 120Hz panel: 120, 60, 40, 30, 24 — never 100). PLL/DispSync re-lock causes vsync phase shifts → apps must use `targetTimestamp`, not `timestamp`.

### 1.8 CADisplayLink targetTimestamp
- Prepare content against `targetTimestamp` (when the frame will be shown), not the last callback timestamp. Needed because during refresh-rate transitions the callback time and display time diverge.

### 1.9 QoS classes (Quality of Service)
- `userInteractive` / `userInitiated` / `utility` / `background`. The scheduler resolves priority inversion, defers background work, and **pauses discretionary work under Low Power Mode**. Equivalent concept to Android thread priorities + `uclamp`.

### 1.10 App Nap / App Prioritization
- Off-screen, non-visible apps are throttled aggressively; visible games get the whole GPU/CPU budget. "What is on screen owns the silicon."

---

## 2. Android — ADPF & platform

### 2.1 ADPF Thermal API — `PowerManager.getThermalHeadroom(forecastSeconds)`
- Returns a float in **0.0..1.0+**; **1.0 = the throttling (SEVERE) threshold**. Below 1.0 = headroom available; above 1.0 = already throttled.
- Tracks **slow skin/board temperature sensors** (smoothed, low-pass), so it is a *slow* signal — not for per-frame control, but perfect for slow-state adaptation (PerfCollect battery temp ≈ same role, empirically corr(prime_freq, batt_temp) = −0.59).
- **Do not call more than ~once per 10s** (the forecast needs a few samples; too-frequent calls return NaN until it recalibrates).
- `forecastSeconds` uses recent slope to extrapolate future headroom → you can pre-emptively lower quality before hitting 1.0.
- Android 15+: also `getCurrentThermalStatus()` 0–6 (NONE→SEVERE) + per-status headroom thresholds via `PowerManager.getThermalHeadroomThresholds`.

### 2.2 Caveat: unreliable thermal status
- Some devices/OEMs return `THERMAL_STATUS_NONE` always (thermal HAL bug) → the headroom API is the reliable cross-device signal. This is why ADPF guidance is headroom-first.

### 2.3 ADPF Performance Hint — `PerformanceHintManager.Session` (API 31+)
- Create a session, **group the threads** doing the frame work, set `targetWorkDuration` (e.g. 16ms), then each frame call `reportActualWorkDuration(actual)`.
- The system then **adjusts core placement + frequencies to hit the target with minimum energy** — the kernel tunes DVFS to a *time budget*, not just a load.
- Models the task as real-time-ish; uses a feedback controller around the target duration.
- Best paired with Unity/Unreal ADPF plugins which expose the API cleanly.

### 2.4 ADPF GPU hint (Android 12+ / API 34–35)
- Hints now cover the **GPU pipeline** (vulkan command buffer / frame boundaries). Report CPU + GPU work per frame together so the system can allocate between them; `notifyWorkloadIncrease` for level transitions.

### 2.5 Game Mode API (Android 12/13+)
- `GameManager.getGameMode()` → `STANDARD` (default), `PERFORMANCE`, `BATTERY`, `CUSTOM` (OEM interventions like vivo's own modes). User picks per game in the Game Dashboard.
- App should query on `onResume` and adapt (e.g. PERFORMANCE → keep 60fps + high quality; BATTERY → cap 30fps, low res). Google quotes ~25% power savings from a properly responsive game.
- Requires `android:appCategory="game"` in the manifest.

### 2.6 Game State API (Android 15+)
- `GameManager.setGameState(GAME_STATE_LOADING/GAMEPLAY/UNKNOWN)`; system boosts I/O + CPU during loading, then rebalances for gameplay. This is the OS-side analogue of iPhone's Game Mode background-minimization.

### 2.7 Fixed Performance Mode
- Disables dynamic DVFS/gov hysteresis so **benchmarks** measure the hardware ceiling without governor lag. (Note: our benchmarks ran under normal governor — real numbers are lower than the ceiling.)

### 2.8 ADPF scalability thresholds (Google's Unity/Unreal reference)
- Sample headroom ~1/s. Map to quality tiers: `<0.75 → Q3 (high)`, `0.75–0.85 → Q2`, `0.85–0.95 → Q1`, `>0.95 → Q0 (low)`. Adjust resolution / LOD / shadows / framerate accordingly. React slowly (hysteresis) to avoid oscillation.

### 2.9 Performance Class (MPC)
- CDD-defined minimum hardware tiers (MPC 33/34/35). Apps use `Jetpack core-performance` to query and then choose feature flags. Not an algorithm, but the mechanism by which Android normalizes "what level am I allowed to assume."

### 2.10 Frame pacing / VSync / SurfaceFlinger
- `DispSync` = software PLL locked to hardware vsync. Two offsets: `VSYNC_APP` and `VSYNC_SF`. Pipeline: App renders **N+2** while display shows N (triple-buffer look-ahead), SurfaceFlinger composites the layer, HWC programs the display.
- Choreographer callback per vsync; `doFrame` starts a frame; if you miss the vsync you render for the *next* one (frame drop, no tearing).
- Low refresh (e.g. 60Hz) at same content rate = same work, half the display/scanout energy.

### 2.11 FrameTimeline jank detection
- Android's system-level frame classification:
  - `AppDeadlineMissed` — app finished after its vsync deadline.
  - `BufferStuffing` — app swapped more buffers than the display consumes (queue stuffing).
  - `SurfaceFlingerCpuDeadlineMissed` / `GPUDeadlineMissed` / `DisplayHAL` — composer/GPU/display missed.
  - `PredictionError` — FrameTimeline predicted the wrong end-of-frame.
- Outputs `on-time` vs `late` per frame. This is the *ground-truth* jank label we can collect as a target for the NPU model.

### 2.12 Jank severity thresholds
- 16ms/frame target (60fps); "slow" = 16–700ms; "frozen" = 700ms–5s; ANR >5s. (Note: at 120fps the budget is 8.3ms.)

### 2.13 Two game-loop models
- **Queue stuffing** ("swap as fast as possible"): renders ahead, relies on BufferQueue back-pressure; high input lag, wasteful at 120Hz panels.
- **Choreographer-driven** ("drop when late"): game state advances exactly per vsync; frame dropped when late → lower latency, jitter is quantized to vsyncs.

---

## 3. Qualcomm

### 3.1 QAPE — Qualcomm Adaptive Performance Engine
- Low-level perf hints that **map to the right CPU cores without SoC topology knowledge**:
  - `hint_thread_pipeline()` — signals a latency-critical thread loop (boost scheduling).
  - `hint_high_util()` — sustained heavy thread.
  - `hint_low_latency()` — short, latency-sensitive bursts.
- Hints are **sticky** (auto-release) and get auto-acquired/released on activity lifecycle. Built to work *alongside* ADPF, not replace it. Targets the gaming-native path (direct kernel tuning, GPU+CPU).

### 3.2 Snapdragon Elite Gaming suite (marketing = capabilities list)
VRS (rate shading), HDR Fast Blend, updatable GPU drivers, AFME (frame gen), SGSR (upscaling), Game Post-Processing Accelerator (FFX-style effects offloaded), Shadow Denoiser, **Adaptive Game Configuration** (dynamic resolution guidance per SoC), Game Frame Rate Conversion.

### 3.3 Adreno Frame Motion Engine (AFME) 2.0/3.0
- **Frame interpolation**: synthesizes in-between frames from consecutive rendered frames + motion data → doubles apparent fps (e.g. render 60, present 120).
- On Gen 5 (SM8850) AFME 3.0 with lower latency and better motion handling. Pairs with panel-side VRR/GFRC.
- *Relevance*: this is how high-refresh gameplay feels smooth without 2× the GPU fill — the OS/game splits budget: render 30–60, present 60–120.

### 3.4 SGSR — Snapdragon Game Super Resolution
- **SGSR 1.0**: single-pass, **12-tap Lanczos-like** filter + adaptive sharpening. Temporal-free, cheap. Implemented as an Unreal plugin; Vulkan SPIR-V, works across Snapdragon GPUs.
- **SGSR 2.0**: **temporal upscaler** (the MetalFX/FSR3 recipe): per-pixel motion vectors, reprojection into history, hole-filling on disocclusion, anti-ghosting. Built for the **tiled GMEM** architecture (keeps history in on-chip memory, avoids tile-memory traffic).
- *Relevance*: the classic "render 540p → present 1080p" saves massive fill/ALU while looking near-native. Directly measurable as GPU-load drop in PerfCollect.

### 3.5 Snapdragon Game Toolkit
- Official dev guides: thread affinity, NEON/SVE SIMD, Oryon/Kryo tuning, Unreal plugins (GSR, Neural Processing SDK, Shadow Denoiser, and the VRS plugin), Windows-on-Snapdragon (ARM64EC) packaging.
- This is the "how to actually use the silicon" manual.

### 3.6 Snapdragon Profiler
- **150+ hardware performance counters**; real-time graphs, trace capture, snapshot capture, frame-by-frame Vulkan/GLES replay. Measures real HW (GMEM traffic, cache misses, GPU queue utilization) instead of guessing.

### 3.7 QNN / Hexagon NPU (HTP) — QAIRT
- Two API levels:
  - **SNPE**: simpler, multi-processor orchestration (CPU/GPU/HTP), quicker to production.
  - **QNN (QAIRT)**: granular, per-processor control (HTP backend = Hexagon Vector eXtensions + tensor accelerator), op-package granularity, `serialized.bin` context caching (avoids recompile per load).
- Key patterns: `Qmem` (RPCMem shared buffers) for zero-copy input/output between app and HTP; per-channel quantization; INT4/INT8/FP16 precision trade-offs; context = precompiled graph + weights.
- *Relevance*: our NPU model would ship as a QNN context blob, inference <1–2ms per sample → real-time adaptation loop is feasible.

### 3.8 Adreno tiled architecture (why mobile perf differs from desktop)
- **Tiled GMEM**: on-chip fast memory; a frame is split into bins; geometry pass then per-bin render pass. 
- **FlexRender**: hybrid mode that automatically picks direct (non-tiled) rendering when it's faster for the workload.
- `VK_QCOM_tile_memory_heap` / `VK_QCOM_tile_shading` let apps allocate transients in GMEM explicitly.
- Gen 5 (SM8850) = **2-slice GPU** — workloads split across two GPCs; helps frame gen + upscaling pipelines.
- LRZ (Low Resolution Z) early-out speeds depth-heavy passes.
- *Relevance*: GPU load is not a single number; tiling vs. fragment vs. bandwidth are separate bottlenecks. PerfCollect's GPU counter (gpuss-6) is aggregate.

---

## 4. Linux/Android kernel internals (power/thermal/scheduler)

### 4.1 schedutil governor
- `f = 1.25 × util × fmax` (+ margin, capped & rate-limited to avoid wild swings).
- Frequency set per (group of) CPUs; DVFS update on util change. Feedback from idle injection.

### 4.2 Frequency invariance & APERF/MPERF/AMU
- Micro-arch invariance: if a 1.8GHz core runs the same work as a 2.2GHz core, util must be equal → scale by max-frequency-ratio. On ARM, AMU (Activity Monitors) counters give per-core cycle/instret/energy signals; APERF/MPERF on x86.
- **This is exactly what `time_in_state` residency is NOT** — residency counts wall-clock, not active cycles. Our cpuidle-based util is the invariance-correct approach.

### 4.3 PELT (Per-Entity Load Tracking)
- Util decays exponentially (half-life 32ms-ish) — a 10s idle gap collapses util toward 0 over ~2 periods. That's why "load spikes then fades" looks the way it does in the data.

### 4.4 UTIL_EST
- Exponential moving average (IIR) keeps a running estimate of *achieved* utilization, so short bursts don't collapse the frequency as fast (helps interactive/game workloads).

### 4.5 uclamp (utilization clamping)
- Per-task `uclamp_min` / `uclamp_max`: pins task placement + allows that task to *request* frequency regardless of current util (e.g. `hint_thread_pipeline` from QAPE sets a uclamp boost). Also lets you **cap** tasks (background/minimized) → this is the mechanism behind Game Mode background minimization.

### 4.6 EAS (Energy Aware Scheduling)
- Puts tasks on the core/CPU that minimizes estimated energy for the target latency. Requires energy model tables per SoC. The whole "prime vs perf core" question is EAS's job; our data shows the system migrates to/from prime cores under thermal load.

### 4.7 Battery / charging health management (battery-life extension side)
- **Adaptive Charging** (Android 11+, Google/OnePlus/Pixel): learns your unplug time, holds battery at ~80% and completes the last 20% just before you wake — minimizes time sitting at 100% (the #1 calendar-aging driver).
- **Limit to 80%** (Android 15 default on Pixels): stops charging at 80%; usable while plugged (bypass, no cycle use).
- **Thermal charging limits**: charging pauses or drops to a low current when battery temp is high (this is why charge curves flatline at high temps — our S2 charge slowed as temp→39°C).
- Cycle-life: Li-ion ~500 cycles to 80% health at 100% DoD; higher temps accelerate aging (Arrhenius). *Relevance*: device-life model should blend (temp, SoC, charge current, cycles) → recommend charge window/limit. PerfCollect already has battery current + temp + SoC per sample.

### 4.8 Variable Refresh Rate on Android
- Panels down to 1Hz on some flagships (LTPO). System drives content-rate VRR. Auto VRS + GFRC (panel-side motion-estimated frame doubling) are the vendor paths. "Render less, show smooth" is the theme across every vendor now.

---

## 5. Frame-generation research (cross-platform)

### 5.1 AMD FSR3 / AFMF
- **FSR3**: frame *interpolation* with optical-flow motion vectors + game-supplied depth; needs explicit game integration; recommended base rate 60fps.
- **AFMF**: *driver-level* frame gen for any game (no integration) — less accurate but zero-cost to adopt.

### 5.2 Mob-FGSR (SIGGRAPH 2024, "Mobile Frame Generation via Super-Resolution")
- Splat-based motion reconstruction: render motion vectors at lower res, upscale with a light splat pass.
- **Quadratic motion assumption** — models acceleration, better than linear for camera pans.
- Interpolation + extrapolation hybrid; thin-object gap filling; **no neural net** → real-time on mobile GPUs.
- The academic baseline for on-device frame gen, and a hint at what AFME-family hardware does in the shadow.

---

## 6. Synthesis — what all of this means for PerfCollect → NPU model

1. **Target signal**: FrameTimeline jank labels + ADPF headroom + GPU/CPU/DDR temps + per-policy freq are the "state." The NPU model should predict *next-state* (headroom trend, jank risk) and *suggest actions* (quality tier, framerate cap, charge limit, frame-gen on/off).
2. **Slow vs fast states**: battery temp (slow, smooth — best model feature), GPU/CPU-pkg temp (fast, noisy — prediction targets), headroom (transient — control signal, not a model input for static temp).
3. **The sawtooth problem** (iPhone's sustained-execution insight): our own data proves burst-then-throttle (prime 2.23GHz→921MHz, battery→51°C). A model that flattens the envelope = better sustained fps + longer battery.
4. **Leverage, not fight, the stack**: let ADPF/QAPE do DVFS; the app layer adapts quality via headroom thresholds (2.8); kernel handles core placement via EAS/uclamp.
5. **Device-life side**: blend (battery temp, SoC, charge/discharge current, cycles) → recommend charge limit / adaptive charging window / thermal charge pause. That's a *second* model head that shares the same telemetry stream.
6. **Feasibility**: QNN HTP inference of the model is <2ms per sample → closed-loop in-loop adaptation is realistic (hint sessions + quality tier switching every ~1s, not per frame).

---

## Appendix: sources
- developer.android.com — ADPF (PowerManager, PerformanceHintManager, Game Mode API, Game State API, performance-class, FrameTimeline)
- source.android.com — VSync/DispSync, SurfaceFlinger, EAS, schedutil, PELT/UTIL_EST/uclamp
- developer.apple.com — Game Mode, MetalFX WWDC22 Session 10103 (Temporal AA & Upscaling), ProMotion / CADisplayLink, Sustained Execution, NSProcessInfo thermalState
- docs.kernel.org — schedutil, cpufreq, cpu_idle (cpuidle), freq-invariance
- Qualcomm — QAPE perf API, QNN/QAIRT HTP docs, SGSR repo (github.com/SnapdragonStudios/snapdragon-gsr), Snapdragon Profiler, Snapdragon 8 Elite Gen 5 (SM8850) product brief, Adreno tiling/FlexRender (VK_QCOM_*)
- AMD FSR3 blog; Mob-FGSR (ACM SIGGRAPH 2024)
