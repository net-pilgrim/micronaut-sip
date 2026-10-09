#!/usr/bin/env python3
import os
import sys
import time
import datetime
import subprocess
import signal
import csv

def get_process_cpu_and_mem(pid, prev_cpu_time=None, prev_wall_time=None):
    if not pid or not os.path.exists(f"/proc/{pid}"):
        return {"rss_mb": 0.0, "vsz_mb": 0.0, "fds": 0, "cpu_pct": 0.0, "utime_stime": 0}
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
        
        # CPU from /proc/<pid>/stat
        with open(f"/proc/{pid}/stat") as f:
            stat_parts = f.read().split()
            # utime is 14th (index 13), stime is 15th (index 14)
            utime = int(stat_parts[13])
            stime = int(stat_parts[14])
            total_cpu_ticks = utime + stime
            
        cpu_pct = 0.0
        now_wall = time.time()
        clk_tck = os.sysconf(os.sysconf_names['SC_CLK_TCK'])
        if prev_cpu_time is not None and prev_wall_time is not None and (now_wall - prev_wall_time) > 0:
            delta_ticks = total_cpu_ticks - prev_cpu_time
            delta_sec = now_wall - prev_wall_time
            cpu_pct = round((delta_ticks / clk_tck / delta_sec) * 100.0, 1)

        return {
            "rss_mb": round(rss_kb / 1024.0, 2),
            "vsz_mb": round(vsz_kb / 1024.0, 2),
            "fds": fds,
            "cpu_pct": cpu_pct,
            "cpu_ticks": total_cpu_ticks,
            "wall_time": now_wall
        }
    except Exception:
        return {"rss_mb": 0.0, "vsz_mb": 0.0, "fds": 0, "cpu_pct": 0.0, "cpu_ticks": 0, "wall_time": time.time()}

def get_udp_snmp():
    try:
        with open("/proc/net/snmp") as f:
            lines = f.readlines()
        headers = []
        values = []
        for line in lines:
            if line.startswith("Udp:"):
                parts = line.split()
                if not headers:
                    headers = parts[1:]
                else:
                    values = parts[1:]
                    break
        return dict(zip(headers, [int(v) for v in values]))
    except Exception:
        return {}

def parse_sipp_stats(csv_path):
    if not os.path.exists(csv_path):
        return {}
    try:
        with open(csv_path, "r", encoding="utf-8", errors="ignore") as f:
            lines = [l.strip() for l in f if l.strip()]
        if len(lines) < 2:
            return {}
        headers = [h.strip() for h in lines[0].split(";")]
        last_row = [v.strip() for v in lines[-1].split(";")]
        return dict(zip(headers, last_row))
    except Exception:
        return {}

def main():
    import argparse
    parser = argparse.ArgumentParser(description="NetAnn Ramp Load Saturation Benchmark")
    parser.add_argument("--start-rate", type=int, default=250, help="Initial call rate (cps)")
    parser.add_argument("--rate-increase", type=int, default=5, help="Rate increase step")
    parser.add_argument("--rate-interval", type=str, default="10s", help="Rate increase interval (e.g. 10s)")
    parser.add_argument("--max-rate", type=int, default=600, help="Max call rate to quit (cps)")
    parser.add_argument("--duration-sec", type=int, default=600, help="Max test duration in seconds")
    parser.add_argument("--scenario", type=str, default="perf/uac_annc_server_bye.xml", help="SIPp XML scenario")
    parser.add_argument("--ulimit", type=int, default=65535, help="File descriptor limit")
    parser.add_argument("--target", type=str, default="native", choices=["native", "jvm"], help="NetAnn binary target")
    parser.add_argument("--output-csv", type=str, default="perf/ramp_metrics.csv", help="Output telemetry CSV")
    args = parser.parse_args()

    print(f"=== Starting Saturation Benchmark ===")
    print(f"Target: {args.target}")
    print(f"Scenario: {args.scenario}")
    print(f"Initial Rate: {args.start_rate} cps, Increase: {args.rate_increase} every {args.rate_interval}, Max: {args.max_rate} cps")
    print(f"ulimit -n: {args.ulimit}")
    print(f"Max Duration: {args.duration_sec}s")

    # Set ulimit in python process
    import resource
    resource.setrlimit(resource.RLIMIT_NOFILE, (args.ulimit, args.ulimit))

    # Clean old logs
    os.makedirs("perf", exist_ok=True)
    sipp_stats_csv = "perf/ramp_sipp_stats.csv"
    sipp_err_log = "perf/ramp_sipp_err.log"
    sipp_screen_log = "perf/ramp_sipp_screen.log"
    netann_log = "perf/ramp_netann.log"
    for f in [sipp_stats_csv, sipp_err_log, sipp_screen_log, netann_log, args.output_csv]:
        if os.path.exists(f):
            os.remove(f)

    # Launch NetAnn server
    env = os.environ.copy()
    env["LOG_LEVEL"] = "WARN"
    if args.target == "native":
        bin_path = "sip-app/build/native/nativeCompile/sip-app"
        server_cmd = [bin_path]
    else:
        env["JAVA_HOME"] = "/home/dsv/.sdkman/candidates/java/25.0.2-zulu"
        env["PATH"] = f"{env['JAVA_HOME']}/bin:{env.get('PATH', '')}"
        env["JAVA_OPTS"] = "-Xms1g -Xmx4g"
        server_cmd = ["sip-app/build/install/sip-app/bin/sip-app"]

    print(f"Starting NetAnn server: {' '.join(server_cmd)}")
    with open(netann_log, "w") as log_out:
        server_proc = subprocess.Popen(server_cmd, stdout=log_out, stderr=subprocess.STDOUT, env=env)

    server_pid = server_proc.pid
    print(f"NetAnn PID: {server_pid}. Waiting for UP status...")

    import urllib.request
    healthy = False
    for _ in range(30):
        try:
            with urllib.request.urlopen("http://127.0.0.1:8080/health", timeout=1) as resp:
                if b'"UP"' in resp.read():
                    healthy = True
                    break
        except Exception:
            pass
        time.sleep(0.5)

    if not healthy:
        print("ERROR: NetAnn failed to become healthy. Aborting.")
        server_proc.kill()
        sys.exit(1)

    print("NetAnn server is UP and healthy!")

    # Prepare SIPp command
    sipp_cmd = [
        "/home/dsv/.local/bin/sipp", "127.0.0.1:5060",
        "-sf", args.scenario,
        "-r", str(args.start_rate),
        "-rate_increase", str(args.rate_increase),
        "-rate_interval", args.rate_interval,
        "-rate_max", str(args.max_rate),
        "-l", "5000",
        "-timeout", f"{args.duration_sec}s",
        "-trace_stat", "-stf", sipp_stats_csv, "-fd", "1s",
        "-trace_err", "-error_file", sipp_err_log,
        "-trace_screen", "-screen_file", sipp_screen_log,
        "-max_socket", str(args.ulimit)
    ]

    print(f"Launching SIPp: {' '.join(sipp_cmd)}")
    with open("perf/ramp_sipp_stdout.log", "w") as sipp_out:
        sipp_proc = subprocess.Popen(sipp_cmd, stdout=sipp_out, stderr=subprocess.STDOUT)

    sipp_pid = sipp_proc.pid

    # Telemetry loop
    start_time = time.time()
    last_log_time = 0
    prev_server_cpu_ticks = None
    prev_server_wall = None

    with open(args.output_csv, "w") as out_f:
        out_f.write("timestamp_iso,elapsed_sec,active_calls,call_rate,successful_calls,failed_calls,"
                    "retrans_count,server_rss_mb,server_vsz_mb,server_fds,server_cpu_pct,sipp_rss_mb,sipp_fds,"
                    "udp_in_errors,udp_rcvbuf_errors,udp_sndbuf_errors\n")

    initial_udp = get_udp_snmp()
    print("Beginning telemetry collection...\n")
    print(f"{'Time':>6} | {'Active':>7} | {'Rate':>7} | {'Success':>8} | {'Failed':>7} | {'Retrans':>7} | {'NetAnn FDs':>10} | {'RSS (MB)':>9} | {'CPU %':>6}")
    print("-" * 85)

    try:
        while True:
            now = time.time()
            elapsed = int(now - start_time)

            if sipp_proc.poll() is not None:
                print(f"\nSIPp terminated at elapsed={elapsed}s (exit code={sipp_proc.returncode})")
                break
            if server_proc.poll() is not None:
                print(f"\nNetAnn server terminated unexpectedly at elapsed={elapsed}s (exit code={server_proc.returncode})")
                break
            if elapsed >= args.duration_sec:
                print(f"\nReached max duration {args.duration_sec}s. Stopping.")
                break

            s_info = get_process_cpu_and_mem(server_pid, prev_server_cpu_ticks, prev_server_wall)
            prev_server_cpu_ticks = s_info.get("cpu_ticks")
            prev_server_wall = s_info.get("wall_time")

            c_info = get_process_cpu_and_mem(sipp_pid)
            sipp_stats = parse_sipp_stats(sipp_stats_csv)
            curr_udp = get_udp_snmp()

            active = sipp_stats.get("CurrentCall", sipp_stats.get("Current_Call", "0"))
            rate = sipp_stats.get("CallRate", sipp_stats.get("CallRate(P)", "0"))
            success = sipp_stats.get("SuccessfulCall(C)", sipp_stats.get("Successful_Call", "0"))
            failed = sipp_stats.get("FailedCall(C)", sipp_stats.get("Failed_Call", "0"))
            retrans = sipp_stats.get("Retransmissions", sipp_stats.get("RetransCount", "0"))

            in_err = curr_udp.get("InErrors", 0) - initial_udp.get("InErrors", 0)
            rcv_err = curr_udp.get("RcvbufErrors", 0) - initial_udp.get("RcvbufErrors", 0)
            snd_err = curr_udp.get("SndbufErrors", 0) - initial_udp.get("SndbufErrors", 0)

            iso_ts = datetime.datetime.now().isoformat()
            with open(args.output_csv, "a") as out_f:
                out_f.write(f"{iso_ts},{elapsed},{active},{rate},{success},{failed},{retrans},"
                            f"{s_info['rss_mb']},{s_info['vsz_mb']},{s_info['fds']},{s_info['cpu_pct']},"
                            f"{c_info['rss_mb']},{c_info['fds']},{in_err},{rcv_err},{snd_err}\n")

            if elapsed - last_log_time >= 10:
                print(f"{elapsed:>5}s | {active:>7} | {rate:>7} | {success:>8} | {failed:>7} | {retrans:>7} | {s_info['fds']:>10} | {s_info['rss_mb']:>9} | {s_info['cpu_pct']:>5}%", flush=True)
                last_log_time = elapsed

            time.sleep(1.0)

    finally:
        # Cleanup
        if sipp_proc.poll() is None:
            sipp_proc.terminate()
            try:
                sipp_proc.wait(timeout=3)
            except Exception:
                sipp_proc.kill()

        if server_proc.poll() is None:
            print(f"Terminating NetAnn server PID {server_pid}...")
            server_proc.terminate()
            try:
                server_proc.wait(timeout=3)
            except Exception:
                server_proc.kill()

    print("\nBenchmark run completed. Parsing final statistics...")
    if os.path.exists(sipp_screen_log):
        with open(sipp_screen_log) as sf:
            lines = sf.readlines()
        print("\n--- Final SIPp Summary ---")
        for line in lines[-35:]:
            print(line, end="")

if __name__ == "__main__":
    main()
