import pandas as pd
import numpy as np
import json
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.metrics import mean_absolute_error, mean_squared_error, precision_score, recall_score, f1_score, roc_auc_score

df = pd.read_csv('/home/kali/IQOO-Hackathom/gamemode_live.csv')

# Prepare data for thermal transition prediction
df['next_chipC'] = df['chipC'].shift(-1)
df.dropna(inplace=True)

X = df[['skinC', 'freqMhz', 'gpuPct', 'headroomPct', 'fps', 'battCurMa', 'battMv']].values
y_temp = df['next_chipC'].values

# Thermal Transition Error
lr = LinearRegression()
lr.fit(X, y_temp)
y_pred_temp = lr.predict(X)
mae = mean_absolute_error(y_temp, y_pred_temp)
rmse = np.sqrt(mean_squared_error(y_temp, y_pred_temp))

# Thermal Throttle Classification (exceeds 55C)
y_class = (df['next_chipC'] > 55.0).astype(int)
clf = LogisticRegression(max_iter=1000)
clf.fit(X, y_class)
y_pred_class = clf.predict(X)
y_pred_proba = clf.predict_proba(X)[:, 1]

precision = precision_score(y_class, y_pred_class)
recall = recall_score(y_class, y_pred_class)
f1 = f1_score(y_class, y_pred_class)
roc_auc = roc_auc_score(y_class, y_pred_proba)

with open('scratch/metrics.json', 'r') as f:
    metrics = json.load(f)

metrics['Thermal_Prediction'] = {
    'MAE': mae,
    'RMSE': rmse,
    'Precision': precision,
    'Recall': recall,
    'F1': f1,
    'ROC_AUC': roc_auc
}

with open('scratch/metrics2.json', 'w') as f:
    json.dump(metrics, f, indent=2)

print("Done")
