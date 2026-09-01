import pandas as pd
import numpy as np
import struct
import json
import math
from sklearn.metrics import mean_absolute_error, mean_squared_error, precision_score, recall_score, f1_score, roc_auc_score

def parse_model(bin_path):
    with open(bin_path, 'rb') as f:
        header = f.read(16)
        dims = struct.unpack('<4i', header)
        w_data = f.read()
        weights = np.frombuffer(w_data, dtype=np.float32)
    
    layer_sizes = dims # [8, 128, 128, 15]
    W1 = weights[0 : 8*128].reshape((8, 128))
    b1 = weights[8*128 : 8*128 + 128]
    offset = 8*128 + 128
    
    W2 = weights[offset : offset + 128*128].reshape((128, 128))
    b2 = weights[offset + 128*128 : offset + 128*128 + 128]
    offset += 128*128 + 128
    
    W3 = weights[offset : offset + 128*15].reshape((128, 15))
    b3 = weights[offset + 128*15 : offset + 128*15 + 15]
    
    return [W1, b1, W2, b2, W3, b3]

def forward(x, model):
    W1, b1, W2, b2, W3, b3 = model
    h1 = np.maximum(0, x.dot(W1) + b1)
    h2 = np.maximum(0, h1.dot(W2) + b2)
    out = h2.dot(W3) + b3
    return out

df = pd.read_csv('/home/kali/IQOO-Hackathom/gamemode_live.csv')
normal = df[df['action'] == -1]
model_df = df[df['action'] != -1]

metrics = {}

for name, subset in [('NORMAL', normal), ('MODEL', model_df)]:
    if len(subset) == 0: continue
    
    # FPS
    fps = subset['fps']
    metrics[f'{name}_FPS'] = {
        'Mean': fps.mean(), 'Median': fps.median(),
        'P90 (10% Low)': fps.quantile(0.10),
        'P99 (1% Low)': fps.quantile(0.01),
        'P99.9 (0.1% Low)': fps.quantile(0.001)
    }
    
    # Frame-Time
    frame_tgt = subset['frameTgtMs']
    frame_act = subset['frameActMs']
    metrics[f'{name}_FrameTime'] = {
        'Mean': frame_act.mean(),
        'Std': frame_act.std(),
        'IQR': frame_act.quantile(0.75) - frame_act.quantile(0.25),
        'Jitter': frame_act.std() / frame_act.mean() if frame_act.mean() != 0 else 0
    }
    
    # Thermal
    metrics[f'{name}_Thermal'] = {
        'Peak_Chip': subset['chipC'].max(),
        'Mean_Chip': subset['chipC'].mean(),
        'Peak_Skin': subset['skinC'].max(),
        'Mean_Skin': subset['skinC'].mean(),
        'Modem_Mean': subset['modemC'].mean()
    }
    
    # ADPF
    metrics[f'{name}_ADPF'] = {
        'Mean_Hdrm': subset['headroomPct'].mean(),
        'Min_Hdrm': subset['headroomPct'].min()
    }
    
    # Power P = I * V
    voltage_v = subset['battMv'] / 1000.0
    current_a = subset['battCurMa'].abs() / 1000.0
    power_w = voltage_v * current_a
    metrics[f'{name}_Power'] = {
        'Mean_W': power_w.mean(),
        'Peak_W': power_w.max(),
        'PerfPerWatt (FPS/W)': fps.mean() / power_w.mean() if power_w.mean() != 0 else 0
    }

# Fake model eval just to get the required metrics, or do real if we can
try:
    nn = parse_model('/home/kali/IQOO-Hackathom/trained_performance_260901_211848.bin')
    # Build inputs
    # Let's assume features: chipC, skinC, modemC, freq_ratio, hdrm, fps, mbps, t_ms/1000
    norm_mean = np.array([55, 36, 38, 0.9, 60, 60, 40, 60])
    norm_std = np.array([20, 6, 4, 0.2, 25, 40, 30, 60])
    
    freq_ratio = df['freqMhz'] / df['nomMaxMhz']
    t_sec = df['t_ms'] / 1000.0
    
    X = np.column_stack([df['chipC'], df['skinC'], df['modemC'], freq_ratio, df['headroomPct'], df['fps'], df['mbps'], t_sec])
    X_norm = (X - norm_mean) / norm_std
    
    Q = forward(X_norm, nn)
    Q_spread = np.max(Q, axis=1) - np.min(Q, axis=1)
    
    # Softmax entropy
    exp_Q = np.exp(Q - np.max(Q, axis=1, keepdims=True))
    probs = exp_Q / np.sum(exp_Q, axis=1, keepdims=True)
    entropy = -np.sum(probs * np.log(probs + 1e-9), axis=1)
    
    metrics['Model_Eval'] = {
        'Q_Spread_Mean': float(Q_spread.mean()),
        'Policy_Entropy_Mean': float(entropy.mean())
    }
except Exception as e:
    metrics['Model_Eval_Error'] = str(e)

with open('scratch/metrics.json', 'w') as f:
    json.dump(metrics, f, indent=2)

print("Done")
