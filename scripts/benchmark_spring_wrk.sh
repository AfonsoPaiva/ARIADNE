#!/usr/bin/env bash
# ==============================================================================
# ARIADNE SPRING BOOT WRK / LOAD BENCHMARK RUNNER
# Evaluates HTTP throughput (req/sec) and p99 tail latency with & without Java Agent
# ==============================================================================

set -euo pipefail

PORT=8089
ENDPOINT="http://localhost:${PORT}/api/order"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "================================================================================"
echo "          ARIADNE SPRING BOOT LOAD BENCHMARK (WRK / JMETER RUNNER)              "
echo "================================================================================"

# 1. Package projects
echo ">>> Building Ariadne Agent and Spring Boot Demo..."
mvn -B clean package -DskipTests -pl ariadne-agent,examples/spring-boot-demo -am

AGENT_JAR=$(find "${ROOT_DIR}/ariadne-agent/target" -name "ariadne-agent-*.jar" ! -name "*sources*" ! -name "*javadoc*" | head -n 1)
DEMO_JAR=$(find "${ROOT_DIR}/examples/spring-boot-demo/target" -name "spring-boot-demo-*.jar" ! -name "original-*" | head -n 1)

if [[ ! -f "${AGENT_JAR}" ]] || [[ ! -f "${DEMO_JAR}" ]]; then
    echo "ERROR: Artifact jars not found!"
    exit 1
fi

echo "Agent JAR: ${AGENT_JAR}"
echo "Demo JAR:  ${DEMO_JAR}"

# Function to run load generator (wrk, hey, or python fallback)
run_load_test() {
    local label="$1"
    echo "--------------------------------------------------------------------------------"
    echo "  Running load test for: ${label}"
    echo "--------------------------------------------------------------------------------"
    if command -v wrk &> /dev/null; then
        wrk -t4 -c50 -d10s --latency "${ENDPOINT}"
    elif command -v hey &> /dev/null; then
        hey -z 10s -c 50 "${ENDPOINT}"
    else
        echo "Note: neither 'wrk' nor 'hey' found in PATH. Using embedded python concurrent load tester..."
        python3 - <<EOF
import urllib.request
import time
import concurrent.futures
import statistics

url = "${ENDPOINT}"
concurrency = 20
duration_sec = 8
latencies = []
start_time = time.time()
req_count = 0

def worker():
    global req_count
    while time.time() - start_time < duration_sec:
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                response.read()
            latencies.append((time.perf_counter() - t0) * 1000.0) # ms
            req_count += 1
        except Exception:
            pass

with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
    futures = [executor.submit(worker) for _ in range(concurrency)]
    concurrent.futures.wait(futures)

elapsed = time.time() - start_time
latencies.sort()
if latencies:
    p50 = statistics.median(latencies)
    p90 = latencies[int(len(latencies) * 0.90)]
    p99 = latencies[int(len(latencies) * 0.99)]
    tput = len(latencies) / elapsed
    print(f"Total Requests: {len(latencies):,}")
    print(f"Throughput:     {tput:,.1f} req/sec")
    print(f"Mean Latency:   {statistics.mean(latencies):.2f} ms")
    print(f"p50 Latency:    {p50:.2f} ms")
    print(f"p90 Latency:    {p90:.2f} ms")
    print(f"p99 Latency:    {p99:.2f} ms")
    print(f"Max Latency:    {latencies[-1]:.2f} ms")
EOF
    fi
}

wait_for_server() {
    for i in {1..30}; do
        if curl -s "${ENDPOINT}" > /dev/null 2>&1; then
            return 0
        fi
        sleep 0.2
    done
    echo "ERROR: Server failed to start on port ${PORT} within 6 seconds!"
    return 1
}

# --- TEST 1: BASELINE (NO AGENT) ---
echo ""
echo ">>> [1/2] Starting Spring Boot Demo (BASELINE - No Agent)..."
java -jar "${DEMO_JAR}" server "${PORT}" > /tmp/baseline_demo.log 2>&1 &
BASELINE_PID=$!
trap "kill -9 ${BASELINE_PID} >/dev/null 2>&1 || true" EXIT

wait_for_server
run_load_test "BASELINE (Standard JVM)"

kill -9 "${BASELINE_PID}" >/dev/null 2>&1 || true
sleep 1

# --- TEST 2: ARIADNE AGENT ATTACHED ---
echo ""
echo ">>> [2/2] Starting Spring Boot Demo (ARIADNE AGENT ATTACHED)..."
java -javaagent:"${AGENT_JAR}" -jar "${DEMO_JAR}" server "${PORT}" > /tmp/agent_demo.log 2>&1 &
AGENT_PID=$!
trap "kill -9 ${AGENT_PID} >/dev/null 2>&1 || true" EXIT

wait_for_server
run_load_test "ARIADNE AGENT (Causal Tracking Active)"

kill -9 "${AGENT_PID}" >/dev/null 2>&1 || true
echo ""
echo "================================================================================"
echo "LOAD BENCHMARK COMPLETE"
echo "================================================================================"
