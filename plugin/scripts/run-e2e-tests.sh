#!/bin/bash
#
# PyCharm Agent E2E Test Runner
#
# This script:
# 1. Starts the PyCharm test instance with Robot Server
# 2. Waits for the IDE to be ready
# 3. Runs E2E tests
# 4. Opens the HTML report
# 5. Cleans up
#
# Usage: ./run-e2e-tests.sh [--keep-ide] [--no-report]
#   --keep-ide    Don't stop the IDE after tests
#   --no-report   Don't open the HTML report automatically
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_DIR="$(dirname "$PLUGIN_DIR")"

ROBOT_PORT=8082
ROBOT_URL="http://127.0.0.1:$ROBOT_PORT"
IDE_PID=""
KEEP_IDE=false
OPEN_REPORT=true

# Parse arguments
for arg in "$@"; do
    case $arg in
        --keep-ide)
            KEEP_IDE=true
            shift
            ;;
        --no-report)
            OPEN_REPORT=false
            shift
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

cleanup() {
    if [ "$KEEP_IDE" = false ] && [ -n "$IDE_PID" ]; then
        log_info "Stopping IDE (PID: $IDE_PID)..."
        kill "$IDE_PID" 2>/dev/null || true
        wait "$IDE_PID" 2>/dev/null || true
    fi
}

trap cleanup EXIT

wait_for_robot_server() {
    local max_attempts=60
    local attempt=0

    log_info "Waiting for Robot Server at $ROBOT_URL..."

    while [ $attempt -lt $max_attempts ]; do
        if curl -s "$ROBOT_URL" > /dev/null 2>&1; then
            log_info "Robot Server is ready!"
            return 0
        fi
        attempt=$((attempt + 1))
        echo -n "."
        sleep 2
    done

    echo ""
    log_error "Robot Server did not start within timeout"
    return 1
}

check_backend() {
    log_info "Checking if backend is running on localhost:8000..."
    if curl -s "http://localhost:8000/health" > /dev/null 2>&1; then
        log_info "Backend is running"
        return 0
    else
        log_warn "Backend is not running. Some tests may fail."
        log_warn "Start backend with: cd backend && python -m uvicorn main:app --port 8000"
        return 1
    fi
}

start_ide() {
    log_info "Starting PyCharm with Robot Server plugin..."
    cd "$PLUGIN_DIR"

    # Download Robot Server plugin if needed
    ./gradlew downloadRobotServerPlugin --quiet

    # Start IDE for UI tests in background
    ./gradlew runIdeForUiTests &
    IDE_PID=$!

    log_info "IDE starting (PID: $IDE_PID)..."
}

run_tests() {
    log_info "Running E2E tests..."
    cd "$PLUGIN_DIR"

    # Run only E2E tests
    if ./gradlew test --tests "*E2E*" --info; then
        log_info "All E2E tests passed!"
        return 0
    else
        log_error "Some E2E tests failed"
        return 1
    fi
}

open_report() {
    local report_path="$HOME/repo/hdsp_agent/reports/test/index.html"

    if [ -f "$report_path" ]; then
        log_info "Opening HTML report..."
        if command -v open &> /dev/null; then
            open "$report_path"
        elif command -v xdg-open &> /dev/null; then
            xdg-open "$report_path"
        else
            log_info "Report available at: $report_path"
        fi
    else
        log_warn "Report not found at: $report_path"
    fi
}

main() {
    log_info "=== PyCharm Agent E2E Test Runner ==="
    echo ""

    # Check if IDE is already running with Robot Server
    if curl -s "$ROBOT_URL" > /dev/null 2>&1; then
        log_info "Robot Server already running, skipping IDE start"
    else
        start_ide
        wait_for_robot_server || exit 1
    fi

    # Optional: Check backend
    check_backend || true

    # Run tests
    local test_result=0
    run_tests || test_result=$?

    # Open report if requested
    if [ "$OPEN_REPORT" = true ]; then
        open_report
    fi

    echo ""
    if [ $test_result -eq 0 ]; then
        log_info "=== E2E Tests Completed Successfully ==="
    else
        log_error "=== E2E Tests Failed ==="
    fi

    if [ "$KEEP_IDE" = true ]; then
        log_info "IDE kept running. Stop manually with: kill $IDE_PID"
    fi

    exit $test_result
}

main "$@"
