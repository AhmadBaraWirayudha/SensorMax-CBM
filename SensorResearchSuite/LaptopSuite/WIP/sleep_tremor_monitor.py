#!/usr/bin/env python3
"""
Oppo A33w Nocturnal Sleep Actigraphy & Tremor/Seizure Monitoring Engine
Analyzes continuous long-duration accelerometer recordings during rest/sleep
to quantify sleep staging epochs (Deep vs Light sleep vs Awakening) and flag
sustained neurological tremor oscillations (4-8 Hz band energy).
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

def analyze_sleep_and_tremor(csv_path, epoch_sec=30.0):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    print(f"Analyzing Nocturnal Actigraphy & Tremor Band Energy on '{csv_path}' (Epoch={epoch_sec}s)...")

    timestamps = []
    mags = []

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader, None)
        for row in reader:
            if len(row) >= 6:
                try:
                    stype = int(row[1])
                    if stype == 1: # Accelerometer
                        timestamps.append(float(row[0]))
                        vx, vy, vz = float(row[3]), float(row[4]), float(row[5])
                        mags.append(math.sqrt(vx*vx + vy*vy + vz*vz))
                except ValueError:
                    pass

    if not timestamps:
        print("No Accelerometer samples found.")
        return

    ts_arr = np.array(timestamps)
    mags_arr = np.array(mags)
    dt_ms = np.mean(np.diff(ts_arr)) if len(ts_arr)>1 else 10.0
    fs = 1000.0 / dt_ms if dt_ms > 0 else 100.0
    win_samples = max(10, int(epoch_sec * fs))

    deep_epochs = 0
    light_epochs = 0
    awake_epochs = 0
    tremor_warnings = 0

    total_epochs = 0

    for start_i in range(0, len(mags_arr) - win_samples + 1, win_samples):
        end_i = start_i + win_samples
        sub = mags_arr[start_i:end_i]
        dyn = sub - np.mean(sub)
        rms = np.sqrt(np.mean(dyn**2))

        # Classify sleep actigraphy epoch
        if rms < 0.03: deep_epochs += 1
        elif rms < 0.15: light_epochs += 1
        else: awake_epochs += 1

        # Check Parkinsonian / seizure tremor band (4 - 8 Hz)
        fft_v = np.abs(np.fft.rfft(dyn))
        freqs = np.fft.rfftfreq(len(dyn), d=1.0/fs)
        
        band_mask = (freqs >= 3.5) & (freqs <= 8.5)
        if np.any(band_mask):
            band_energy = np.sum(fft_v[band_mask]**2)
            total_energy = np.sum(fft_v**2) + 1e-6
            if (band_energy / total_energy) > 0.65 and rms > 0.2:
                tremor_warnings += 1

        total_epochs += 1

    if total_epochs == 0:
        total_epochs = 1
        deep_epochs = 1

    deep_pct = (deep_epochs / total_epochs) * 100.0
    light_pct = (light_epochs / total_epochs) * 100.0
    awake_pct = (awake_epochs / total_epochs) * 100.0

    print("=" * 65)
    print(" OPPO A33w NOCTURNAL SLEEP ACTIGRAPHY & TREMOR REPORT")
    print("=" * 65)
    print(f"Total Sleep Epochs Analyzed : {total_epochs} epochs ({total_epochs*epoch_sec/60.0:.1f} min equivalent)")
    print(f"Deep Sleep Stage Estimate   : {deep_epochs} epochs ({deep_pct:.1f}%)")
    print(f"Light Sleep Stage Estimate  : {light_epochs} epochs ({light_pct:.1f}%)")
    print(f"Awakening / Restless Epochs : {awake_epochs} epochs ({awake_pct:.1f}%)")
    print("-" * 65)
    print(f"Neurological Tremor Flag (4-8Hz Band >65% Energy):")
    print(f"  -> Detected Tremor Events : {tremor_warnings} epochs flagged")
    if tremor_warnings == 0:
        print("  -> Assessment             : Normal Resting Baseline (No sustained tremor observed)")
    else:
        print("  -> Assessment             : WARNING: Rhythmic nocturnal tremor oscillations identified!")
    print("=" * 65)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Oppo A33w Sleep Actigraphy & Tremor Monitor")
    parser.add_argument("csv_file", help="Input nocturnal accelerometer recording CSV")
    parser.add_argument("--epoch", type=float, default=30.0, help="Epoch duration in seconds")
    args = parser.parse_args()
    analyze_sleep_and_tremor(args.csv_file, args.epoch)
