# PLAN — iQOO Game-Mode APK: NPU Model + RL Adaptive Governor

Device: **iQOO I2508** (board `canoe`, brand iQOO, model I2508i) — Snapdragon **SM8845** (8 Elite family), Adreno GPU, **QSPA NPU enabled**, Android 16 (SDK 36), OriginOS, **no root**.

## Goal
An iQOO-Game-Mode-style APK where an **NPU-hosted model (FP16) + RL layer** reads live telemetry and **adaptively fixes thermal states and FPS** according to the user's selected mode (Performance / Balanced / Battery-Saver / Cool).

---

## Part A — Data study: all sessions 1–18 (done)

### Coverage map (what each recorded APK version captured)
| Version | Sessions | CPU util? | wfreq/residency? |
|---|---|---|---|
| v0.1.0 | 1–8, 11, 13, 16 | ✗ | ✗ |
| v0.1.1 (time_in_state) | 9,10,12,14,15,17 | ⚠️ fake-flat (proven wrong) | ✗ |
| v0.1.2 (cpuidle) | 18 | ✓ correct | ✓ |

- Big data is in: **S2** charge (5.8h, 57→85%, +2363mA), **S3** idle (7.3h, 86→50%), **S4/S5/S9** heavy benchmarks (the gold: throttle + heat), **S6/S7/S8** cooldown, **S10–17** tiny junk sessions, **S18** 30s v0.1.2 sanity (15 samples, 16% battery).
- **Load proxies available for ALL heavy sessions**: per-core instantaneous freq (policy0/perf + policy6/prime), `power_w` = battery current×voltage, ADPF headroom/status, full 70-sensor thermal tree, disp refresh rate (VRR: 60/90/120/144 observed), mem/net/proc I/O. `util` only exists for S18 (and fake for S9-17) → model must use freq+power as the load signal, not util.

### Key findings
1. **Throttle sawtooth is real and severe**: S9 ramps 2.23GHz→0.92GHz (prime), battery 33.8→50.6°C, GPU→94.9°C, CPU pkg→109.7°C, ADPF status 0→4 (SEVERE), DDR→108°C. This is exactly the problem Sustained-Execution mode fixes.
2. **corr(power_w, GPU temp) = +0.82** — instantaneous power is a strong live load/predictor.
3. **corr(prime-freq, battery temp) = −0.56..−0.76** — throttle state reads clearly in freq.
4. **Battery temp is near-integrated/slow** (lag-1 autocorr 0.99+ → best slow-state feature, very predictable).
5. **GPU/CPU-pkg/DDR temps are fast & noisy** (lag decay quick) → good *prediction targets*, not control signals.
6. **ADPF headroom is sparse**: only ~24% of samples (≈ every 8.4s) — matches the API's ~10s rate limit → controller must interpolate or run a ~1Hz slow loop.
7. **VRR is live**: refresh rate fluctuates 60/90/120/144 across sessions — a real knob we can detect and adapt to.
8. **Bogus sensors**: `mmw_ific0`, `sdr0` read −273000 (disconnected) → exclude.
9. `headroom` vs battery temp corr +0.99 is a workload-trajectory artifact — headroom is transient, not a static function of temp.
10. Charging throttles itself: S2 charge slows as temp→39°C (thermal charge limit).

---

## Part B — Architecture

```
 ┌───────────────────────────── APK (v0.2, "iQOO Game Mode") ─────────────────────────────┐
 │  UI: mode selector (Perf/Balanced/Battery/Cool) · live telemetry · overlay             │
 │  Service: telemetry loop (~1Hz, reuse PerfCollect collectors)                          │
 │       │ state vector                                                                   │
 │       ▼                                                                                │
 │  NPU (QNN HTP, FP16):                                                                  │
 │     • World Model  — predicts next {Δtemp×4, headroom, status, jank} (supervised)      │
 │     • RL Policy    — picks action {quality tier, fps cap, downscale} (trained offline) │
 │       │ action                                                                         │
 │       ▼                                                                                │
 │  Actuator: governs the app's OWN workload budget (game-like load gen) + ADPF hint      │
 │  session target duration + GameManager mode (best-effort) → closes the loop            │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
```

### The RL problem formulation
- **State** (normalized, t-1 window of last K samples): bat_temp, gpu_temp, cpupkg_temp, ddr_temp, freq_p0, freq_p6, power_w, headroom, adpf_status, refresh, batt_level, current_ua, load proxy, mode.
- **Action** (discrete): quality tier q∈{0..3} → {load mult 1.0/0.85/0.70/0.55, fps cap 120/90/60/45, downscale 1.0/0.9/0.8/0.7}.
- **Reward** (per user mode): `w1·fps_smooth + w2·thermal_margin(target − predicted_temp) + w3·battery_drain − w4·jank` with mode-tuned weights.
- **Training**: model-based RL — first calibrate a **thermal simulator** (RC-lumped model: chip C1 / skin-battery C2, power-driven, throttle curve from S4/S5/S9) → train DQN/PPO offline on millions of sim steps → validate on held-out session 9 trace → deploy policy (tiny NN) to NPU.

### Part C — Phases
| Phase | Deliverable |
|---|---|
| 1. Data prep | Unified 18-session dataset, feature engineering, train/val split, thermal sim calibration |
| 2. World model | Supervised forecast (Δtemps, headroom, status, jank) — MLP/LSTM → ONNX → FP16 |
| 3. RL | Sim-based DQN/PPO training, reward-shaping, ablation of modes, eval vs. no-op (sawtooth) |
| 4. NPU | QNN HTP FP16 export (Qualcomm AI Hub), on-device probe; **fallback NNAPI → CPU** if vivo gates HTP |
| 5. APK v0.2 | Game-Mode UI + service loop + NPU inference + actuator (own workload + ADPF hints) |
| 6. Eval | On-device demo: temp/fps curves with controller ON vs OFF across modes |

### Hard constraints (no root)
- CANNOT change CPU/GPU freq, thermal limits, or other apps' settings.
- CAN control the app's **own workload intensity** + request ADPF hint targets + query/set GameManager mode best-effort.
- iQOO system game-mode features (per-game power plan) are system-level — we replicate the **UX + adaptive logic**, enforced on our own governed workload.

### Data gap to close
Heavy sessions lack trustworthy util (only S18). For a solid model, we should record **20–40 min of real, variable-load "gameplay" sessions** (the v0.1.2 collector already logs util/wfreq/VRR every ~2s). This is a small app-side change (re-record) — no rebuild logic change.

---

## Decisions (user-confirmed)
1. **Actuator** → governs the app's **own built-in load generator** (game-like workload it fully controls) + ADPF performance hints + GameManager mode best-effort.
2. **Modes** → **presets (Performance/Balanced/Battery-Saver/Cool) + custom sliders** (target max temp, target fps).
3. **RL** → **DQN** (discrete actions, small state, offline sim training, easy on-device).
4. **Data** → **record 20–40 min of real variable-load sessions first** with current v0.1.2 collector (util/wfreq/VRR logging) before final model training.
5. **NPU** → **QNN HTP FP16, fallback NNAPI → CPU** if vivo gates direct HTP access.

---

## Execution roadmap
1. **Data collection (user runs now)** — record 20–40 min gameplay-like sessions with v0.1.2 app; export after.
2. **Pipeline build (me, in parallel)** — unified dataset loader, feature engineering, train/val split, **thermal-sim calibration from S4/S5/S9**, world-model prototype, DQN skeleton.
3. **World model** — supervised forecast (Δtemps, headroom, status, jank) → ONNX → FP16.
4. **RL training** — DQN on sim rollouts, mode-tuned rewards, eval vs no-op sawtooth.
5. **NPU export** — QNN HTP via Qualcomm AI Hub, on-device probe, fallback chain.
6. **APK v0.2** — Game-Mode UI (presets + sliders) + service loop + NPU inference + load-generator actuator + ADPF hints.
7. **Eval** — on-device demo: controller ON vs OFF across modes; temp/fps/battery curves.

## Network optimization (added)

The modem/radio is itself a heat source (data shows `mdmss-*`, `ltepa_ntc`,
`nrpa_ntc` thermal zones). The game-mode governor now also manages network power:

- **New actuator**: `NetworkLoadGenerator` pushes real UDP traffic to a host so
  the radio does real work; throughput (Mbps) is measured and reported.
- **New state dims** (8 total): `[chip, skin, modem, freq_ratio, headroom, fps, net_mbps, t]`.
  Modem temp = max(mdmss-*, ltepa_ntc, nrpa_ntc).
- **Expanded actions**: 15 combos = quality tier (5: load 0.40–1.0) x network tier
  (3: net load 0.25/0.60/1.00). Index = quality*3 + net.
- **Env2 sim**: adds a modem node (Cm=150, Rm=8) driven by network power
  (0–1.2 W, up to 100 Mbps); reward includes modem margin + network-quality term.
- **Retrained** DQN (8->128->128->15, Double DQN) for all 4 modes.
- **On-device decode**: action -> (intensity, netTier); netTier feeds NetworkLoadGenerator.
- Target-temp slider override now also drops the network tier to 0.

## Option-3 on-device online learning + clean game UI (v0.2.1)

Per user decision: the RL layer learns from LIVE data on the device (option 3).

- **OnlineLearner (Kotlin, zero-dep)**: ring replay buffer (cap 2048), mini-batch
  Q-learning (batch 16, lr 5e-4, gamma 0.9, every 4 transitions), hand-rolled
  backprop for the 8->128->128->15 ReLU MLP. Runs on CPU — NPU is inference-only.
- **Live reward** (per tick, mirrors offline weights per mode): fps + net quality
  minus chip/skin/modem-excess penalties minus load*battery weight.
- **Exploration**: eps-greedy (eps=0.05) so the policy probes the state space.
- **Persistence**: weights saved every 6 updates to `files/adaptive_<mode>.bin`,
  reloaded on next controller start (verified: adaptive=true across sessions;
  weights differ from frozen asset after learning).
- **UI**: rebuilt as a clean dark game-product dashboard — header, profile pills
  (Performance/Balanced/Battery/Cool), LIVE card (big FPS, chip/battery/modem
  temp gauges color-coded, load/net/action), live stats (samples · ticks ·
  transitions · online updates · loss · reward · elapsed), thermal/FPS ceiling
  sliders, accent START/STOP GAME MODE buttons, telemetry manager below.
- **NPU note**: QNN HTP inference is still unverified on this build (no NNAPI
  driver; vivo HIDL gated). PolicyEngine interface stays swappable for a future
  QnnHtpEngine; online updates apply to the in-memory CPU engine immediately.

---
## v0.3.0 — Train page + 3-page UI + robust network (work in progress)

**User decisions (revised from option-3 above):**
- Game Mode is **pure inference only** — NO training during gameplay.
- Training moves to a dedicated **Train page**: pick a CSV, pick a profile
  (performance/balanced/battery/cool), run offline batch Q-learning on-device.
  Each profile trains its own model (including the network dimension).
- UI reorganized into a neat 3-page dark app: **Game Mode / Train / Telemetry**.
- Session counters refresh live; deleting a session also deletes its exported
  CSV/JSONL artifacts.

### Code landed
- `ml/PolicyConfig.kt` — single source of truth (LOAD, N_NET/N_ACTIONS/N_STATE,
  MODE_W, NORM_MEAN/STD, reward(), normalize()).
- `ml/Trainer.kt` — `parseCsv` (accepts current 15-col trace, reward at idx 14,
  legacy 13-col at idx 12, else computes reward), `loadEngine` (trained file
  else frozen asset), `QUpdate` (shared batch backprop), `OfflineTrainer`
  (multi-epoch shuffled batch Q-learning on background thread).
- `GameModeService.kt` rewritten — pure inference, loads `trained_<mode>.bin`
  if present else asset (`modelUsed`), trace now 15 cols (adds latency_ms,
  loss_pct, reward), latency/loss surfaced for UI.
- `ml/NetworkLoadGenerator.kt` — robustness rewrite: EWMA throughput, TCP-connect
  latency probe (800ms timeout), congestion-aware pacing (factor 1.0→0.3 as
  latency 90ms→250ms), send-budget deficit loss proxy. "iPhone-style" behavior.
- `export/SessionFiles.kt` — deleting a session (or all) also deletes matching
  `session_<id>_*` files in /sdcard/iqoo-data/csv and /sessions.
- `MainActivity.kt` — 3-page tabbed UI. Game Mode: profile pills, LIVE gauges
  (fps/chip/batt/modem, load/net/action, latency/loss, ticks/samples/reward,
  modelUsed), temp+fps sliders, START/STOP. Train: file picker (SAF) +
  "Use app trace" + "Latest device export", mode pills, epochs slider, progress
  bar + live loss, "Reset profile to frozen weights". Telemetry: collector
  start/stop, HEAVY/IDLE markers, export-all, autostart, polling interval,
  live session list with per-session delete.

## v0.3.0 — Models & Favorites + network AIMD (verified on device 2026-08-19)
- `MainActivity.kt` rewritten cleanly (prior external edit was broken): profile
  pills via `segmented()` (no RadioGroup overlap), new **Models & Favorites**
  section — FAVORITES (star-toggled, max 5, prefs `models`/`fav_models`) + ALL
  MODELS (4 frozen + snapshots) + "Save active model as snapshot". Row tap sets
  KEY_MODE + KEY_ACTIVE_MODEL (`.commit()`). Train page trains the ACTIVE profile
  and auto-saves a timestamped snapshot.
- Model store unified on `getExternalFilesDir(null)/models` (always writable, no
  all-files permission). `ModelsDir.baseDir`, `Trainer.loadEngine#1`, and
  `GameModeService` all resolve the same dir. /sdcard/iqoo-data root write is
  blocked on this device (no MANAGE_EXTERNAL_STORAGE grant) → do not depend on it.
- `ModelsDir.kt` — MAX_SNAPSHOTS=5 with oldest-eviction; snapshot naming
  `trained_<mode>_<yyMMdd_HHmmss>.bin`; favorites string-set capped at 5.
- `ml/NetworkLoadGenerator.kt` — AIMD congestion control: additive increase toward
  tier base (300/700/1400 pps), multiplicative ×0.6 on congestion; jitter EWMA
  (>45ms → congested); TCP-connect latency probe (600ms, 2s cadence); send-budget
  deficit loss proxy; hard-failure cooldown; `targetPps` accessor.
- `GameModeService.kt` — `KEY_ACTIVE_MODEL`; loads frozen mode asset / trained
  variant / saved snapshot accordingly; `modelUsed` reflects the actual file.

### On-device verification (DONE 2026-08-19)
- [x] UI fits screen, no overlap (profile pills at y=500–619, content ends 2309<2358)
- [x] Star toggle → FAVORITES section appears (fav_models persisted)
- [x] Save active model as snapshot → file lands in models dir, listed in UI
- [x] Select snapshot → prefs `active_model=trained_balanced_260819_155542`
- [x] START → `controller ON`, `model: trained_balanced_260819_155542.bin`
- [x] STOP (button + `am startservice GM_STOP`) → controller OFF
- [x] Train from `files/gamemode_trace.csv` → valid loss, snapshot auto-saved
- Sim-vs-real gap still open: real chip ~108°C / modem ~86°C in performance mode
  vs sim ~93/41 → recalibration to session-23 real temps recommended.

## v0.4.0 — LIVE fused telemetry + ADPF PerformanceHint (verified on device 2026-08-19)
- **`ml/LiveTelemetry.kt`** — every 2s tick fuses ALL readable live sources and
  builds the SAME 8-dim policy state (contract unchanged → every .bin loads):
  [chipC=max(gpuss/ddr/cpu zones), skinC=battery temp, modemC=max(mdmss),
  freqRatio=scaling_cur/scaling_max, headroomProxy=ADPF thermal headroom %,
  fps, mbps, tSec]. Also tracks GPU busy%, online cores, battery level/current,
  frame target/actual ms → appended to `files/gamemode_live.csv` (22-col) for
  offline analysis. `LiveTelemetry.close()` on STOP.
- **`ml/HintBoost.kt`** — reflective wrapper over ADPF `PerformanceHintManager`
  (@SystemApi — direct compile fails). Creates a `PerformanceHintSession` over the
  LoadGenerator thread tids and reports target/actual frame duration each tick so
  the OS raises CPU freq/scheduling for those threads — the real booster lever.
  Verified on device: "hint session created for 6 threads". No-op fallback.
- **`ml/LoadGenerator.kt`** — captures OS tids (`Process.myTid()`) into a
  CopyOnWriteArrayList, exposed via `tids()` for the hint session.
- **`GameModeService.kt`** — `LiveTelemetry.init` + `createHintSession` on START
  (with retry loop), `close()` on STOP; fused `buildState` via `LiveTelemetry.
  sample`; new companion `last*` fields (CpuFreqMhz, Cores, GpuPct, Headroom,
  BattLevel, FrameTargetMs, FrameActualMs) surfaced in the notification and
  MainActivity LIVE card.
- **`MainActivity.kt`** — LIVE stats line extended: `cpu <MHz> <c>c · gpu <pct>%
  · hdrm <pct>% · batt <pct>% · frm <actual>/<target>ms`.
- **NPU verdict (researched, user question):** model does NOT use NPU (pure-Kotlin
  `KotlinMlpEngine`, no QNN/NNAPI deps). NPU also UNREACHABLE non-root on this ROM:
  QNN HTP v81 libs + firmware present, but `/dev/fastrpc-cdsp` Permission-denied
  in app SELinux domain and no NNAPI HAL (`getNumberOfDevices()=0`). Root or
  vivo-signed key required; pointless for a ~100k-FLOP net anyway.
- **Built-in-booster parity so far:** ADPF thermal (headroom) + ADPF
  PerformanceHint DONE. vivo Multi-Turbo SDK + Android Game Mode API = next step.
- Build note: `BATTERY_PROPERTY_VOLTAGE_NOW` also hidden at compileSdk 36 → dropped
  (voltage not in policy state).

### On-device verification (DONE 2026-08-19)
- [x] Hint session created each run (logcat: "hint session created for 6 threads")
- [x] `gamemode_live.csv` streaming per tick (chip 105°C, skin 30.6°C, modem 44.8°C,
      freq 2390/2390MHz, 8c, GPU 0%, headroom 87.2%, batt 20%/-844mA, frm 28.7/8.3ms)
- [x] Thermal override correctly forces low load tier (chip 105°C > 41°C ceiling)
- [x] LIVE card shows real fused data while controller ON (`cpu 2390MHz 8c · gpu
      0% · hdrm 85% · batt 20% · frm 28.7/8.3ms`)
- [x] STOP clean (button + GM_STOP) → no active services

## v0.4.1 — Unified training sources + button layout (2026-08-19)
- Root fix for "pick file → rows less": the Collector exports a flattened JSON
  CSV, NOT the 15-col RL trace, so `parseCsv` dropped nearly every row. Now the
  telemetry records the SAME fused state as the game trace.
- `collect/TickBuilder.kt` — every telemetry tick calls `LiveTelemetry.sample()`
  (single fused-state code path) and stores the result in the session JSON under
  `rl.*` (`chip_c, skin_c, modem_c, freq_ratio, headroom, fps, net_mbps, action,
  reward, mode, target_temp_c`). Flattened CSV gets `rl.*` columns → telemetry and
  game CSV are literally the same data (no mismatch). CollectorService now
  init/closes LiveTelemetry so `gamemode_live.csv` accumulates collector rows too.
- `Trainer.kt` — auto-detection + converters:
  - `isRlTrace` -> `parseCsv` (game trace)
  - `isLiveCsv` -> `parseLiveCsv` (gamemode_live.csv, 22-col fused sensors)
  - `isCollectorCsv` -> `parseCollectorCsv` (telemetry sessions; prefers `rl.*`
    columns, falls back to raw thermal/battery/adpf/cpu; uses `rl.action`/`rl.reward`
    when recorded, else current-policy argmax + PolicyConfig.reward)
- `MainActivity.kt` — Train page: new "Telemetry sessions" list (tap an exported
  session CSV to select); after a train completes the source row count is
  refreshed (CSV may have grown during training). `listLatestCsv` dir bug fixed
  (`targetExportDir/csv`, not files/csv). Buttons fixed with `buttonRow()`:
  equal-width pill rows on Train (Pick file/Use app trace, Latest device export)
  and Telemetry (Start/Stop, HEAVY/IDLE) — no more cramped/overflowing buttons.

### On-device verification (DONE 2026-08-19)
- [x] Train buttons render as equal-width rows, no overlap
- [x] Telemetry session list shows exported CSVs (needs a recorded + exported session)
- [ ] User to verify: record telemetry session → EXPORT → Train page tap session → TRAIN
      (expect "telemetry session · rows N · final loss") — and same for gamemode_live.csv
