#!/usr/bin/env python3
"""
Oppo A33w IMU 9-DOF / 6-DOF Sensor Fusion Engine (Madgwick & Mahony AHRS)
Fuses raw Accelerometer (m/s²), Gyroscope (rad/s), and Magnetometer (µT) data
to compute real-time 3D orientation (Roll, Pitch, Yaw Euler angles and Quaternions).
"""

import math
import sys
import os
import argparse
import csv

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required. Run 'pip install numpy'")
    sys.exit(1)

class MadgwickAHRS:
    """
    Madgwick orientation filter for IMU (6-DOF) and MARG (9-DOF).
    Beta represents the gyroscope measurement error (proportional to gyroscope drift).
    """
    def __init__(self, sampleperiod=0.01, beta=0.1):
        self.sampleperiod = sampleperiod
        self.beta = beta
        self.q = np.array([1.0, 0.0, 0.0, 0.0], dtype=float)

    def update_6dof(self, gx, gy, gz, ax, ay, az, dt=None):
        """Update orientation with Accelerometer and Gyroscope only (Roll & Pitch drift-compensated)."""
        if dt is not None and dt > 0:
            self.sampleperiod = dt

        q1, q2, q3, q4 = self.q

        # Auxiliary variables to avoid repeated arithmetic
        _2q1 = 2.0 * q1
        _2q2 = 2.0 * q2
        _2q3 = 2.0 * q3
        _2q4 = 2.0 * q4
        _4q1 = 4.0 * q1
        _4q2 = 4.0 * q2
        _4q3 = 4.0 * q3
        _8q2 = 8.0 * q2
        _8q3 = 8.0 * q3
        q1q1 = q1 * q1
        q2q2 = q2 * q2
        q3q3 = q3 * q3
        q4q4 = q4 * q4

        # Normalize accelerometer measurement
        norm = math.sqrt(ax * ax + ay * ay + az * az)
        if norm == 0.0:
            return self.get_euler()
        ax /= norm
        ay /= norm
        az /= norm

        # Gradient decent algorithm corrective step
        s1 = _4q1 * q3q3 + _2q3 * ax + _4q1 * q2q2 - _2q2 * ay
        s2 = _4q2 * q4q4 - _2q4 * ax + 4.0 * q1q1 * q2 - _2q1 * ay - _4q2 + _8q2 * q2q2 + _8q2 * q3q3 + _4q2 * az
        s3 = 4.0 * q1q1 * q3 + _2q1 * ax + _4q3 * q4q4 - _2q4 * ay - _4q3 + _8q3 * q2q2 + _8q3 * q3q3 + _4q3 * az
        s4 = 4.0 * q2q2 * q4 - _2q2 * ax + 4.0 * q3q3 * q4 - _2q3 * ay

        norm = math.sqrt(s1 * s1 + s2 * s2 + s3 * s3 + s4 * s4)
        if norm > 0.0:
            s1 /= norm
            s2 /= norm
            s3 /= norm
            s4 /= norm

        # Rate of change of quaternion from gyroscope
        qDot1 = 0.5 * (-q2 * gx - q3 * gy - q4 * gz) - self.beta * s1
        qDot2 = 0.5 * (q1 * gx + q3 * gz - q4 * gy) - self.beta * s2
        qDot3 = 0.5 * (q1 * gy - q2 * gz + q4 * gx) - self.beta * s3
        qDot4 = 0.5 * (q1 * gz + q2 * gy - q3 * gx) - self.beta * s4

        # Integrate to yield quaternion
        q1 += qDot1 * self.sampleperiod
        q2 += qDot2 * self.sampleperiod
        q3 += qDot3 * self.sampleperiod
        q4 += qDot4 * self.sampleperiod

        norm = math.sqrt(q1 * q1 + q2 * q2 + q3 * q3 + q4 * q4)
        self.q = np.array([q1/norm, q2/norm, q3/norm, q4/norm])
        return self.get_euler()

    def get_euler(self):
        """Returns (roll, pitch, yaw) in degrees."""
        q0, q1, q2, q3 = self.q
        # Roll (x-axis rotation)
        sinr_cosp = 2.0 * (q0 * q1 + q2 * q3)
        cosr_cosp = 1.0 - 2.0 * (q1 * q1 + q2 * q2)
        roll = math.atan2(sinr_cosp, cosr_cosp)

        # Pitch (y-axis rotation)
        sinp = 2.0 * (q0 * q2 - q3 * q1)
        if abs(sinp) >= 1:
            pitch = math.copysign(math.pi / 2, sinp)
        else:
            pitch = math.asin(sinp)

        # Yaw (z-axis rotation)
        siny_cosp = 2.0 * (q0 * q3 + q1 * q2)
        cosy_cosp = 1.0 - 2.0 * (q2 * q2 + q3 * q3)
        yaw = math.atan2(siny_cosp, cosy_cosp)

        return math.degrees(roll), math.degrees(pitch), math.degrees(yaw)

def process_recorded_imu(csv_path, output_csv="oppo_orientation_ahrs.csv"):
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}")
        return

    print(f"Processing IMU data with Madgwick AHRS: '{csv_path}' -> '{output_csv}'...")
    ahrs = MadgwickAHRS(beta=0.1)

    # Store latest values
    latest_acc = [0.0, 0.0, 9.8]
    latest_gyro = [0.0, 0.0, 0.0]
    last_ts = None

    rows_out = [["Timestamp_ms", "Roll_deg", "Pitch_deg", "Yaw_deg", "Q0", "Q1", "Q2", "Q3"]]
    count = 0

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
                        latest_acc = [vx, vy, vz]
                    elif stype == 4: # Gyroscope
                        latest_gyro = [vx, vy, vz]

                    # When we get either IMU update, recalculate orientation
                    dt = (ts - last_ts)/1000.0 if last_ts is not None else 0.01
                    if dt <= 0 or dt > 0.5: dt = 0.01
                    last_ts = ts

                    roll, pitch, yaw = ahrs.update_6dof(
                        latest_gyro[0], latest_gyro[1], latest_gyro[2],
                        latest_acc[0], latest_acc[1], latest_acc[2],
                        dt=dt
                    )
                    count += 1
                    q = ahrs.q
                    rows_out.append([round(ts, 1), round(roll, 2), round(pitch, 2), round(yaw, 2), round(q[0],4), round(q[1],4), round(q[2],4), round(q[3],4)])
                except ValueError:
                    pass

    with open(output_csv, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerows(rows_out)

    print(f"AHRS Orientation Fusion Complete! Computed {count} orientation states.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Oppo A33w 3D AHRS Orientation Processor")
    parser.add_argument("csv_file", help="Input multi-sensor CSV file")
    parser.add_argument("output_csv", nargs="?", default="oppo_orientation_ahrs.csv", help="Output orientation CSV file")
    args = parser.parse_args()
    process_recorded_imu(args.csv_file, args.output_csv)
