#!/usr/bin/env python3
"""
SensorMax CBM Reliability Dashboard

Purpose
-------
Provide a Streamlit dashboard for the CURRENT SensorMax desktop
pipeline.

Data flow
---------

    Android SensorMax
            |
            | WebSocket
            v
    LaptopSuite/web_server.py
            |
            +----> *_raw.csv
            |
            +----> *_analysis.csv
            |
            v
    cbm_live_dashboard.py


Current analysis CSV schema
---------------------------

Timestamp_ms
Machine_ID
Point
Sample_Rate_Hz
RMS_X_ms2
RMS_Y_ms2
RMS_Z_ms2
Overall_RMS_Accel_ms2
Overall_RMS_Velocity_mms
Overall_RMS_Displacement_um
Dominant_Axis
Dominant_Freq_Hz
Dominant_Accel_Amplitude_ms2
Envelope_Peak_Freq_Hz
ISO20816_Zone
Bearing_Match
RPM_Input
Impact_Event
Snapshot_Triggered


Current raw CSV schema from web_server.py
------------------------------------------

Timestamp_ms
Sensor_ID
Sensor_Type
Sensor_Name
Value_0
Value_1
Value_2
Value_3
Value_4
Value_5

The dashboard also accepts the Android-origin schema:

Timestamp_ms
Machine_ID
Point
Sensor_Type
Sensor_Name
Val_0
Val_1
Val_2
Val_3
Val_4
Val_5


Important
---------
- AndroidApp is NOT modified.
- No device-specific Oppo identity is hard-coded.
- No frequency peak is treated as a confirmed machine diagnosis.
- ISO zone and bearing match are displayed as supplied by the
  Android analysis pipeline.
"""

from __future__ import annotations

import os
import glob
import time
from pathlib import Path
from typing import Optional

import pandas as pd
import plotly.graph_objects as go
import streamlit as st


# ============================================================
# Configuration
# ============================================================

st.set_page_config(
    page_title="SensorMax CBM Reliability Dashboard",
    page_icon="SM",
    layout="wide",
)


BASE_DIR = (
    Path(__file__)
    .resolve()
    .parent
)

RECORD_DIR = (
    BASE_DIR /
    "Imported_Records"
)


DEFAULT_REFRESH_SECONDS = 2.0

MAX_RAW_POINTS = 600

MAX_ANALYSIS_ROWS = 300


# ============================================================
# CSS
# ============================================================

st.markdown(
    """
    <style>

    .main {
        background-color: #08111f;
    }

    .block-container {
        max-width: 1500px;
        padding-top: 1.2rem;
        padding-bottom: 2rem;
    }

    .sensormax-title {
        font-size: 1.9rem;
        font-weight: 800;
        margin-bottom: 0.1rem;
    }

    .sensormax-subtitle {
        color: #94a3b8;
        font-size: 0.85rem;
        margin-bottom: 1rem;
    }

    .small-note {
        color: #94a3b8;
        font-size: 0.75rem;
    }

    .status-good {
        color: #4ade80;
        font-weight: 700;
    }

    .status-warn {
        color: #facc15;
        font-weight: 700;
    }

    .status-fail {
        color: #f87171;
        font-weight: 700;
    }

    </style>
    """,
    unsafe_allow_html=True,
)


# ============================================================
# Utility
# ============================================================

def numeric_series(
    df: pd.DataFrame,
    column: str,
) -> pd.Series:

    if column not in df.columns:

        return pd.Series(
            dtype="float64"
        )

    return pd.to_numeric(
        df[column],
        errors="coerce",
    ).dropna()


def first_nonempty(
    df: pd.DataFrame,
    column: str,
    default: str = "UNSPECIFIED",
) -> str:

    if column not in df.columns:

        return default

    values = (
        df[column]
        .dropna()
        .astype(str)
        .str.strip()
    )

    values = values[
        values != ""
    ]

    if values.empty:

        return default

    return str(
        values.iloc[-1]
    )


def latest_value(
    df: pd.DataFrame,
    column: str,
    default=0.0,
):
    """

    Return the latest valid value from a column.
    """

    values = numeric_series(
        df,
        column,
    )

    if values.empty:

        return default

    return float(
        values.iloc[-1]
    )


def latest_text(
    df: pd.DataFrame,
    column: str,
    default: str = "--",
) -> str:

    if column not in df.columns:

        return default

    values = (
        df[column]
        .dropna()
        .astype(str)
        .str.strip()
    )

    values = values[
        values != ""
    ]

    if values.empty:

        return default

    return str(
        values.iloc[-1]
    )


def count_true_values(
    series: pd.Series,
) -> int:

    if series.empty:

        return 0

    return int(
        series
        .astype(str)
        .str.strip()
        .str.lower()
        .isin(
            {
                "true",
                "1",
                "yes",
            }
        )
        .sum()
    )


# ============================================================
# Locate latest files
# ============================================================

def latest_file(
    pattern: str,
) -> Optional[Path]:

    files = glob.glob(
        str(
            RECORD_DIR /
            pattern
        )
    )

    if not files:

        return None

    return Path(
        max(
            files,
            key=os.path.getmtime,
        )
    )


def find_latest_analysis() -> Optional[Path]:

    return latest_file(
        "*_analysis.csv"
    )


def find_latest_raw() -> Optional[Path]:

    return latest_file(
        "*_raw.csv"
    )


# ============================================================
# Current schema normalization
# ============================================================

def normalize_raw_columns(
    df: pd.DataFrame,
) -> pd.DataFrame:

    result = df.copy()

    rename_map = {}

    # Current Android-origin raw schema.
    if "Val_0" in result.columns:

        rename_map.update(
            {
                "Val_0": "X",
                "Val_1": "Y",
                "Val_2": "Z",
            }
        )

    # Current web_server-produced raw schema.
    if "Value_0" in result.columns:

        rename_map.update(
            {
                "Value_0": "X",
                "Value_1": "Y",
                "Value_2": "Z",
            }
        )

    # Historical compatibility.
    if "Value_X" in result.columns:

        rename_map[
            "Value_X"
        ] = "X"

    if "Value_Y" in result.columns:

        rename_map[
            "Value_Y"
        ] = "Y"

    if "Value_Z" in result.columns:

        rename_map[
            "Value_Z"
        ] = "Z"

    result = result.rename(
        columns=rename_map
    )

    for column in (
        "X",
        "Y",
        "Z",
    ):

        if column not in result.columns:

            result[column] = 0.0

        result[column] = pd.to_numeric(
            result[column],
            errors="coerce",
        )

    if "Timestamp_ms" in result.columns:

        result["Timestamp_dt"] = pd.to_datetime(
            result["Timestamp_ms"],
            unit="ms",
            errors="coerce",
        )

    return result


def normalize_analysis_columns(
    df: pd.DataFrame,
) -> pd.DataFrame:

    result = df.copy()

    numeric_columns = [
        "Sample_Rate_Hz",
        "RMS_X_ms2",
        "RMS_Y_ms2",
        "RMS_Z_ms2",
        "Overall_RMS_Accel_ms2",
        "Overall_RMS_Velocity_mms",
        "Overall_RMS_Displacement_um",
        "Dominant_Freq_Hz",
        "Dominant_Accel_Amplitude_ms2",
        "Envelope_Peak_Freq_Hz",
        "RPM_Input",
    ]

    for column in numeric_columns:

        if column in result.columns:

            result[column] = pd.to_numeric(
                result[column],
                errors="coerce",
            )

    if "Timestamp_ms" in result.columns:

        result["Timestamp_dt"] = pd.to_datetime(
            result["Timestamp_ms"],
            unit="ms",
            errors="coerce",
        )

    return result


# ============================================================
# Loading
# ============================================================

@st.cache_data(
    ttl=1,
    show_spinner=False,
)
def load_csv(
    path_string: str,
) -> pd.DataFrame:

    path = Path(
        path_string
    )

    return pd.read_csv(
        path
    )


# ============================================================
# Severity helpers
# ============================================================

def iso_css_class(
    zone: str,
) -> str:

    text = (
        str(zone)
        .strip()
        .upper()
    )

    if (
        "A" in text
        and
        "B" not in text
    ):

        return "status-good"

    if "B" in text:

        return "status-warn"

    if (
        "C" in text
        or
        "D" in text
    ):

        return "status-fail"

    return ""


# ============================================================
# Page header
# ============================================================

st.markdown(
    '<div class="sensormax-title">'
    'SensorMax CBM Reliability Dashboard'
    '</div>',
    unsafe_allow_html=True,
)

st.markdown(
    '<div class="sensormax-subtitle">'
    'Live condition-monitoring view for SensorMax acquisition and '
    'analysis streams'
    '</div>',
    unsafe_allow_html=True,
)


# ============================================================
# Sidebar
# ============================================================

st.sidebar.header(
    "Dashboard Controls"
)


auto_refresh = st.sidebar.checkbox(
    "Auto refresh",
    value=True,
)


refresh_rate = st.sidebar.slider(
    "Refresh interval (seconds)",
    min_value=1,
    max_value=10,
    value=2,
    step=1,
)


display_points = st.sidebar.slider(
    "Waveform points",
    min_value=100,
    max_value=600,
    value=300,
    step=50,
)


show_raw = st.sidebar.checkbox(
    "Show raw telemetry table",
    value=False,
)


show_analysis = st.sidebar.checkbox(
    "Show analysis table",
    value=False,
)


st.sidebar.markdown("---")


st.sidebar.markdown(
    "**Data directory**"
)

st.sidebar.code(
    str(RECORD_DIR)
)


# ============================================================
# Locate latest data
# ============================================================

analysis_file = (
    find_latest_analysis()
)

raw_file = (
    find_latest_raw()
)


if (
    analysis_file is None
    and
    raw_file is None
):

    st.warning(
        "No SensorMax records found."
    )

    st.info(
        "Start web_server.py and stream data from Android, "
        "or place SensorMax *_raw.csv / *_analysis.csv files "
        "inside Imported_Records."
    )

    st.code(
        str(RECORD_DIR)
    )

    st.stop()


# ============================================================
# Load data
# ============================================================

analysis_df = pd.DataFrame()

raw_df = pd.DataFrame()


if analysis_file is not None:

    try:

        analysis_df = normalize_analysis_columns(
            load_csv(
                str(analysis_file)
            )
        )

    except Exception as exc:

        st.error(
            "Unable to read analysis CSV: "
            f"{exc}"
        )


if raw_file is not None:

    try:

        raw_df = normalize_raw_columns(
            load_csv(
                str(raw_file)
            )
        )

    except Exception as exc:

        st.error(
            "Unable to read raw CSV: "
            f"{exc}"
        )


# ============================================================
# Source status
# ============================================================

source_columns = st.columns(
    3
)


with source_columns[0]:

    if analysis_file is not None:

        st.success(
            "Analysis stream detected"
        )

    else:

        st.info(
            "No analysis stream"
        )


with source_columns[1]:

    if raw_file is not None:

        st.success(
            "Raw stream detected"
        )

    else:

        st.info(
            "No raw stream"
        )


with source_columns[2]:

    st.caption(
        "AndroidApp: unchanged"
    )


# ============================================================
# Prefer analysis as primary dashboard source
# ============================================================

primary_df = (
    analysis_df
    if not analysis_df.empty
    else raw_df
)


if primary_df.empty:

    st.warning(
        "The discovered SensorMax files contain no usable rows."
    )

    st.stop()


# ============================================================
# Session context
# ============================================================

machine_id = first_nonempty(
    primary_df,
    "Machine_ID",
)

point = first_nonempty(
    primary_df,
    "Point",
)

st.markdown(
    "### Asset Context"
)


context_columns = st.columns(
    4
)


with context_columns[0]:

    st.metric(
        "Machine ID",
        machine_id,
    )


with context_columns[1]:

    st.metric(
        "Measurement Point",
        point,
    )


with context_columns[2]:

    if analysis_file is not None:

        st.metric(
            "Analysis Windows",
            f"{len(analysis_df):,}",
        )

    else:

        st.metric(
            "Raw Records",
            f"{len(raw_df):,}",
        )


with context_columns[3]:

    source_name = (
        analysis_file.name
        if analysis_file is not None
        else raw_file.name
    )

    st.caption(
        "Latest source"
    )

    st.code(
        source_name
    )


# ============================================================
# Analysis dashboard
# ============================================================

if not analysis_df.empty:

    st.markdown(
        "## Current Condition Snapshot"
    )


    latest_analysis =
        analysis_df.iloc[-1]


    sample_rate = latest_value(
        analysis_df,
        "Sample_Rate_Hz",
    )

    rms_accel = latest_value(
        analysis_df,
        "Overall_RMS_Accel_ms2",
    )

    rms_velocity = latest_value(
        analysis_df,
        "Overall_RMS_Velocity_mms",
    )

    displacement = latest_value(
        analysis_df,
        "Overall_RMS_Displacement_um",
    )

    dominant_freq = latest_value(
        analysis_df,
        "Dominant_Freq_Hz",
    )

    envelope_freq = latest_value(
        analysis_df,
        "Envelope_Peak_Freq_Hz",
    )

    dominant_axis = latest_text(
        analysis_df,
        "Dominant_Axis",
    )

    iso_zone = latest_text(
        analysis_df,
        "ISO20816_Zone",
    )

    bearing_match = latest_text(
        analysis_df,
        "Bearing_Match",
    )

    rpm = latest_value(
        analysis_df,
        "RPM_Input",
    )


    cards = st.columns(
        4
    )


    with cards[0]:

        st.metric(
            "RMS Acceleration",
            f"{rms_accel:.3f}",
            "m/s²",
        )


    with cards[1]:

        st.metric(
            "RMS Velocity",
            f"{rms_velocity:.3f}",
            "mm/s",
        )


    with cards[2]:

        st.metric(
            "Dominant Frequency",
            f"{dominant_freq:.3f}",
            "Hz",
        )


    with cards[3]:

        st.metric(
            "Sample Rate",
            f"{sample_rate:.2f}",
            "Hz",
        )


    cards2 = st.columns(
        4
    )


    with cards2[0]:

        st.metric(
            "Dominant Axis",
            dominant_axis,
        )


    with cards2[1]:

        st.metric(
            "Envelope Peak",
            f"{envelope_freq:.3f}",
            "Hz",
        )


    with cards2[2]:

        st.metric(
            "RPM Input",
            f"{rpm:.0f}",
            "rpm",
        )


    with cards2[3]:

        st.metric(
            "Displacement",
            f"{displacement:.2f}",
            "µm",
        )


    # --------------------------------------------------------
    # Status
    # --------------------------------------------------------

    st.markdown(
        "### Engineering Status"
    )


    status_columns = st.columns(
        3
    )


    with status_columns[0]:

        st.metric(
            "ISO 20816 Zone",
            iso_zone,
        )


    with status_columns[1]:

        st.metric(
            "Bearing Match",
            bearing_match,
        )


    with status_columns[2]:

        impact_count = count_true_values(
            analysis_df[
                "Impact_Event"
            ]
            if "Impact_Event"
            in analysis_df.columns
            else pd.Series(
                dtype="object"
            )
        )

        st.metric(
            "Impact Events",
            impact_count,
        )


    # --------------------------------------------------------
    # Frequency context
    # --------------------------------------------------------

    st.markdown(
        "### Running-Speed Context"
    )


    speed_columns = st.columns(
        4
    )


    one_x = (
        rpm /
        60.0
        if rpm > 0
        else 0.0
    )


    with speed_columns[0]:

        st.metric(
            "1X",
            f"{one_x:.3f}",
            "Hz",
        )


    with speed_columns[1]:

        st.metric(
            "2X",
            f"{one_x * 2:.3f}",
            "Hz",
        )


    with speed_columns[2]:

        st.metric(
            "3X",
            f"{one_x * 3:.3f}",
            "Hz",
        )


    with speed_columns[3]:

        if one_x > 0:

            ratio = (
                dominant_freq /
                one_x
            )

            st.metric(
                "Peak / 1X",
                f"{ratio:.2f}X",
            )

        else:

            st.metric(
                "Peak / 1X",
                "--",
            )


    # --------------------------------------------------------
    # Advisory
    # --------------------------------------------------------

    st.markdown(
        "### Advisory"
    )


    if dominant_freq and one_x:

        ratio = (
            dominant_freq /
            one_x
        )

        if abs(
            ratio - 1
        ) < 0.08:

            advisory = (
                "Dominant spectral peak is near 1X running speed."
            )

        elif abs(
            ratio - 2
        ) < 0.08:

            advisory = (
                "Dominant spectral peak is near 2X running speed."
            )

        elif abs(
            ratio - 3
        ) < 0.08:

            advisory = (
                "Dominant spectral peak is near 3X running speed."
            )

        else:

            advisory = (
                "Dominant spectral peak is not near "
                "the entered 1X/2X/3X frequencies."
            )

    else:

        advisory = (
            "Insufficient RPM context for running-speed comparison."
        )


    st.info(
        advisory
    )

    st.caption(
        "This is spectral context, not a confirmed machine-fault diagnosis."
    )


    # ========================================================
    # RMS trend
    # ========================================================

    st.markdown(
        "### RMS Velocity Trend"
    )


    trend_df = analysis_df.tail(
        MAX_ANALYSIS_ROWS
    ).copy()


    if (
        "Timestamp_dt"
        in trend_df.columns
        and
        trend_df["Timestamp_dt"].notna().any()
    ):

        x_axis = (
            trend_df[
                "Timestamp_dt"
            ]
        )

    else:

        x_axis = (
            trend_df.index
        )


    fig_rms = go.Figure()


    if (
        "Overall_RMS_Velocity_mms"
        in trend_df.columns
    ):

        fig_rms.add_trace(
            go.Scatter(
                x=x_axis,
                y=trend_df[
                    "Overall_RMS_Velocity_mms"
                ],
                mode="lines",
                name="RMS Velocity",
            )
        )


    fig_rms.update_layout(
        title="Overall RMS Velocity",
        xaxis_title="Time",
        yaxis_title="mm/s",
        template="plotly_dark",
        height=360,
        margin=dict(
            l=30,
            r=20,
            t=45,
            b=35,
        ),
    )


    st.plotly_chart(
        fig_rms,
        use_container_width=True,
    )


    # ========================================================
    # Dominant frequency trend
    # ========================================================

    st.markdown(
        "### Dominant Frequency Trend"
    )


    fig_frequency = go.Figure()


    if (
        "Dominant_Freq_Hz"
        in trend_df.columns
    ):

        fig_frequency.add_trace(
            go.Scatter(
                x=x_axis,
                y=trend_df[
                    "Dominant_Freq_Hz"
                ],
                mode="lines",
                name="Dominant Frequency",
            )
        )


    if one_x > 0:

        fig_frequency.add_hline(
            y=one_x,
            line_dash="dash",
            annotation_text="1X",
        )

        fig_frequency.add_hline(
            y=one_x * 2,
            line_dash="dot",
            annotation_text="2X",
        )

        fig_frequency.add_hline(
            y=one_x * 3,
            line_dash="dot",
            annotation_text="3X",
        )


    fig_frequency.update_layout(
        title="Dominant Spectral Frequency",
        xaxis_title="Time",
        yaxis_title="Hz",
        template="plotly_dark",
        height=360,
        margin=dict(
            l=30,
            r=20,
            t=45,
            b=35,
        ),
    )


    st.plotly_chart(
        fig_frequency,
        use_container_width=True,
    )


    # ========================================================
    # Envelope trend
    # ========================================================

    st.markdown(
        "### Envelope Peak Trend"
    )


    fig_envelope = go.Figure()


    if (
        "Envelope_Peak_Freq_Hz"
        in trend_df.columns
    ):

        fig_envelope.add_trace(
            go.Scatter(
                x=x_axis,
                y=trend_df[
                    "Envelope_Peak_Freq_Hz"
                ],
                mode="lines",
                name="Envelope Peak",
            )
        )


    fig_envelope.update_layout(
        title="Envelope Peak Frequency",
        xaxis_title="Time",
        yaxis_title="Hz",
        template="plotly_dark",
        height=360,
        margin=dict(
            l=30,
            r=20,
            t=45,
            b=35,
        ),
    )


    st.plotly_chart(
        fig_envelope,
        use_container_width=True,
    )


    # ========================================================
    # Analysis table
    # ========================================================

    if show_analysis:

        st.markdown(
            "### Recent Analysis Windows"
        )

        display_columns = [
            "Timestamp_ms",
            "Machine_ID",
            "Point",
            "Sample_Rate_Hz",
            "Overall_RMS_Accel_ms2",
            "Overall_RMS_Velocity_mms",
            "Overall_RMS_Displacement_um",
            "Dominant_Axis",
            "Dominant_Freq_Hz",
            "Envelope_Peak_Freq_Hz",
            "ISO20816_Zone",
            "Bearing_Match",
            "RPM_Input",
            "Impact_Event",
            "Snapshot_Triggered",
        ]

        existing_columns = [
            column
            for column in display_columns
            if column
            in analysis_df.columns
        ]

        st.dataframe(
            analysis_df[
                existing_columns
            ]
            .tail(
                MAX_ANALYSIS_ROWS
            ),
            use_container_width=True,
            hide_index=True,
        )


# ============================================================
# RAW waveform
# ============================================================

if not raw_df.empty:

    st.markdown(
        "## Raw Vibration Waveform"
    )


    raw_display = raw_df.tail(
        display_points
    ).copy()


    if "Timestamp_dt" in raw_display.columns:

        time_axis = (
            raw_display[
                "Timestamp_dt"
            ]
        )

    else:

        time_axis = (
            raw_display.index
        )


    fig_wave = go.Figure()


    fig_wave.add_trace(
        go.Scatter(
            x=time_axis,
            y=raw_display["X"],
            mode="lines",
            name="X",
        )
    )


    fig_wave.add_trace(
        go.Scatter(
            x=time_axis,
            y=raw_display["Y"],
            mode="lines",
            name="Y",
        )
    )


    fig_wave.add_trace(
        go.Scatter(
            x=time_axis,
            y=raw_display["Z"],
            mode="lines",
            name="Z",
        )
    )


    fig_wave.update_layout(
        title="Recent Triaxial Raw Sensor Data",
        xaxis_title="Time",
        yaxis_title="Sensor value",
        template="plotly_dark",
        height=430,
        margin=dict(
            l=30,
            r=20,
            t=45,
            b=35,
        ),
    )


    st.plotly_chart(
        fig_wave,
        use_container_width=True,
    )


    # --------------------------------------------------------
    # Current raw values
    # --------------------------------------------------------

    latest_raw = raw_df.iloc[-1]


    raw_columns = st.columns(
        4
    )


    with raw_columns[0]:

        st.metric(
            "X",
            f"{float(latest_raw['X']):.4f}",
        )


    with raw_columns[1]:

        st.metric(
            "Y",
            f"{float(latest_raw['Y']):.4f}",
        )


    with raw_columns[2]:

        st.metric(
            "Z",
            f"{float(latest_raw['Z']):.4f}",
        )


    with raw_columns[3]:

        rss = (
            float(latest_raw["X"]) ** 2
            +
            float(latest_raw["Y"]) ** 2
            +
            float(latest_raw["Z"]) ** 2
        ) ** 0.5


        st.metric(
            "RSS",
            f"{rss:.4f}",
        )


    # --------------------------------------------------------
    # Sensor information
    # --------------------------------------------------------

    if show_raw:

        st.markdown(
            "### Raw Telemetry"
        )

        st.dataframe(
            raw_df.tail(
                display_points
            ),
            use_container_width=True,
            hide_index=True,
        )


# ============================================================
# Session information
# ============================================================

st.markdown(
    "## Stream Information"
)


info_columns = st.columns(
    4
)


with info_columns[0]:

    st.caption(
        "Analysis file"
    )

    st.code(
        analysis_file.name
        if analysis_file is not None
        else "NONE"
    )


with info_columns[1]:

    st.caption(
        "Raw file"
    )

    st.code(
        raw_file.name
        if raw_file is not None
        else "NONE"
    )


with info_columns[2]:

    st.caption(
        "Import directory"
    )

    st.code(
        str(RECORD_DIR)
    )


with info_columns[3]:

    if "Timestamp_ms" in primary_df.columns:

        timestamps = numeric_series(
            primary_df,
            "Timestamp_ms",
        )

        if not timestamps.empty:

            first_ts = (
                timestamps.iloc[0]
            )

            last_ts = (
                timestamps.iloc[-1]
            )

            duration = max(
                0.0,
                (
                    last_ts -
                    first_ts
                ) / 1000.0,
            )

            st.metric(
                "Dataset Duration",
                f"{duration:.2f} s",
            )

        else:

            st.metric(
                "Dataset Duration",
                "--",
            )

    else:

        st.metric(
            "Dataset Duration",
            "--",
        )


# ============================================================
# Engineering boundary
# ============================================================

st.markdown(
    "## Engineering Boundary"
)


st.warning(
    "SensorMax reports measurement and analysis evidence. "
    "RMS, frequency peaks, ISO zones, bearing-match codes and "
    "running-speed relationships must not be interpreted as a "
    "confirmed machine diagnosis without appropriate engineering "
    "validation and supporting evidence."
)


# ============================================================
# Auto refresh
# ============================================================

if auto_refresh:

    time.sleep(
        refresh_rate
    )

    st.rerun()