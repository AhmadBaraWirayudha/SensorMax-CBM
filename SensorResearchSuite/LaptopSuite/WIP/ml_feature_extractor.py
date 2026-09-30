#!/usr/bin/env python3
"""
Oppo A33w Machine Learning Feature Extraction Engine
Splits multi-axis sensor recordings into sliding time windows (epochs)
and extracts time-domain & frequency-domain feature matrices for AI/ML classification.
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

def compute_window_features(arr, fs=100.0):
    """Computes time and frequency domain features for a 1D array."""
    if len(arr) < 4:
        return [0.0]*9
    
    mean_val = np.mean(arr)
    std_val = np.std(arr)
    min_val = np.min(arr)
    max_val = np.max(arr)
    p2p = max_val - min_val
    rms = np.sqrt(np.mean(arr**2))
    
    # Zero crossing rate around mean
    centered = arr - mean_val
    zcr = np.sum(np.diff(np.sign(centered)) != 0) / float(len(arr))

    # Frequency domain
    fft_vals = np.abs(np.fft.rfft(centered))
    freqs = np.fft.rfftfreq(len(arr), d=1.0/fs)
    dom_freq = freqs[np.argmax(fft_vals)] if len(fft_vals)>0 else 0.0
    
    # Spectral energy
    energy = np.sum(fft_vals**2) / float(len(arr))

    return [mean_val, std_val, min_val, max_val, p2p, rms, zcr, dom_freq, energy]

def extract_features(csv_path, window_sec=2.0, overlap=0.5, output_csv="extracted_ml_features.csv"):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    print(f"Extracting ML Feature Matrix from '{csv_path}' (Window: {window_sec}s, Overlap: {int(overlap*100)}%)...")

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

    if not timestamps:
        print("No Accelerometer samples found.")
        return

    ts_arr = np.array(timestamps)
    x_arr = np.array(x_vals)
    y_arr = np.array(y_vals)
    z_arr = np.array(z_vals)

    dt_ms = np.mean(np.diff(ts_arr)) if len(ts_arr)>1 else 10.0
    fs = 1000.0 / dt_ms
    win_samples = int(window_sec * fs)
    step_samples = max(1, int(win_samples * (1.0 - overlap)))

    feature_headers = [
        "Epoch_Start_ms", "Epoch_End_ms",
        "X_Mean", "X_Std", "X_Min", "X_Max", "X_P2P", "X_RMS", "X_ZCR", "X_DomFreq", "X_Energy",
        "Y_Mean", "Y_Std", "Y_Min", "Y_Max", "Y_P2P", "Y_RMS", "Y_ZCR", "Y_DomFreq", "Y_Energy",
        "Z_Mean", "Z_Std", "Z_Min", "Z_Max", "Z_P2P", "Z_RMS", "Z_ZCR", "Z_DomFreq", "Z_Energy"
    ]

    rows_out = [feature_headers]
    epoch_count = 0

    for start_idx in range(0, len(x_arr) - win_samples + 1, step_samples):
        end_idx = start_idx + win_samples
        epoch_ts_start = ts_arr[start_idx]
        epoch_ts_end = ts_arr[end_idx - 1]

        fx = compute_window_features(x_arr[start_idx:end_idx], fs)
        fy = compute_window_features(y_arr[start_idx:end_idx], fs)
        fz = compute_window_features(z_arr[start_idx:end_idx], fs)

        formatted = [round(epoch_ts_start, 1), round(epoch_ts_end, 1)]
        formatted.extend([round(val, 4) for val in fx + fy + fz])
        rows_out.append(formatted)
        epoch_count += 1

    with open(output_csv, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerows(rows_out)

    print(f"ML Feature Extraction Complete! Computed {epoch_count} window epochs -> {output_csv}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Oppo A33w ML Feature Extractor")
    parser.add_argument("csv_file", help="Input Accelerometer CSV dataset")
    parser.add_argument("--window", type=float, default=2.0, help="Window epoch size in seconds")
    parser.add_argument("--overlap", type=float, default=0.5, help="Overlap ratio (0.0 to 0.9)")
    parser.add_argument("--output", default="extracted_ml_features.csv", help="Output features CSV path")
    args = parser.parse_args()
    extract_features(args.csv_file, args.window, args.overlap, args.output)
