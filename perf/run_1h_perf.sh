#!/usr/bin/env bash
set -euo pipefail

cd /home/dsv/work/sip

TARGET="${1:-native}"

echo "=== Preparing 1-Hour Performance Test (Target: $TARGET) ==="
ulimit -n 65535
echo "File descriptor limit: $(ulimit -n)"

# Ensure clean logs directory
mkdir -p perf
rm -f perf/perf_metrics.csv perf/sipp_stats.csv perf/sipp_errors.log perf/sipp_screen.log perf/netann.log perf/sipp_out.log

export LOG_LEVEL=WARN

if [ "$TARGET" = "native" ]; then
    echo "=== Starting NetAnn Server (GraalVM Native Image) ==="
    if [ ! -f "micronaut-netann/build/native/nativeCompile/micronaut-netann" ]; then
        echo "Error: Native executable micronaut-netann/build/native/nativeCompile/micronaut-netann not found!"
        exit 1
    fi
    ./micronaut-netann/build/native/nativeCompile/micronaut-netann > perf/netann.log 2>&1 &
    SERVER_PID=$!
elif [ "$TARGET" = "jvm" ]; then
    echo "=== Starting NetAnn Server (JVM Zulu 25) ==="
    export JAVA_HOME=/home/dsv/.sdkman/candidates/java/25.0.2-zulu
    export PATH=$JAVA_HOME/bin:$PATH
    export JAVA_OPTS="-Xms1g -Xmx4g"
    micronaut-netann/build/install/micronaut-netann/bin/micronaut-netann > perf/netann.log 2>&1 &
    SERVER_PID=$!
else
    echo "Unknown target: $TARGET. Use 'native' or 'jvm'."
    exit 1
fi

echo "NetAnn server started with PID: $SERVER_PID"

# Wait for server to be healthy
echo "Waiting for NetAnn server to initialize..."
for i in {1..30}; do
    if curl -s http://127.0.0.1:8080/health | grep -q '"status":"UP"'; then
        echo "NetAnn server is UP and healthy!"
        break
    fi
    sleep 1
done

if ! curl -s http://127.0.0.1:8080/health | grep -q '"status":"UP"'; then
    echo "ERROR: NetAnn server failed to start!"
    kill -9 $SERVER_PID 2>/dev/null || true
    cat perf/netann.log
    exit 1
fi

echo "=== Launching SIPp Load Generator ==="
echo "Target: 50 cps, 2.0s duration, ~100 concurrent calls, 180,000 calls (1 hour)"

# Launch SIPp in background
/home/dsv/.local/bin/sipp 127.0.0.1:5060 \
    -sf perf/uac_annc_server_bye.xml \
    -r 50 \
    -l 130 \
    -m 180000 \
    -timeout 3610s \
    -trace_stat -stf perf/sipp_stats.csv -fd 10s \
    -trace_err -error_file perf/sipp_errors.log \
    -trace_screen -screen_file perf/sipp_screen.log \
    -trace_rtt \
    -max_socket 65535 \
    > perf/sipp_out.log 2>&1 &

SIPP_PID=$!
echo "SIPp started with PID: $SIPP_PID"

# Run monitor script for 3630 seconds
echo "=== Running Metric Monitor ==="
python3 perf/monitor.py "$SERVER_PID" "$SIPP_PID" 3630

echo "=== Test Completed. Shutting down... ==="
wait "$SIPP_PID" 2>/dev/null || true
mv -f uac_annc_server_bye_*_rtt.csv perf/ 2>/dev/null || true

# Check if NetAnn server is still alive
if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "Shutting down NetAnn server PID: $SERVER_PID"
    kill "$SERVER_PID" 2>/dev/null || true
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null || true
fi

echo "=== Performance Test Finished Successfully ==="
python3 perf/analyze_results.py
