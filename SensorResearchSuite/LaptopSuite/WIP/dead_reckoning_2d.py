#!/usr/bin/env python3
"""
Oppo A33w Pedestrian Dead Reckoning (PDR) 2D Trajectory Reconstruction Engine
Combines step detection from Accelerometer with heading estimation (Yaw angle)
from Gyroscope/Magnetometer to reconstruct 2D walking trajectories (X, Y in meters).
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

def reconstruct_trajectory(csv_path, stride_length_m=0.7, output_csv="pdr_trajectory_2d.csv"):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    print(f"Reconstructing 2D Pedestrian Dead Reckoning (PDR) Trajectory from '{csv_path}'...")

    timestamps = []
    acc_mags = []
    gyro_z = []

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader, None)
        for row in reader:
            if len(row) >= 6:
                try:
                    ts = float(row[0])
                    stype = int(row[1])
                    vx = float(row[3])
                    vy = float(row[4])
                    vz = float(row[5])

                    if stype == 1: # Accelerometer
                        timestamps.append(ts)
                        acc_mags.append(math.sqrt(vx*vx + vy*vy + vz*vz))
                        gyro_z.append(0.0)
                    elif stype == 4: # Gyroscope
                        if len(gyro_z) > 0:
                            gyro_z[-1] = vz
                except ValueError:
                    pass

    if not timestamps:
        print("No valid sensor samples found.")
        return

    ts_arr = np.array(timestamps)
    mags = np.array(acc_mags)
    gz_arr = np.array(gyro_z)

    # Dynamic acceleration
    dyn_acc = mags - np.mean(mags)
    std_acc = np.std(dyn_acc)
    threshold = max(0.4, 0.8 * std_acc)

    # Reconstruct heading via numerical integration of Gyro Z
    heading_rad = 0.0
    x_pos = 0.0
    y_pos = 0.0

    trajectory_points = [[0.0, 0.0, 0.0, 0.0]] # ts, x, y, heading_deg
    steps_detected = 0
    last_step_time = 0

    for i in range(1, len(dyn_acc) - 1):
        dt = (ts_arr[i] - ts_arr[i-1]) / 1000.0
        if dt > 0 and dt < 0.5:
            heading_rad += gz_arr[i] * dt

        # Check step
        if dyn_acc[i] > threshold and dyn_acc[i] > dyn_acc[i-1] and dyn_acc[i] > dyn_acc[i+1]:
            if (ts_arr[i] - last_step_time) >= 250.0:
                steps_detected += 1
                last_step_time = ts_arr[i]
                
                # Advance 2D position along current heading
                x_pos += stride_length_m * math.cos(heading_rad)
                y_pos += stride_length_m * math.sin(heading_rad)
                trajectory_points.append([round(ts_arr[i],1), round(x_pos, 3), round(y_pos, 3), round(math.degrees(heading_rad), 1)])

    with open(output_csv, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(["Timestamp_ms", "Position_X_meters", "Position_Y_meters", "Heading_Angle_deg"])
        writer.writerows(trajectory_points)

    total_dist = steps_detected * stride_length_m
    print("=" * 65)
    print(" OPPO A33w 2D PEDESTRIAN DEAD RECKONING (PDR) REPORT")
    print("=" * 65)
    print(f"Total Steps Detected      : {steps_detected} steps")
    print(f"Total Distance Traversed  : {total_dist:.2f} meters")
    print(f"Final 2D Coordinates (X,Y): ({x_pos:+.2f} m, {y_pos:+.2f} m)")
    print(f"Final Heading Angle       : {math.degrees(heading_rad):+.1f}°")
    print(f"Exported Trajectory File  : {output_csv}")
    print("=" * 65)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Oppo A33w PDR 2D Trajectory Tracker")
    parser.add_argument("csv_file", help="Input multi-sensor CSV")
    parser.add_argument("--stride", type=float, default=0.7, help="Assumed stride length in meters")
    parser.add_argument("--output", default="pdr_trajectory_2d.csv", help="Output trajectory coordinates CSV")
    args = parser.parse_args()
    reconstruct_trajectory(args.csv_file, args.stride, args.output)
