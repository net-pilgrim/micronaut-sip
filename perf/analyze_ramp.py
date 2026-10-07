#!/usr/bin/env python3
import csv
import sys
import os

def analyze(csv_file="perf/ramp_metrics.csv"):
    if not os.path.exists(csv_file):
        print(f"File {csv_file} not found.")
        return

    rows = []
    with open(csv_file) as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(r)

    if not rows:
        print("No data in CSV.")
        return

    print("=== Ramp Load Saturation Benchmark Analysis ===")
    print(f"Total telemetry data points: {len(rows)}")

    # Filter to periods before traffic ended
    active_rows = [r for r in rows if float(r["call_rate"]) > 0 or int(r["active_calls"]) > 100]

    max_active = max(int(r["active_calls"]) for r in rows)
    max_rate = max(float(r["call_rate"]) for r in rows)
    max_fds = max(int(r["server_fds"]) for r in rows)
    max_rss = max(float(r["server_rss_mb"]) for r in rows)
    max_cpu = max(float(r["server_cpu_pct"]) for r in rows)
    final_success = int(rows[-1]["successful_calls"])
    final_failed = int(rows[-1]["failed_calls"])
    final_rcv_err = int(rows[-1]["udp_rcvbuf_errors"])

    # Find the saturation point: the first second where failed_calls > 0 or udp_rcvbuf_errors jumped
    saturation_row = None
    for i, r in enumerate(rows):
        if int(r["failed_calls"]) > 0 or int(r["udp_rcvbuf_errors"]) > 1000:
            saturation_row = r
            sat_index = i
            break

    print(f"\n--- Milestone Summary ---")
    print(f"• Total Completed Calls: {final_success:,}")
    print(f"• Peak Active Concurrency: {max_active} calls")
    print(f"• Peak Call Arrival Rate: {max_rate:.1f} cps")
    print(f"• Peak Media Sockets (FDs): {max_fds} file descriptors")
    print(f"• Peak Server RSS: {max_rss:.1f} MB")
    print(f"• Peak Server CPU: {max_cpu:.1f}%")

    if saturation_row:
        prev_row = rows[sat_index - 1]
        print(f"\n--- Saturation Inception Point ---")
        print(f"• Timestamp: {saturation_row['timestamp_iso']} (Elapsed: {saturation_row['elapsed_sec']}s)")
        print(f"• Concurrency Before Saturation: {prev_row['active_calls']} concurrent calls")
        print(f"• Rate Before Saturation: {prev_row['call_rate']} cps")
        print(f"• FDs Before Saturation: {prev_row['server_fds']} sockets")
        print(f"• Kernel UDP Rcvbuf Errors at Saturation: {saturation_row['udp_rcvbuf_errors']}")
        print(f"• Failed Calls at Inception: {saturation_row['failed_calls']}")

    # Print interval table every 30s
    print(f"\n--- Concurrency Progression (Every 30s) ---")
    print(f"{'Elapsed':>8} | {'Active Calls':>12} | {'Rate (cps)':>10} | {'Success':>10} | {'Failed':>7} | {'NetAnn FDs':>10} | {'RSS (MB)':>9} | {'CPU %':>6} | {'UDP Drops':>9}")
    print("-" * 105)
    for i, r in enumerate(rows):
        sec = int(r["elapsed_sec"])
        if sec % 30 == 0 and sec <= 480:
            print(f"{sec:>7}s | {r['active_calls']:>12} | {float(r['call_rate']):>10.1f} | {int(r['successful_calls']):>10} | {int(r['failed_calls']):>7} | {r['server_fds']:>10} | {float(r['server_rss_mb']):>9.1f} | {float(r['server_cpu_pct']):>5.1f}% | {int(r['udp_rcvbuf_errors']):>9}")

if __name__ == "__main__":
    analyze()
