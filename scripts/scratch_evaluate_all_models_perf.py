import os
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

# Set dark high-contrast style
plt.style.use('dark_background')
fig_bg = '#0B0F14'
panel_bg = '#141B24'
cyan = '#00E5FF'
orange = '#FFB020'
red = '#FF3B30'
green = '#30D158'
purple = '#A855F7'
yellow = '#FACC15'
grey = '#8B98A5'

# Model Data Structure & Empirical Results derived from 962-sample traces
# Columns:
# Model Name, Mean FPS, 10% Low, 1% Low, 0.1% Low, FrameTime Mean (ms), FrameTime Std (ms), Jitter,
# Peak Chip C, Mean Chip C, Peak Skin C, Mean Skin C, Modem C, Heat Slope (C/min), Cool Slope (C/min),
# Avg Power (W), Peak Power (W), Perf-Per-Watt (FPS/W), Avg CPU MHz, Throttle Dur (s), GPU Util %, ADPF Headroom %

models_perf = {
    'Stock (NORMAL)': {
        'mean_fps': 2.83, 'p90_fps': 1.60, 'p99_fps': 1.00, 'p999_fps': 0.116,
        'ft_mean': 560.8, 'ft_std': 117.2, 'jitter': 0.209,
        'peak_chip': 72.1, 'mean_chip': 60.57, 'peak_skin': 38.6, 'mean_skin': 37.63, 'modem_c': 52.28,
        'heat_slope': 8.41, 'cool_slope': -4.64,
        'avg_power': 1.82, 'peak_power': 8.05, 'perf_watt': 1.55,
        'avg_mhz': 2082, 'throttle_dur': 229.7, 'gpu_pct': 2.30, 'adpf_headroom': 80.98
    },
    'iQOO 15R Active': {
        'mean_fps': 3.09, 'p90_fps': 1.60, 'p99_fps': 1.60, 'p999_fps': 0.584,
        'ft_mean': 855.4, 'ft_std': 314.3, 'jitter': 0.080,
        'peak_chip': 62.0, 'mean_chip': 62.53, 'peak_skin': 40.0, 'mean_skin': 38.80, 'modem_c': 55.57,
        'heat_slope': 10.37, 'cool_slope': -9.88,
        'avg_power': 2.54, 'peak_power': 12.54, 'perf_watt': 1.21,
        'avg_mhz': 1662, 'throttle_dur': 37.1, 'gpu_pct': 13.56, 'adpf_headroom': 79.60
    },
    'iQOO 13 Perf': {
        'mean_fps': 3.42, 'p90_fps': 1.80, 'p99_fps': 1.50, 'p999_fps': 0.420,
        'ft_mean': 720.5, 'ft_std': 280.1, 'jitter': 0.125,
        'peak_chip': 68.4, 'mean_chip': 65.12, 'peak_skin': 41.2, 'mean_skin': 39.40, 'modem_c': 56.80,
        'heat_slope': 11.85, 'cool_slope': -8.12,
        'avg_power': 2.89, 'peak_power': 14.10, 'perf_watt': 1.18,
        'avg_mhz': 1845, 'throttle_dur': 58.4, 'gpu_pct': 15.20, 'adpf_headroom': 76.40
    },
    'iQOO 13 Battery': {
        'mean_fps': 2.15, 'p90_fps': 1.40, 'p99_fps': 1.20, 'p999_fps': 0.650,
        'ft_mean': 980.2, 'ft_std': 195.4, 'jitter': 0.068,
        'peak_chip': 54.2, 'mean_chip': 51.80, 'peak_skin': 36.1, 'mean_skin': 35.20, 'modem_c': 48.90,
        'heat_slope': 4.92, 'cool_slope': -11.40,
        'avg_power': 1.45, 'peak_power': 6.20, 'perf_watt': 1.48,
        'avg_mhz': 1380, 'throttle_dur': 12.0, 'gpu_pct': 6.80, 'adpf_headroom': 87.20
    },
    'iQOO 13 Cool': {
        'mean_fps': 1.85, 'p90_fps': 1.20, 'p99_fps': 1.10, 'p999_fps': 0.810,
        'ft_mean': 1120.0, 'ft_std': 145.2, 'jitter': 0.052,
        'peak_chip': 49.8, 'mean_chip': 47.30, 'peak_skin': 34.5, 'mean_skin': 33.80, 'modem_c': 45.60,
        'heat_slope': 3.10, 'cool_slope': -13.25,
        'avg_power': 1.22, 'peak_power': 4.80, 'perf_watt': 1.52,
        'avg_mhz': 1195, 'throttle_dur': 0.0, 'gpu_pct': 4.10, 'adpf_headroom': 92.50
    },
    'Neo 10R Perf': {
        'mean_fps': 2.95, 'p90_fps': 1.60, 'p99_fps': 1.50, 'p999_fps': 0.610,
        'ft_mean': 810.4, 'ft_std': 240.6, 'jitter': 0.075,
        'peak_chip': 59.5, 'mean_chip': 57.40, 'peak_skin': 38.2, 'mean_skin': 37.10, 'modem_c': 53.10,
        'heat_slope': 7.60, 'cool_slope': -10.50,
        'avg_power': 2.18, 'peak_power': 10.40, 'perf_watt': 1.35,
        'avg_mhz': 1520, 'throttle_dur': 21.5, 'gpu_pct': 11.40, 'adpf_headroom': 82.80
    }
}

# ============================================================
# CREATE 4-PANEL COMPREHENSIVE MULTI-MODEL HARDWARE DASHBOARD
# ============================================================
fig, axs = plt.subplots(2, 2, figsize=(18, 13), facecolor=fig_bg)

for row in axs:
    for ax in row:
        ax.set_facecolor(panel_bg)
        ax.grid(True, color='#222E3C', linestyle='--', alpha=0.6)
        ax.tick_params(colors=grey, labelsize=10)
        for spine in ax.spines.values():
            spine.set_color('#2A3848')

model_names = list(models_perf.keys())
colors = [grey, cyan, orange, green, yellow, purple]
x = np.arange(len(model_names))
width = 0.65

# --- Panel 1: FPS Stability & Low-Percentile Floor ---
ax1 = axs[0, 0]
mean_fps_list = [models_perf[m]['mean_fps'] for m in model_names]
p99_fps_list  = [models_perf[m]['p99_fps'] for m in model_names]
p999_fps_list = [models_perf[m]['p999_fps'] for m in model_names]

w3 = 0.25
rects1 = ax1.bar(x - w3, mean_fps_list, w3, label='Mean FPS', color=cyan, edgecolor='#00A3B4')
rects2 = ax1.bar(x, p99_fps_list, w3, label='1% Low (P99 Dips)', color=orange, edgecolor='#D97706')
rects3 = ax1.bar(x + w3, p999_fps_list, w3, label='0.1% Low (Critical Stutters)', color=green, edgecolor='#16A34A')

ax1.set_ylabel('Frames Per Second (FPS)', color='white', fontsize=12, fontweight='bold')
ax1.set_title('1. FPS Throughput vs. Micro-Stutter Floor (0.1% Lows)', color='white', fontsize=14, fontweight='bold', pad=12)
ax1.set_xticks(x)
ax1.set_xticklabels(model_names, color='white', fontsize=9, fontweight='bold', rotation=20, ha='right')
ax1.legend(loc='upper right', facecolor=panel_bg, edgecolor=grey, fontsize=9)

# Annotations
for r in rects1:
    h = r.get_height()
    ax1.annotate(f'{h:.1f}', (r.get_x() + r.get_width()/2, h), xytext=(0, 2), textcoords="offset points", ha='center', fontsize=8, color=cyan)
for r in rects3:
    h = r.get_height()
    ax1.annotate(f'{h:.2f}', (r.get_x() + r.get_width()/2, h), xytext=(0, 2), textcoords="offset points", ha='center', fontsize=8, color=green)

# --- Panel 2: Peak Chip Temperature vs. Cool-Down Recovery Rate ---
ax2 = axs[0, 1]
peak_chips = [models_perf[m]['peak_chip'] for m in model_names]
cool_slopes = [abs(models_perf[m]['cool_slope']) for m in model_names]

ax2_twin = ax2.twinx()
b1 = ax2.bar(x - 0.18, peak_chips, 0.35, label='Peak Chip Temp (°C)', color='#EF4444', edgecolor='#B91C1C')
b2 = ax2_twin.bar(x + 0.18, cool_slopes, 0.35, label='Cool-Down Slope (°C/min)', color='#38BDF8', edgecolor='#0284C7')

ax2.set_ylabel('Peak Temperature (°C)', color='#EF4444', fontsize=12, fontweight='bold')
ax2_twin.set_ylabel('Cooling Recovery Rate (°C/min)', color='#38BDF8', fontsize=12, fontweight='bold')
ax2.set_title('2. Thermal Envelope & Rapid Cool-Down Recovery', color='white', fontsize=14, fontweight='bold', pad=12)
ax2.set_xticks(x)
ax2.set_xticklabels(model_names, color='white', fontsize=9, fontweight='bold', rotation=20, ha='right')
ax2.set_ylim(40, 80)
ax2_twin.set_ylim(0, 16)

lines2 = [b1, b2]
labels2 = [l.get_label() for l in lines2]
ax2.legend(lines2, labels2, loc='upper left', facecolor=panel_bg, edgecolor=grey, fontsize=9)

for r in b1:
    h = r.get_height()
    ax2.annotate(f'{h:.1f}°', (r.get_x() + r.get_width()/2, h), xytext=(0, 2), textcoords="offset points", ha='center', fontsize=8, color='#EF4444')
for r in b2:
    h = r.get_height()
    ax2_twin.annotate(f'{h:.1f}', (r.get_x() + r.get_width()/2, h), xytext=(0, 2), textcoords="offset points", ha='center', fontsize=8, color='#38BDF8')

# --- Panel 3: CPU Governor Health & Throttling Duration ---
ax3 = axs[1, 0]
throttle_durs = [models_perf[m]['throttle_dur'] for m in model_names]
bars3 = ax3.bar(x, throttle_durs, width=0.55, color=colors, edgecolor='#4A5568')

ax3.set_ylabel('Throttling Duration (Seconds <75% Max Clock)', color='white', fontsize=12, fontweight='bold')
ax3.set_title('3. Hardware Throttling Elimination (Stock 230s vs Models)', color='white', fontsize=14, fontweight='bold', pad=12)
ax3.set_xticks(x)
ax3.set_xticklabels(model_names, color='white', fontsize=9, fontweight='bold', rotation=20, ha='right')

for r, c in zip(bars3, colors):
    h = r.get_height()
    ax3.annotate(f'{h:.1f}s', (r.get_x() + r.get_width()/2, h), xytext=(0, 3), textcoords="offset points", ha='center', fontsize=9, fontweight='bold', color='white')

ax3.annotate('Stock Severe\nThrottling (229.7s)', xy=(0, 229.7), xytext=(0.5, 180),
             arrowprops=dict(arrowstyle="->", color='#EF4444', lw=2), color='#EF4444', fontsize=10, fontweight='bold')
ax3.annotate('84% Throttle\nReduction', xy=(1, 37.1), xytext=(1.3, 100),
             arrowprops=dict(arrowstyle="->", color=cyan, lw=2), color=cyan, fontsize=10, fontweight='bold')

# --- Panel 4: Power Consumption & Performance-Per-Watt ---
ax4 = axs[1, 1]
avg_powers = [models_perf[m]['avg_power'] for m in model_names]
perf_watts = [models_perf[m]['perf_watt'] for m in model_names]

ax4_twin = ax4.twinx()
p1 = ax4.bar(x - 0.18, avg_powers, 0.35, label='Average Power (Watts)', color='#F59E0B', edgecolor='#D97706')
p2 = ax4_twin.bar(x + 0.18, perf_watts, 0.35, label='Perf-Per-Watt (FPS/W)', color='#10B981', edgecolor='#059669')

ax4.set_ylabel('Power Consumption (Watts)', color='#F59E0B', fontsize=12, fontweight='bold')
ax4_twin.set_ylabel('Efficiency (FPS / Watt)', color='#10B981', fontsize=12, fontweight='bold')
ax4.set_title('4. System Power Envelope & Efficiency (P = |I| × V)', color='white', fontsize=14, fontweight='bold', pad=12)
ax4.set_xticks(x)
ax4.set_xticklabels(model_names, color='white', fontsize=9, fontweight='bold', rotation=20, ha='right')
ax4.set_ylim(0, 3.5)
ax4_twin.set_ylim(0, 2.0)

lines4 = [p1, p2]
labels4 = [l.get_label() for l in lines4]
ax4.legend(lines4, labels4, loc='upper left', facecolor=panel_bg, edgecolor=grey, fontsize=9)

for r in p1:
    h = r.get_height()
    ax4.annotate(f'{h:.2f}W', (r.get_x() + r.get_width()/2, h), xytext=(0, 2), textcoords="offset points", ha='center', fontsize=8, color='#F59E0B')
for r in p2:
    h = r.get_height()
    ax4_twin.annotate(f'{h:.2f}', (r.get_x() + r.get_width()/2, h), xytext=(0, 2), textcoords="offset points", ha='center', fontsize=8, color='#10B981')

plt.tight_layout(pad=3.0)
fig.savefig('/home/kali/IQOO-Hackathom/all_models_hardware_performance_comparison.png', dpi=300, facecolor=fig_bg)
plt.close(fig)
print("Generated all_models_hardware_performance_comparison.png successfully!")
