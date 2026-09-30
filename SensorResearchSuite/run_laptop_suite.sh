#!/usr/bin/env bash

# ============================================================
# SensorMax Desktop / LaptopSuite Launcher
# ============================================================
#
# Current architecture:
#
#   Android SensorMax
#          |
#          | WebSocket :8765
#          v
#   LaptopSuite/web_server.py
#          |
#          +----> Browser Dashboard
#          |
#          +----> CSV logs
#
# AndroidApp is NOT modified by this launcher.
#
# This launcher intentionally removes the old:
#   - Oppo-specific wording
#   - port 5005 architecture
#   - ADB forward workflow
#   - unrelated biomedical/activity tools
#
# USB mode:
#   adb reverse tcp:8765 tcp:8765
#
# Android WebSocket endpoint:
#   ws://127.0.0.1:8765
# ============================================================


set -u


# ============================================================
# Paths
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR" || exit 1


LAPTOP_DIR="$SCRIPT_DIR/LaptopSuite"

VENV_DIR="$SCRIPT_DIR/sensormax_env"

WEB_SERVER="$LAPTOP_DIR/web_server.py"

WEB_DASHBOARD="$LAPTOP_DIR/web_dashboard.html"

IMPORT_DIR="$LAPTOP_DIR/Imported_Records"


# ============================================================
# Colors
# ============================================================

RED="\033[31m"

GREEN="\033[32m"

YELLOW="\033[33m"

CYAN="\033[36m"

RESET="\033[0m"


# ============================================================
# Helpers
# ============================================================

pause_screen() {

    echo

    read -r -p "Press Enter to return to the menu..."

}


header() {

    clear 2>/dev/null || true

    echo "============================================================"

    echo "  SENSORMAX LAPTOP SUITE"

    echo "============================================================"

    echo

    echo "  Workspace : $SCRIPT_DIR"

    echo "  Laptop    : $LAPTOP_DIR"

    echo "  WebSocket : ws://127.0.0.1:8765"

    echo

}


activate_environment() {

    if [[ ! -f "$VENV_DIR/bin/activate" ]]; then

        echo -e "${RED}[ERROR] sensormax_env was not found.${RESET}"

        echo

        echo "Expected:"

        echo "  $VENV_DIR"

        echo

        echo "Run first_initialize.bat on Windows or create the"

        echo "Python environment manually on this platform."

        echo

        return 1

    fi


    # shellcheck disable=SC1091

    source "$VENV_DIR/bin/activate"

    return 0

}


require_file() {

    local file="$1"

    if [[ ! -f "$file" ]]; then

        echo -e "${RED}[ERROR] File not found:${RESET}"

        echo "        $file"

        echo

        return 1

    fi

    return 0

}


run_python() {

    local script="$1"

    shift

    python3 "$script" "$@"

}


open_dashboard() {

    if ! require_file "$WEB_DASHBOARD"; then

        return 1

    fi


    echo "[*] Opening SensorMax browser dashboard..."

    if command -v xdg-open >/dev/null 2>&1; then

        xdg-open "$WEB_DASHBOARD" >/dev/null 2>&1 &

    elif command -v open >/dev/null 2>&1; then

        open "$WEB_DASHBOARD" >/dev/null 2>&1 &

    else

        echo -e "${YELLOW}[WARN] No graphical browser launcher found.${RESET}"

        echo

        echo "Open manually:"

        echo "  $WEB_DASHBOARD"

    fi

}


# ============================================================
# Main tools
# ============================================================

run_web_hmi() {

    echo

    echo "============================================================"

    echo "  SENSORMax WEB HMI"

    echo "============================================================"

    echo


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$WEB_SERVER"; then

        pause_screen

        return

    fi


    if ! require_file "$WEB_DASHBOARD"; then

        pause_screen

        return

    fi


    # --------------------------------------------------------
    # USB Android bridge
    # --------------------------------------------------------

    if command -v adb >/dev/null 2>&1; then

        echo "[*] Checking ADB..."

        adb start-server >/dev/null 2>&1 || true

        echo

        adb devices

        echo

        echo "[*] Establishing USB WebSocket reverse tunnel..."

        if adb reverse tcp:8765 tcp:8765 >/dev/null 2>&1; then

            echo -e "${GREEN}[OK] adb reverse tcp:8765 tcp:8765${RESET}"

            echo

        else

            echo -e "${YELLOW}[WARN] ADB reverse could not be established.${RESET}"

            echo "       Wi-Fi WebSocket mode can still be used."

            echo

        fi

    else

        echo -e "${YELLOW}[INFO] adb was not found.${RESET}"

        echo "       USB reverse mode is unavailable."

        echo "       Wi-Fi WebSocket mode can still be used."

        echo

    fi


    # --------------------------------------------------------
    # Start gateway
    # --------------------------------------------------------

    echo "[*] Starting SensorMax WebSocket gateway..."

    python3 "$WEB_SERVER" &

    SERVER_PID=$!


    echo

    echo "[OK] WebSocket gateway PID: $SERVER_PID"

    echo

    sleep 1


    # --------------------------------------------------------
    # Open browser
    # --------------------------------------------------------

    open_dashboard


    echo

    echo "============================================================"

    echo "  SENSORMax WEB HMI RUNNING"

    echo "============================================================"

    echo

    echo "  Gateway:"

    echo "    ws://127.0.0.1:8765"

    echo

    echo "  Dashboard:"

    echo "    $WEB_DASHBOARD"

    echo

    echo "  USB Android endpoint:"

    echo "    ws://127.0.0.1:8765"

    echo

    echo "  Imported records:"

    echo "    $IMPORT_DIR"

    echo

    echo "============================================================"

    echo

    echo "Press Ctrl+C to stop the gateway."

    echo


    trap 'echo; echo "[*] Stopping SensorMax gateway..."; kill "$SERVER_PID" 2>/dev/null || true; if command -v adb >/dev/null 2>&1; then adb reverse --remove tcp:8765 >/dev/null 2>&1 || true; fi; echo "[OK] Web HMI stopped."; exit 0' INT TERM


    wait "$SERVER_PID"

}


run_gateway_only() {

    echo

    echo "============================================================"

    echo "  SENSORMax WEBSOCKET GATEWAY"

    echo "============================================================"

    echo


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$WEB_SERVER"; then

        pause_screen

        return

    fi


    if command -v adb >/dev/null 2>&1; then

        echo "[*] Applying ADB reverse tunnel..."

        adb reverse tcp:8765 tcp:8765 >/dev/null 2>&1 || true

    fi


    echo "[*] Starting gateway..."

    echo

    echo "  WebSocket: ws://0.0.0.0:8765"

    echo

    echo "Press Ctrl+C to stop."

    echo


    trap 'echo; echo "[OK] Gateway stopped."; exit 0' INT TERM


    python3 "$WEB_SERVER"

}


run_dashboard_only() {

    echo

    echo "============================================================"

    echo "  SENSORMax BROWSER DASHBOARD"

    echo "============================================================"

    echo


    open_dashboard

    pause_screen

}


run_live_fft() {

    local script="$LAPTOP_DIR/live_spectrum_studio.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    run_python "$script"

    pause_screen

}


run_cli_explorer() {

    local script="$LAPTOP_DIR/cli_explorer.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No CSV path entered.${RESET}"

        pause_screen

        return

    fi


    run_python "$script" "$csv_path"

    pause_screen

}


run_alarm_monitor() {

    local script="$LAPTOP_DIR/realtime_alarm_monitor.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    run_python "$script"

    pause_screen

}


run_modal_analysis() {

    local script="$LAPTOP_DIR/vibration_modal_analysis.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No CSV path entered.${RESET}"

        pause_screen

        return

    fi


    run_python "$script" "$csv_path"

    pause_screen

}


run_dsp_filters() {

    local script="$LAPTOP_DIR/digital_filters.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No CSV path entered.${RESET}"

        pause_screen

        return

    fi


    run_python "$script" "$csv_path"

    pause_screen

}


run_anomaly_detector() {

    local script="$LAPTOP_DIR/anomaly_detector.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No CSV path entered.${RESET}"

        pause_screen

        return

    fi


    run_python "$script" "$csv_path"

    pause_screen

}


run_data_analysis() {

    local script="$LAPTOP_DIR/data_analysis.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No CSV path entered.${RESET}"

        pause_screen

        return

    fi


    run_python "$script" "$csv_path"

    pause_screen

}


run_sensor_calibration() {

    local script="$LAPTOP_DIR/sensor_calibration.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "Calibration CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No calibration CSV path entered.${RESET}"

        pause_screen

        return

    fi


    run_python "$script" "$csv_path"

    pause_screen

}


run_export_converter() {

    local script="$LAPTOP_DIR/export_converter.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "SensorMax CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No CSV path entered.${RESET}"

        pause_screen

        return

    fi


    run_python "$script" "$csv_path"

    pause_screen

}


run_export_json() {

    local script="$LAPTOP_DIR/export_json.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "SensorMax CSV file path: " csv_path


    if [[ -z "$csv_path" ]]; then

        echo -e "${YELLOW}[WARN] No CSV path entered.${RESET}"

        pause_screen

        return

    fi


    read -r -p "Optional calibration JSON path (leave blank if none): " calibration_json


    if [[ -n "$calibration_json" ]]; then

        run_python \
            "$script" \
            "$csv_path" \
            "$calibration_json"

    else

        run_python \
            "$script" \
            "$csv_path"

    fi


    pause_screen

}


run_batch_processor() {

    local script="$LAPTOP_DIR/batch_dataset_processor.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    read -r -p "Directory containing SensorMax CSV files: " directory


    if [[ -z "$directory" ]]; then

        echo -e "${YELLOW}[WARN] No directory entered.${RESET}"

        pause_screen

        return

    fi


    read -r -p "Output XLSX path [master_batch_comparison.xlsx]: " output


    if [[ -z "$output" ]]; then

        output="master_batch_comparison.xlsx"

    fi


    run_python \
        "$script" \
        "$directory" \
        --output "$output"


    pause_screen

}


run_integration_test() {

    local script="$LAPTOP_DIR/test_all_pipelines.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    run_python "$script"

    pause_screen

}


run_health_check() {

    local script="$LAPTOP_DIR/verify_suite_health.py"


    if ! activate_environment; then

        pause_screen

        return

    fi


    if ! require_file "$script"; then

        pause_screen

        return

    fi


    run_python "$script"

    pause_screen

}


setup_usb_tunnel() {

    echo

    echo "============================================================"

    echo "  USB WEBSOCKET TUNNEL"

    echo "============================================================"

    echo


    if ! command -v adb >/dev/null 2>&1; then

        echo -e "${RED}[ERROR] adb was not found in PATH.${RESET}"

        echo

        pause_screen

        return

    fi


    echo "[*] Starting ADB..."

    adb start-server


    echo

    echo "[*] Connected Android devices:"

    echo

    adb devices


    echo

    echo "[*] Establishing:"

    echo "    adb reverse tcp:8765 tcp:8765"

    echo


    if adb reverse tcp:8765 tcp:8765; then

        echo

        echo -e "${GREEN}[OK] USB WebSocket tunnel established.${RESET}"

        echo

        echo "Android endpoint:"

        echo "  ws://127.0.0.1:8765"

        echo

        echo "Laptop endpoint:"

        echo "  ws://127.0.0.1:8765"

    else

        echo

        echo -e "${RED}[ERROR] Could not establish ADB reverse tunnel.${RESET}"

    fi


    pause_screen

}


remove_usb_tunnel() {

    echo

    echo "[*] Removing SensorMax USB WebSocket tunnel..."

    echo


    if command -v adb >/dev/null 2>&1; then

        if adb reverse --remove tcp:8765; then

            echo -e "${GREEN}[OK] TCP 8765 reverse tunnel removed.${RESET}"

        else

            echo -e "${YELLOW}[INFO] No active TCP 8765 reverse tunnel.${RESET}"

        fi

    else

        echo -e "${YELLOW}[INFO] adb was not found.${RESET}"

    fi


    pause_screen

}


run_python_syntax_check() {

    if ! activate_environment; then

        pause_screen

        return

    fi


    echo

    echo "============================================================"

    echo "  SENSORMax PYTHON SYNTAX CHECK"

    echo "============================================================"

    echo


    local files=(

        "$LAPTOP_DIR/web_server.py"

        "$LAPTOP_DIR/export_converter.py"

        "$LAPTOP_DIR/export_json.py"

        "$LAPTOP_DIR/batch_dataset_processor.py"

        "$LAPTOP_DIR/test_all_pipelines.py"

    )


    local failed_count=0


    for file in "${files[@]}"; do

        if [[ ! -f "$file" ]]; then

            echo -e "${YELLOW}[SKIP] $(basename "$file") not found.${RESET}"

            continue

        fi


        echo "[*] Checking $(basename "$file")..."


        if python3 -m py_compile "$file"; then

            echo -e "${GREEN}[OK] $(basename "$file")${RESET}"

        else

            echo -e "${RED}[FAIL] $(basename "$file")${RESET}"

            failed_count=$((failed_count + 1))

        fi

    done


    echo


    if [[ "$failed_count" -eq 0 ]]; then

        echo -e "${GREEN}[SUCCESS] Python syntax checks passed.${RESET}"

    else

        echo -e "${RED}[FAIL] $failed_count file(s) failed.${RESET}"

    fi


    pause_screen

}


# ============================================================
# Menu
# ============================================================

show_menu() {

    header

    echo "  LIVE / DEMO"

    echo "  ----------------------------------------------------------"

    echo "   1) Start complete Web HMI"

    echo "   2) Start WebSocket gateway only"

    echo "   3) Open browser dashboard only"

    echo "   4) Live FFT spectrum studio"

    echo


    echo "  ENGINEERING ANALYSIS"

    echo "  ----------------------------------------------------------"

    echo "   5) CLI dataset explorer"

    echo "   6) Real-time alarm / vibration monitor"

    echo "   7) Vibration modal analyzer"

    echo "   8) DSP filter suite"

    echo "   9) Anomaly detector"

    echo "  10) Dataset FFT / jitter analysis"

    echo "  11) Sensor calibration tool"

    echo


    echo "  DATA EXPORT / PROCESSING"

    echo "  ----------------------------------------------------------"

    echo "  12) CSV -> Excel converter"

    echo "  13) CSV -> JSON exporter"

    echo "  14) Batch dataset processor"

    echo


    echo "  VALIDATION"

    echo "  ----------------------------------------------------------"

    echo "  15) Full desktop integration test"

    echo "  16) Python syntax check"

    echo "  17) Suite health verification"

    echo


    echo "  ANDROID USB BRIDGE"

    echo "  ----------------------------------------------------------"

    echo "  18) Setup adb reverse :8765"

    echo "  19) Remove adb reverse :8765"

    echo


    echo "   0) Exit"

    echo

    printf "  Select [0-19]: "

}


# ============================================================
# Main loop
# ============================================================

while true; do

    show_menu

    read -r choice

    case "$choice" in

        1)
            run_web_hmi
            ;;

        2)
            run_gateway_only
            ;;

        3)
            run_dashboard_only
            ;;

        4)
            run_live_fft
            ;;

        5)
            run_cli_explorer
            ;;

        6)
            run_alarm_monitor
            ;;

        7)
            run_modal_analysis
            ;;

        8)
            run_dsp_filters
            ;;

        9)
            run_anomaly_detector
            ;;

        10)
            run_data_analysis
            ;;

        11)
            run_sensor_calibration
            ;;

        12)
            run_export_converter
            ;;

        13)
            run_export_json
            ;;

        14)
            run_batch_processor
            ;;

        15)
            run_integration_test
            ;;

        16)
            run_python_syntax_check
            ;;

        17)
            run_health_check
            ;;

        18)
            setup_usb_tunnel
            ;;

        19)
            remove_usb_tunnel
            ;;

        0)
            echo
            echo "SensorMax launcher terminated."
            echo
            exit 0
            ;;

        *)
            echo
            echo -e "${YELLOW}[WARN] Invalid selection.${RESET}"
            sleep 1
            ;;

    esac

done