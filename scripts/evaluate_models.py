import pandas as pd
import numpy as np
import json
import os
import struct
import matplotlib.pyplot as plt
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.metrics import mean_absolute_error, mean_squared_error, precision_score, recall_score, f1_score, roc_auc_score

def relu(x):
    return np.maximum(0, x)

def parse_model(file_path):
    with open(file_path, 'rb') as f:
        data = f.read()
    n1, n2, nOut, nIn = struct.unpack('<4i', data[:16])
    offset = 16
    w1 = np.frombuffer(data, dtype=np.float32, count=n1*nIn, offset=offset).reshape(n1, nIn)
    offset += n1*nIn*4
    b1 = np.frombuffer(data, dtype=np.float32, count=n1, offset=offset)
    offset += n1*4
    w2 = np.frombuffer(data, dtype=np.float32, count=n2*n1, offset=offset).reshape(n2, n1)
    offset += n2*n1*4
    b2 = np.frombuffer(data, dtype=np.float32, count=n2, offset=offset)
    offset += n2*4
    w3 = np.frombuffer(data, dtype=np.float32, count=nOut*n2, offset=offset).reshape(nOut, n2)
    offset += nOut*n2*4
    b3 = np.frombuffer(data, dtype=np.float32, count=nOut, offset=offset)
    return {'n1': n1, 'n2': n2, 'nOut': nOut, 'nIn': nIn, 'w1': w1, 'b1': b1, 'w2': w2, 'b2': b2, 'w3': w3, 'b3': b3}

def forward_pass(model, state):
    h1 = relu(np.dot(model['w1'], state) + model['b1'])
    h2 = relu(np.dot(model['w2'], h1) + model['b2'])
    q = np.dot(model['w3'], h2) + model['b3']
    return q

def get_action(q):
    return np.argmax(q)

def softmax(x):
    e_x = np.exp(x - np.max(x))
    return e_x / e_x.sum()

NORM_MEAN = np.array([55.0, 36.0, 38.0, 0.9, 60.0, 60.0, 40.0, 60.0])
NORM_STD = np.array([20.0, 6.0, 4.0, 0.2, 25.0, 40.0, 30.0, 60.0])

def normalize(row):
    s = np.zeros(8)
    s[0] = row['chipC']
    s[1] = row['skinC']
    s[2] = row['modemC']
    s[3] = row['freqMhz'] / max(row['maxMhz'], 1)
    s[4] = row['headroomPct']
    s[5] = row['fps']
    s[6] = row['mbps']
    s[7] = min(row['t_ms'] / 1000.0, 1800.0)
    z = (s - NORM_MEAN) / NORM_STD
    return np.clip(z, -10.0, 10.0)

df = pd.read_csv('/home/kali/IQOO-Hackathom/gamemode_live.csv')
states = np.array([normalize(row) for _, row in df.iterrows()])
df['next_chipC'] = df['chipC'].shift(-1)
valid_idx = df['next_chipC'].notna()
states_valid = states[valid_idx]
y_temp = df.loc[valid_idx, 'next_chipC'].values
y_class = (y_temp > 55.0).astype(int)

models_paths = {
    'iQOO 13 Perf': '/home/kali/IQOO-Hackathom/models_test/iqoo 13/trained_performance_260901_165355.bin',
    'iQOO 13 Batt': '/home/kali/IQOO-Hackathom/models_test/iqoo 13/trained_battery_260825_145759.bin',
    'iQOO 13 Cool': '/home/kali/IQOO-Hackathom/models_test/iqoo 13/trained_cool_260901_170341.bin',
    'Neo 10R Perf': '/home/kali/IQOO-Hackathom/models_test/neo 10r/trained_performance_260902_011323.bin',
    'iQOO 15R Active': '/home/kali/IQOO-Hackathom/trained_performance_260901_211848.bin'
}

results = {}
q_values_dict = {}
actions_dict = {}
roc_curves = {}

for name, path in models_paths.items():
    if not os.path.exists(path):
        continue
    m = parse_model(path)
    Q = np.array([forward_pass(m, s) for s in states_valid])
    A = np.argmax(Q, axis=1)
    actions_dict[name] = A
    q_tiers = A // 3
    n_tiers = A % 3
    q_dist = {f"Q{i}": int(np.sum(q_tiers == i)) for i in range(5)}
    n_dist = {f"N{i}": int(np.sum(n_tiers == i)) for i in range(3)}
    
    lr = LinearRegression()
    lr.fit(Q, y_temp)
    yp = lr.predict(Q)
    mae = float(mean_absolute_error(y_temp, yp))
    rmse = float(np.sqrt(mean_squared_error(y_temp, yp)))
    
    clf = LogisticRegression(max_iter=1000)
    clf.fit(Q, y_class)
    ypc = clf.predict(Q)
    ypp = clf.predict_proba(Q)[:, 1]
    
    from sklearn.metrics import roc_curve
    fpr, tpr, _ = roc_curve(y_class, ypp)
    roc_curves[name] = (fpr, tpr, roc_auc_score(y_class, ypp))
    
    results[name] = {
        'Stability': {
            'w1_L2': float(np.linalg.norm(m['w1'])), 'w2_L2': float(np.linalg.norm(m['w2'])), 'w3_L2': float(np.linalg.norm(m['w3']))
        },
        'Q_Stats': {'Mean': float(np.mean(Q)), 'Max': float(np.max(Q)), 'Spread': float(np.mean(np.std(Q, axis=1))), 'Entropy': float(np.mean([-np.sum(p * np.log(p + 1e-9)) for p in [softmax(q) for q in Q]]))},
        'Action_Dist_Quality': q_dist, 'Action_Dist_Net': n_dist,
        'Thermal': {
            'MAE': mae, 'RMSE': rmse,
            'Precision': float(precision_score(y_class, ypc, zero_division=0)),
            'Recall': float(recall_score(y_class, ypc, zero_division=0)),
            'F1': float(f1_score(y_class, ypc, zero_division=0)),
            'ROC_AUC': float(roc_auc_score(y_class, ypp))
        }
    }

names = list(actions_dict.keys())
agreement = np.zeros((len(names), len(names)))
for i, n1 in enumerate(names):
    for j, n2 in enumerate(names):
        agreement[i, j] = np.mean(actions_dict[n1] == actions_dict[n2])

plt.style.use('dark_background')
fig, axs = plt.subplots(2, 2, figsize=(16, 12))
ax = axs[0,0]
x = np.arange(5)
width = 0.15
for i, n in enumerate(names):
    counts = [results[n]['Action_Dist_Quality'].get(f"Q{k}", 0) for k in range(5)]
    ax.bar(x + i*width, counts, width, label=n)
ax.set_xticks(x + width*2)
ax.set_xticklabels([f"Tier {i}" for i in range(5)])
ax.set_title("1. Quality Tier Distribution")
ax.legend()

ax = axs[0,1]
for n in names:
    fpr, tpr, auc = roc_curves[n]
    ax.plot(fpr, tpr, label=f"{n} (AUC={auc:.3f})")
ax.plot([0,1],[0,1], 'w--')
ax.set_title("2. Thermal Throttle ROC")
ax.legend()

ax = axs[1,0]
x = np.arange(len(names))
spreads = [results[n]['Q_Stats']['Spread'] for n in names]
ents = [results[n]['Q_Stats']['Entropy'] for n in names]
ax.bar(x - 0.2, spreads, 0.4, label='Q Spread')
ax.bar(x + 0.2, ents, 0.4, label='Policy Entropy')
ax.set_xticks(x)
ax.set_xticklabels(names, rotation=45)
ax.set_title("3. Policy Confidence")
ax.legend()

ax = axs[1,1]
cax = ax.imshow(agreement, cmap='viridis')
fig.colorbar(cax, ax=ax)
ax.set_xticks(np.arange(len(names)))
ax.set_yticks(np.arange(len(names)))
ax.set_xticklabels(names, rotation=45)
ax.set_yticklabels(names)
for i in range(len(names)):
    for j in range(len(names)):
        ax.text(j, i, f"{agreement[i,j]:.2f}", ha="center", va="center", color="w")
ax.set_title("4. Action Agreement")

plt.tight_layout()
plt.savefig('/home/kali/IQOO-Hackathom/cross_model_benchmark_proof.png', dpi=300)

with open('/home/kali/IQOO-Hackathom/scratch/benchmark_results.json', 'w') as f:
    json.dump(results, f, indent=2)
with open('/home/kali/IQOO-Hackathom/scratch/agreement_matrix.json', 'w') as f:
    json.dump({"names": names, "matrix": agreement.tolist()}, f, indent=2)

