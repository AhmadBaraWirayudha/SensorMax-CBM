#!/usr/bin/env python3
"""
Oppo A33w Motion & Activity Recognition Engine
Analyzes raw Accelerometer streams to perform:
1. Dynamic Peak Detection for Step Counting & Cadence (steps/minute).
2. Motion State Classification (Stationary, Light Activity / Walking, Vigorous Activity / Running).
3. Signal Energy & Vibration Intensity Analysis.
"""

import sys
import os
import argparse
import csv

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required. Run 'pip install numpy'")
    sys.exit(1)

def analyze_motion(csv_path):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    timestamps = []
    magnitudes = []

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
                        mag = math.sqrt(vx**vx if vx>1e-5 else vx*vx + vy*vy + vz*vz)
                        # Standard magnitude
                        mag = np.sqrt(vx**2 + vy**2 + vz**2)
                        timestamps.append(ts)
                        magnitudes.append(mag)
                except ValueError:
                    pass

    if not timestamps:
        print("No Accelerometer samples found in dataset.")
        return

    ts_arr = np.array(timestamps)
    mag_arr = np.array(magnitudes)
    duration_sec = (ts_arr[-1] - ts_arr[0]) / 1000.0

    # Remove DC gravity component (9.8 m/s^2 approx)
    dynamic_acc = mag_arr - np.mean(mag_arr)
    rms_vibration = np.sqrt(np.mean(dynamic_acc**2))
    peak_to_peak = np.max(mag_arr) - np.min(mag_arr)

    # Step Detection via dynamic threshold peak finding
    std_acc = np.std(dynamic_acc)
    threshold = max(0.4, 0.8 * std_acc)
    
    steps = 0
    last_step_time = 0
    min_step_interval_ms = 250.0 # Maximum 4 steps per second (~240 SPM)

    for i in range(1, len(dynamic_acc) - 1):
        # Local maximum above threshold
        if dynamic_acc[i] > threshold and dynamic_acc[i] > dynamic_acc[i-1] and dynamic_acc[i] > dynamic_acc[i+1]:
            if (ts_arr[i] - last_step_time) >= min_step_interval_ms:
                steps += 1
                last_step_time = ts_arr[i]

    cadence = (steps / duration_sec) * 60.0 if duration_sec > 0 else 0.0

    # Classify activity state
    if rms_vibration < 0.15:
        activity_state = "STATIONARY / RESTING"
    elif rms_vibration < 1.2:
        activity_state = "LIGHT ACTIVITY / WALKING"
    elif rms_vibration < 3.5:
        activity_state = "MODERATE TO VIGOROUS ACTIVITY / RUNNING"
    else:
        activity_state = "HIGH-INTENSITY MECHANICAL VIBRATION / SHOCK"

    print("=" * 65)
    print(" OPPO A33w MOTION & ACTIVITY RECOGNITION REPORT")
    print("=" * 65)
    print(f"Dataset Analyzed     : {os.path.basename(csv_path)}")
    print(f"Duration             : {duration_sec:.2f} seconds ({len(ts_arr)} samples)")
    print(f"Classified State     : {activity_state}")
    print("-" * 65)
    print(f"Dynamic RMS Energy   : {rms_vibration:.4f} m/s²")
    print(f"Peak-to-Peak Range   : {peak_to_peak:.4f} m/s²")
    print(f"Detected Steps       : {steps} steps")
    print(f"Estimated Cadence    : {cadence:.1f} steps/min")
    print("=" * 65)

if __name__ == "__main__":
    import math
    parser = argparse.ArgumentParser(description="Oppo A33w Motion Recognition Suite")
    parser.add_argument("csv_file", help="Input Accelerometer CSV dataset")
    args = parser.parse_args()
    analyze_motion(args.csv_file)
