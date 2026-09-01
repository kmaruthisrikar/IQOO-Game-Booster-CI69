import pandas as pd
import numpy as np
import json

df = pd.read_csv('/home/kali/IQOO-Hackathom/gamemode_live.csv')

def get_slopes(subset):
    if len(subset) < 2: return 0, 0
    
    # Smooth temperature
    temp = subset['chipC'].rolling(window=10, min_periods=1).mean()
    time_min = subset['t_ms'] / 60000.0
    
    dt = time_min.diff().fillna(0)
    dtemp = temp.diff().fillna(0)
    
    slopes = np.where(dt > 0, dtemp / dt, 0)
    
    heating = slopes[slopes > 0]
    cooling = slopes[slopes < 0]
    
    heat_slope = np.mean(heating) if len(heating) > 0 else 0
    cool_slope = np.mean(cooling) if len(cooling) > 0 else 0
    return heat_slope, cool_slope

normal = df[df['action'] == -1]
model_df = df[df['action'] != -1]

nh, nc = get_slopes(normal)
mh, mc = get_slopes(model_df)

with open('scratch/metrics2.json', 'r') as f:
    metrics = json.load(f)

metrics['NORMAL_Thermal']['Heat_Slope'] = nh
metrics['NORMAL_Thermal']['Cool_Slope'] = nc
metrics['MODEL_Thermal']['Heat_Slope'] = mh
metrics['MODEL_Thermal']['Cool_Slope'] = mc

# Hardware Governor Throttling
# Throttling duration: seconds spent with freq < 75% nominal max
nom_max = df['nomMaxMhz'].iloc[0] if len(df) > 0 else 3442
metrics['NORMAL_Gov'] = {
    'Avg_Freq': normal['freqMhz'].mean(),
    'Throttle_Sec': len(normal[normal['freqMhz'] < 0.75 * nom_max]) * (normal['t_ms'].diff().mean() / 1000.0) if len(normal) > 1 else 0,
    'GPU_Pct': normal['gpuPct'].mean()
}
metrics['MODEL_Gov'] = {
    'Avg_Freq': model_df['freqMhz'].mean(),
    'Throttle_Sec': len(model_df[model_df['freqMhz'] < 0.75 * nom_max]) * (model_df['t_ms'].diff().mean() / 1000.0) if len(model_df) > 1 else 0,
    'GPU_Pct': model_df['gpuPct'].mean()
}

with open('scratch/metrics3.json', 'w') as f:
    json.dump(metrics, f, indent=2)
print("Done")
