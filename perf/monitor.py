#!/usr/bin/env python3
import os
import sys
import time
import datetime
import subprocess

def get_process_info(pid):
    if not pid or not os.path.exists(f"/proc/{pid}"):
        return {"rss_mb": 0.0, "vsz_mb": 0.0, "fds": 0, "cpu_pct": 0.0}
    try:
        # Memory & stats from /proc/<pid>/status
        with open(f"/proc/{pid}/status") as f:
            status_text = f.read()
        rss_kb = 0
        vsz_kb = 0
        for line in status_text.splitlines():
            if line.startswith("VmRSS:"):
                rss_kb = int(line.split()[1])
            elif line.startswith("VmSize:"):
                vsz_kb = int(line.split()[1])
        fds = len(os.listdir(f"/proc/{pid}/fd"))
        return {
            "rss_mb": round(rss_kb / 1024.0, 2),
            "vsz_mb": round(vsz_kb / 1024.0, 2),
            "fds": fds
        }
    except Exception:
        return {"rss_mb": 0.0, "vsz_mb": 0.0, "fds": 0}

def get_sipp_latest_stats(csv_path):
    if not os.path.exists(csv_path):
        return {}
    try:
        with open(csv_path, "r", encoding="utf-8", errors="ignore") as f:
            lines = [l.strip() for l in f if l.strip()]
        if len(lines) < 2:
            return {}
        headers = lines[0].split(";")
        last_row = lines[-1].split(";")
        stats = {}
        for h, v in zip(headers, last_row):
            h_clean = h.strip()
            v_clean = v.strip()
            stats[h_clean] = v_clean
        return stats
    except Exception:
        return {}

def main():
    if len(sys.argv) < 3:
        print("Usage: monitor.py <server_pid> <sipp_pid> [duration_seconds]")
        sys.exit(1)

    server_pid = int(sys.argv[1])
    sipp_pid = int(sys.argv[2])
    duration = int(sys.argv[3]) if len(sys.argv) > 3 else 3660

    csv_path = "perf/perf_metrics.csv"
    sipp_csv_path = "perf/sipp_stats.csv"

    print(f"[{datetime.datetime.now().isoformat()}] Starting NetAnn Perf Monitor: Server PID={server_pid}, SIPp PID={sipp_pid}, Target Duration={duration}s")
    with open(csv_path, "w") as out:
        out.write("timestamp_iso,elapsed_sec,server_rss_mb,server_vsz_mb,server_fds,sipp_rss_mb,sipp_fds,active_calls,successful_calls,failed_calls,call_rate\n")
        out.flush()

    start_time = time.time()
    last_log_time = 0

    while True:
        now = time.time()
        elapsed = int(now - start_time)

        # Check if processes are alive
        server_alive = os.path.exists(f"/proc/{server_pid}")
        sipp_alive = os.path.exists(f"/proc/{sipp_pid}")

        s_info = get_process_info(server_pid)
        c_info = get_process_info(sipp_pid)
        sipp_stats = get_sipp_latest_stats(sipp_csv_path)

        active_calls = sipp_stats.get("CurrentCall", sipp_stats.get("Current_Call", "N/A"))
        successful_calls = sipp_stats.get("SuccessfulCall(C)", sipp_stats.get("Successful_Call", "N/A"))
        failed_calls = sipp_stats.get("FailedCall(C)", sipp_stats.get("Failed_Call", "0"))
        call_rate = sipp_stats.get("CallRate", "N/A")

        iso_ts = datetime.datetime.now().isoformat()
        with open(csv_path, "a") as out:
            out.write(f"{iso_ts},{elapsed},{s_info['rss_mb']},{s_info['vsz_mb']},{s_info['fds']},{c_info['rss_mb']},{c_info['fds']},{active_calls},{successful_calls},{failed_calls},{call_rate}\n")
            out.flush()

        if elapsed - last_log_time >= 60:
            print(f"[{iso_ts}] Elapsed: {elapsed}s/{duration}s | Active Calls: {active_calls} | Success: {successful_calls} | Failed: {failed_calls} | Rate: {call_rate} cps | Server RSS: {s_info['rss_mb']}MB (FDs: {s_info['fds']}) | SIPp RSS: {c_info['rss_mb']}MB", flush=True)
            last_log_time = elapsed

        if not sipp_alive or not server_alive:
            print(f"[{datetime.datetime.now().isoformat()}] Monitored process terminated (server_alive={server_alive}, sipp_alive={sipp_alive}). Final elapsed: {elapsed}s", flush=True)
            break

        if elapsed >= duration:
            print(f"[{datetime.datetime.now().isoformat()}] Reached target duration of {duration}s. Monitor completed.", flush=True)
            break

        time.sleep(10)

if __name__ == "__main__":
    main()
