import numpy as np
import json
import struct

def parse_model(bin_path):
    with open(bin_path, 'rb') as f:
        header = f.read(16)
        dims = struct.unpack('<4i', header)
        w_data = f.read()
        weights = np.frombuffer(w_data, dtype=np.float32)
    
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

nn = parse_model('/home/kali/IQOO-Hackathom/trained_performance_260901_211848.bin')
norm_mean = np.array([55, 36, 38, 0.9, 60, 60, 40, 60])
norm_std = np.array([20, 6, 4, 0.2, 25, 40, 30, 60])

# Simulate ramp up 35 to 75
temps_up = np.linspace(35, 75, 40)
actions_up = []
for t in temps_up:
    X = np.array([t, 36, 38, 0.9, 60, 60, 40, 60])
    X_norm = (X - norm_mean) / norm_std
    Q = forward(X_norm, nn)
    actions_up.append(int(np.argmax(Q)))

# Simulate cool down 75 to 40
temps_down = np.linspace(75, 40, 35)
actions_down = []
for t in temps_down:
    X = np.array([t, 36, 38, 0.9, 60, 60, 40, 60])
    X_norm = (X - norm_mean) / norm_std
    Q = forward(X_norm, nn)
    actions_down.append(int(np.argmax(Q)))

# Count oscillations
def count_osc(acts):
    osc = 0
    for i in range(2, len(acts)):
        if acts[i] == acts[i-2] and acts[i] != acts[i-1]:
            osc += 1
    return osc

osc_up = count_osc(actions_up)
osc_down = count_osc(actions_down)

with open('scratch/metrics3.json', 'r') as f:
    metrics = json.load(f)

metrics['Closed_Loop'] = {
    'Ramp_Up_Oscillations': osc_up,
    'Cool_Down_Oscillations': osc_down,
    'Unique_Actions_Up': list(set(actions_up)),
    'Unique_Actions_Down': list(set(actions_down)),
    'Recovery_Hysteresis_Deadlocks': 0 if osc_up == 0 and osc_down == 0 else 1
}

with open('scratch/metrics_final.json', 'w') as f:
    json.dump(metrics, f, indent=2)
print("Done")
