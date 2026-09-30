#!/usr/bin/env python3
"""
Oppo A33w Ballistocardiography (BCG) & Mechanocardiography Heart Rate Estimator
Isolates micro-vibrations produced by cardiac ejections (0.8 - 2.5 Hz / 48 - 150 BPM)
from accelerometer recordings taken while resting on the chest or wrist to compute
Heart Rate (BPM) and Heart Rate Variability (RMSSD in ms).
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

def bandpass_filter_bcg(data, fs=100.0, lowcut=0.8, highcut=2.5):
    """Zero-phase frequency band extraction using Fourier domain filtering."""
    n = len(data)
    fft_v = np.fft.rfft(data)
    freqs = np.fft.rfftfreq(n, d=1.0/fs)
    
    # Mask frequencies outside heart rate band (48 - 150 BPM)
    mask = (freqs >= lowcut) & (freqs <= highcut)
    fft_v[~mask] = 0.0
    return np.fft.irfft(fft_v, n=n)

def estimate_heart_rate(csv_path):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    print(f"Performing Ballistocardiography (BCG) Cardiac Analysis on '{csv_path}'...")

    timestamps = []
    acc_z = []

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader, None)
        for row in reader:
            if len(row) >= 6:
                try:
                    stype = int(row[1])
                    if stype == 1: # Accelerometer
                        ts = float(row[0])
                        vx, vy, vz = float(row[3]), float(row[4]), float(row[5])
                        timestamps.append(ts)
                        acc_z.append(vz) # Primary chest recoil axis
                except ValueError:
                    pass

    if not timestamps or len(timestamps) < 100:
        print("Not enough samples for cardiac extraction (need at least 3-5 seconds of resting data).")
        return

    ts_arr = np.array(timestamps)
    za = np.array(acc_z)
    dt_ms = np.mean(np.diff(ts_arr))
    fs = 1000.0 / dt_ms if dt_ms > 0 else 100.0

    # Filter into cardiac ejection band (0.8 - 2.5 Hz)
    bcg_signal = bandpass_filter_bcg(za - np.mean(za), fs, lowcut=0.8, highcut=2.5)

    # Peak finding in frequency spectrum
    fft_bcg = np.abs(np.fft.rfft(bcg_signal))
    freqs = np.fft.rfftfreq(len(bcg_signal), d=1.0/fs)
    valid_mask = (freqs >= 0.8) & (freqs <= 2.5)
    
    if np.any(valid_mask):
        best_idx = np.argmax(fft_bcg * valid_mask)
        dom_freq = freqs[best_idx]
        estimated_bpm = dom_freq * 60.0
    else:
        estimated_bpm = 72.0

    # Time-domain J-wave beat detection
    std_bcg = np.std(bcg_signal)
    threshold = max(1e-4, 0.6 * std_bcg)
    beat_times = []
    last_beat_t = 0

    for i in range(1, len(bcg_signal) - 1):
        if bcg_signal[i] > threshold and bcg_signal[i] > bcg_signal[i-1] and bcg_signal[i] > bcg_signal[i+1]:
            if (ts_arr[i] - last_beat_t) >= 400.0: # Max 150 BPM
                beat_times.append(ts_arr[i])
                last_beat_t = ts_arr[i]

    if len(beat_times) >= 3:
        rr_intervals = np.diff(beat_times)
        mean_rr = np.mean(rr_intervals)
        td_bpm = 60000.0 / mean_rr if mean_rr > 0 else estimated_bpm
        # RMSSD Heart Rate Variability
        rmssd = np.sqrt(np.mean(np.diff(rr_intervals)**2)) if len(rr_intervals)>=2 else 25.0
    else:
        td_bpm = estimated_bpm
        rmssd = 0.0

    print("=" * 65)
    print(" OPPO A33w BALLISTOCARDIOGRAPHY (BCG) CARDIAC REPORT")
    print("=" * 65)
    print(f"Resting Recording Examined      : {len(ts_arr)} samples ({(ts_arr[-1]-ts_arr[0])/1000:.1f} sec)")
    print(f"Frequency-Domain Cardiac Peak   : {dom_freq:.2f} Hz ({estimated_bpm:.1f} BPM)")
    print(f"Time-Domain Detected J-Waves    : {len(beat_times)} cardiac ejections")
    print("-" * 65)
    print(f"ESTIMATED RESTING HEART RATE    : {(estimated_bpm+td_bpm)/2.0:.1f} BPM")
    print(f"Heart Rate Variability (RMSSD)  : {rmssd:.1f} ms")
    
    if rmssd > 20.0: hrv_grade = "Healthy Autonomic Nervous System Tone"
    elif rmssd > 10.0: hrv_grade = "Moderate Resting Tone"
    else: hrv_grade = "Low HRV / High Sympathetic Stress or Motion Artifact"
    print(f"Physiological Assessment        : {hrv_grade}")
    print("=" * 65)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Oppo A33w Ballistocardiography Estimator")
    parser.add_argument("csv_file", help="Input resting chest/wrist recording CSV")
    args = parser.parse_args()
    estimate_heart_rate(args.csv_file)
