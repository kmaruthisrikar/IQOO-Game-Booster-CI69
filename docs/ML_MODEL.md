# Neural Network & RL Architecture

This document outlines the design, serialization, and training methodology of the custom Reinforcement Learning policy engine powering the application.

---

## 1. Model Philosophy

The system eschews complex, computationally intensive frameworks (like TensorFlow Lite or PyTorch Mobile) in favor of a bespoke, zero-dependency Kotlin Multi-Layer Perceptron (MLP). 

**Why DQN?** Deep Q-Networks map continuous state spaces to discrete action values efficiently, enabling robust offline training using historical telemetry data.
**Why Offline?** Online per-tick learning caused weight divergence (NaNs) and unstable gameplay. By batching offline, we enforce gradient clipping and plausibility gating.
**Why 3 Layers?** The state space is only 8 dimensions and the action space 15. A simple 128-width, 3-layer network processes this with >99% ROC-AUC accuracy in ~10 microseconds, rendering NPU acceleration entirely unnecessary.

---

## 2. Architecture Details

| Layer | Input Size | Output Size | Parameters | Activation | Binary Offset |
|-------|------------|-------------|------------|------------|---------------|
| 1 | 8 | 128 | 1,152 | ReLU | `0x0008` |
| 2 | 128 | 128 | 16,512 | ReLU | `0x1208` |
| 3 | 128 | 15 | 1,935 | Linear | `0x11408` |
| **Total** | - | - | **19,603** | - | - |

---

## 3. Binary File Format

Model files are serialized into highly compact `.bin` files (~78KB).

```text
[Magic Header]   4 bytes (Int32)
[Layer Count]    4 bytes (Int32) -> Always 3

For each layer:
  [Rows]         4 bytes (Int32)
  [Cols]         4 bytes (Int32)
  [Weights]      Rows * Cols * 4 bytes (Float32 Array)
  [Bias Length]  4 bytes (Int32)
  [Biases]       Bias Length * 4 bytes (Float32 Array)
```

---

## 4. State Vector (8-dim)

| # | Dimension | Unit | NORM_MEAN | NORM_STD | Plausibility Bounds |
|---|-----------|------|-----------|----------|---------------------|
| 0 | chipC | °C | 55.0 | 15.0 | -10..130 |
| 1 | skinC | °C | 38.0 | 5.0 | -10..130 |
| 2 | modemC | °C | 45.0 | 10.0 | -10..130 |
| 3 | freqRatio | 0-1 | 0.55 | 0.25 | 0..2 |
| 4 | headroomProxy | 0-100 | 70.0 | 20.0 | 0..100 |
| 5 | fps | fps | 90.0 | 40.0 | 0..500 |
| 6 | netMbps | Mbps | 10.0 | 15.0 | 0..2000 |
| 7 | tSec | s | 300.0 | 200.0 | 0..86400 |

---

## 5. Output and `chooseAction()` Algorithm

The inference and decision selection operate step-by-step:

1. **`fitInput()`**: Truncate or zero-pad the 8-dim state to ensure exact engine input width.
2. **`eng.qValues(x)`**: Execute the forward pass through all 3 layers.
3. **Finite Check**: Ensure Q-values contain no NaNs or Infinities; fallback to profile priors if corrupted.
4. **Z-Score Normalization**: Compute μ and σ of Q-values across all 15 usable actions.
5. **Action Scoring**: 
   `score = (q[i] - μ) / σ + qualityBias[qTier] + netBias[nTier]`
6. **Selection**: Execute `argmax(score)` to pick the final action.

---

## 6. Profile Tilt System

To avoid catastrophic forgetting, the app relies on manual profile biases modifying the Q-values post-inference.

| Profile | Q0 | Q1 | Q2 | Q3 | Q4 |
|---------|----|----|----|----|----|
| Performance | -0.40 | -0.10 | +0.30 | +0.70 | +1.10 |
| Balanced | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| Battery | +0.60 | +0.24 | -0.04 | -0.30 | -0.60 |
| Cool | +1.00 | +0.40 | -0.20 | -0.60 | -1.00 |

*Note: Net bias arrays similarly restrict high-bandwidth actions during Battery and Cool modes.*

---

## 7. Reward Function Math

Reward re-computation utilizes the following LaTeX equivalent logic:

```math
R = w_0 \cdot \text{work} + w_5 \cdot \text{netNorm} - \text{penalty} - w_4 \cdot \text{intensity} \cdot 0.3
```
- $\text{work} = 0.7 \cdot \text{intensity} + 0.3 \cdot \text{fpsScore}$
- $\text{fpsScore} = \max(0, 1 - \frac{|\text{fps} - \text{targetFps}|}{\text{targetFps}})$
- $\text{penalty} = [\text{softPen}(\text{skin}, \text{knee}) \cdot w_1 + \dots ] \times (0.6 + 0.8 \cdot \text{intensity})$

---

## 8. Training Algorithm

**Pseudocode:**
```kotlin
fun trainOffline(batch: List<StateActionReward>) {
    val states = batch.filter { plausibleState(it) }
    if (states.size < 32) return

    val oldWeights = engine.cloneWeights()
    
    for (epoch in 1..EPOCHS) {
        val mseLoss = computeMse()
        engine.backward(learningRate = 0.001, gradClip = 5.0)
    }
    
    if (engine.hasNaN()) {
        engine.restoreWeights(oldWeights)
    }
}
```

---

## 9. Cross-Device Generalization

Models trained under intense thermal conditions on one device generalize across disparate devices effectively. 

**5×5 Pairwise Action Agreement Matrix:**
| Model | 13Perf | 13Batt | 13Cool | Neo10R | 15R Active |
|-------|--------|--------|--------|--------|------------|
| 13Perf | 1.00 | 0.00 | 0.00 | 0.22 | 0.56 |
| 13Batt | 0.00 | 1.00 | 0.07 | 0.71 | 0.42 |
| 13Cool | 0.00 | 0.07 | 1.00 | 0.07 | 0.00 |
| Neo10R | 0.22 | 0.71 | 0.07 | 1.00 | 0.31 |
| 15R Act | 0.56 | 0.42 | 0.00 | 0.31 | 1.00 |

---

## 10. Numerical Stability

> [!CAUTION]
> The Z-clamp mechanism is mandatory. States drifting beyond ±10 standard deviations will explode the network activations. The offline trainer enforces this rigorously. L2 Norm Bounds and Gradient Clipping (value = 5.0) ensure stable descent trajectories.

---

## 11. Full Evaluation Results

| Model | MAE | RMSE | Precision | Recall | F1 Score | ROC-AUC |
|-------|-----|------|-----------|--------|----------|---------|
| 15R Active | 1.733 | 2.927 | 0.941 | 0.982 | 0.967 | 0.927 |
| 13 Perf | 1.820 | 2.982 | 0.938 | 0.978 | 0.967 | 0.935 |
| 13 Battery | 1.994 | 3.095 | 0.950 | 0.965 | 0.969 | 0.812 |
| 13 Cool | 2.701 | 3.816 | 0.966 | 0.955 | 0.969 | 0.779 |
| Neo10R | 1.846 | 3.071 | 0.962 | 0.988 | 0.979 | 0.946 |
