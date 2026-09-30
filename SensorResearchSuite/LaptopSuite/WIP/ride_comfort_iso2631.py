#!/usr/bin/env python3
"""
Oppo A33w Vehicle & Transport Ride Comfort Evaluator (ISO 2631-1 Standard)
Computes frequency-weighted RMS vibration magnitude (awq in m/s²) from floor/seat
accelerometer recordings to evaluate passenger comfort and mechanical ride smoothness.
"""

import sys
import os
import argparse
import csv
import math

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required.")
    sys.exit(1)

def evaluate_ride_comfort(csv_path):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    print(f"Evaluating ISO 2631-1 Vehicle Ride Comfort on '{csv_path}'...")

    timestamps = []
    x_vals, y_vals, z_vals = [], [], []

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader, None)
        for row in reader:
            if len(row) >= 6:
                try:
                    stype = int(row[1])
                    if stype == 1: # Accelerometer
                        timestamps.append(float(row[0]))
                        x_vals.append(float(row[3]))
                        y_vals.append(float(row[4]))
                        z_vals.append(float(row[5]))
                except ValueError:
                    pass

    if not timestamps or len(timestamps) < 20:
        print("Not enough Accelerometer samples found.")
        return

    ts_arr = np.array(timestamps)
    xa, ya, za = np.array(x_vals), np.array(y_vals), np.array(z_vals)
    dt_ms = np.mean(np.diff(ts_arr))
    fs = 1000.0 / dt_ms if dt_ms > 0 else 100.0

    # Dynamic accelerations
    dx = xa - np.mean(xa)
    dy = ya - np.mean(ya)
    dz = za - np.mean(za)

    # ISO 2631 frequency weighting proxy (Human body most sensitive to vertical 4 - 8 Hz and horizontal 1 - 2 Hz)
    # We apply spectral weighting in Fourier domain
    def apply_iso_weighting_vertical(arr, fs):
        fft_v = np.fft.rfft(arr)
        freqs = np.fft.rfftfreq(len(arr), d=1.0/fs)
        weights = np.ones_like(freqs)
        # Peak sensitivity 4 - 8 Hz
        sensitive = (freqs >= 4.0) & (freqs <= 8.0)
        weights[sensitive] = 1.4
        weights[freqs > 20.0] = 0.2
        return np.fft.irfft(fft_v * weights, n=len(arr))

    def apply_iso_weighting_horizontal(arr, fs):
        fft_v = np.fft.rfft(arr)
        freqs = np.fft.rfftfreq(len(arr), d=1.0/fs)
        weights = np.ones_like(freqs)
        sensitive = (freqs >= 1.0) & (freqs <= 2.0)
        weights[sensitive] = 1.4
        weights[freqs > 10.0] = 0.2
        return np.fft.irfft(fft_v * weights, n=len(arr))

    wx = apply_iso_weighting_horizontal(dx, fs)
    wy = apply_iso_weighting_horizontal(dy, fs)
    wz = apply_iso_weighting_vertical(dz, fs)

    # Combined frequency-weighted acceleration vector magnitude (awv)
    awv = np.sqrt(wx**2 + wy**2 + wz**2)
    awq = np.sqrt(np.mean(awv**2))
    peak_factor = np.max(awv) / (awq + 1e-6)

    print("=" * 65)
    print(" OPPO A33w VEHICLE RIDE COMFORT REPORT (ISO 2631-1)")
    print("=" * 65)
    print(f"Recording Duration Evaluated    : {(ts_arr[-1]-ts_arr[0])/1000.0:.2f} seconds ({len(ts_arr)} samples)")
    print(f"Effective Sampling Frequency    : {fs:.1f} Hz")
    print("-" * 65)
    print(f"Weighted Vertical RMS (awz)     : {np.sqrt(np.mean(wz**2)):.4f} m/s²")
    print(f"Weighted Horizontal RMS (awxy)  : {np.sqrt(np.mean(wx**2 + wy**2)):.4f} m/s²")
    print(f"Overall Frequency-Weighted awq  : {awq:.4f} m/s² RMS")
    print(f"Crest Factor (Vibration Shock)  : {peak_factor:.2f}")
    print("-" * 65)
    
    if awq < 0.315: comfort = "Not Uncomfortable (Smooth Passenger Ride)"
    elif awq < 0.63: comfort = "A Little Uncomfortable"
    elif awq < 1.0: comfort = "Fairly Uncomfortable (Rough Road / Moderate Vibration)"
    elif awq < 1.6: comfort = "Uncomfortable"
    elif awq < 2.5: comfort = "Very Uncomfortable (Severe Mechanical Shocks)"
    else: comfort = "Extremely Uncomfortable / Hazardous Structural Exposure"
    
    print(f"ISO 2631 Comfort Classification : {comfort}")
    print("=" * 65)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Oppo A33w ISO 2631 Ride Comfort Tool")
    parser.add_argument("csv_file", help="Input vehicle/seat vibration CSV")
    args = parser.parse_args()
    evaluate_ride_comfort(args.csv_file)
