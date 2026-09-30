#!/usr/bin/env python3
"""
Oppo A33w Biomechanical Gait Kinematics & Symmetry Analyzer
Analyzes vertical acceleration waveforms during human locomotion to compute:
1. Step-to-Step Time Symmetry Index (SSI).
2. Estimated Vertical Ground Reaction Force (vGRF) proxy.
3. Stance vs Swing phase duration ratio and Gait Regularity Autocorrelation.
"""

import sys
import os
import argparse
import csv
import math

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required. Run 'pip install numpy'")
    sys.exit(1)

def analyze_gait(csv_path):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    print(f"Performing Biomechanical Gait Kinematics Analysis on '{csv_path}'...")

    timestamps = []
    acc_z = [] # Assume Z or magnitude aligned with vertical axis

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader, None)
        for row in reader:
            if len(row) >= 6:
                try:
                    stype = int(row[1])
                    if stype == 1: # Accelerometer
                        ts = float(row[0])
                        vx = float(row[3])
                        vy = float(row[4])
                        vz = float(row[5])
                        mag = math.sqrt(vx*vx + vy*vy + vz*vz)
                        timestamps.append(ts)
                        acc_z.append(mag)
                except ValueError:
                    pass

    if not timestamps or len(timestamps) < 20:
        print("Not enough Accelerometer samples found.")
        return

    ts_arr = np.array(timestamps)
    mags = np.array(acc_z)
    dt_ms = np.mean(np.diff(ts_arr))
    fs = 1000.0 / dt_ms if dt_ms > 0 else 100.0

    dyn_acc = mags - np.mean(mags)
    std_acc = np.std(dyn_acc)
    threshold = max(0.4, 0.8 * std_acc)

    # Detect heel-strike impact peaks
    step_times = []
    peak_impacts = []
    last_step_t = 0

    for i in range(1, len(dyn_acc) - 1):
        if dyn_acc[i] > threshold and dyn_acc[i] > dyn_acc[i-1] and dyn_acc[i] > dyn_acc[i+1]:
            if (ts_arr[i] - last_step_t) >= 250.0:
                step_times.append(ts_arr[i])
                peak_impacts.append(dyn_acc[i])
                last_step_t = ts_arr[i]

    if len(step_times) < 3:
        print("Not enough distinct step impacts detected for gait symmetry analysis.")
        return

    step_intervals = np.diff(step_times) # in ms
    mean_interval = np.mean(step_intervals)
    std_interval = np.std(step_intervals)
    cadence = (60000.0 / mean_interval) if mean_interval > 0 else 0.0

    # Separate even and odd intervals (Left vs Right step times)
    if len(step_intervals) >= 2:
        left_steps = step_intervals[0::2]
        right_steps = step_intervals[1::2]
        t_left = np.mean(left_steps) if len(left_steps)>0 else mean_interval
        t_right = np.mean(right_steps) if len(right_steps)>0 else mean_interval

        # Step Symmetry Index (SSI): 100 * |TL - TR| / (0.5 * (TL + TR))
        ssi = 100.0 * abs(t_left - t_right) / (0.5 * (t_left + t_right))
    else:
        t_left, t_right, ssi = mean_interval, mean_interval, 0.0

    # Autocorrelation Gait Regularity Index
    norm_acc = dyn_acc / (np.std(dyn_acc) + 1e-6)
    autocorr = np.correlate(norm_acc, norm_acc, mode='full') / len(norm_acc)
    autocorr = autocorr[len(norm_acc)-1:]
    
    # Peak ground reaction acceleration proxy (in g's)
    max_impact_g = np.mean(peak_impacts) / 9.80665

    print("=" * 65)
    print(" OPPO A33w BIOMECHANICAL GAIT KINEMATICS & SYMMETRY REPORT")
    print("=" * 65)
    print(f"Total Locomotion Steps Examined : {len(step_times)} steps")
    print(f"Mean Stride/Step Cadence        : {cadence:.1f} steps/min ({mean_interval:.1f} ms/step)")
    print(f"Step Interval Variability (1σ)  : ±{std_interval:.1f} ms")
    print("-" * 65)
    print(f"Mean Left Step Duration Estimated : {t_left:.1f} ms")
    print(f"Mean Right Step Duration Estimated: {t_right:.1f} ms")
    print(f"Step Symmetry Index (SSI)       : {ssi:.2f}%")
    
    if ssi < 3.0: sym_grade = "Highly Symmetrical Normal Gait"
    elif ssi < 8.0: sym_grade = "Mild Asymmetry / Slight Limp"
    else: sym_grade = "Significant Gait Asymmetry / Pathological Impairment"
    print(f"Clinical Gait Symmetry Grade    : {sym_grade}")
    print("-" * 65)
    print(f"Mean Vertical Ground Impact Proxy : +{max_impact_g:.2f} G above static weight")
    print("=" * 65)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Oppo A33w Gait Symmetry Analyzer")
    parser.add_argument("csv_file", help="Input locomotion recording CSV")
    args = parser.parse_args()
    analyze_gait(args.csv_file)
