#!/usr/bin/env python3
"""
TranzFer MFT — SFTP Protocol Benchmark
Tests concurrent uploads, downloads, large files, and bulk transfers via SFTP.

Usage:
    python3 sftp_benchmark.py --scenario small-files
    python3 sftp_benchmark.py --scenario large-file --size 1GB
    python3 sftp_benchmark.py --scenario concurrent --connections 50
    python3 sftp_benchmark.py --scenario bulk --count 10000
    python3 sftp_benchmark.py --scenario ramp-connections --start 10 --end 200 --step 10
    python3 sftp_benchmark.py --scenario full-pipeline --users 50
    python3 sftp_benchmark.py --scenario memory-pressure --concurrent 100 --file-size 100MB
    python3 sftp_benchmark.py --scenario quota-exceed
"""
import argparse
import io
import os
import sys
import time
import random
import threading
import statistics
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import List, Optional
import paramiko
from tqdm import tqdm
from tabulate import tabulate

# ── Configuration ─────────────────────────────────────────────────────────────
SFTP_HOST      = os.environ.get("SFTP_HOST",     "localhost")
SFTP_PORT      = int(os.environ.get("SFTP_PORT", "2222"))
SFTP_USER      = os.environ.get("SFTP_USER",     "admin")
SFTP_PASS      = os.environ.get("SFTP_PASS",     "Admin@1234")
SFTP_REMOTE_DIR = os.environ.get("SFTP_DIR",     "/upload")

SIZE_MAP = {
    "1KB":   1024,
    "10KB":  10 * 1024,
    "100KB": 100 * 1024,
    "1MB":   1024 * 1024,
    "10MB":  10 * 1024 * 1024,
    "100MB": 100 * 1024 * 1024,
    "1GB":   1024 * 1024 * 1024,
    "5GB":   5 * 1024 * 1024 * 1024,
    "10GB":  10 * 1024 * 1024 * 1024,
}

@dataclass
class TransferResult:
    success: bool
    duration_ms: float
    bytes_transferred: int
    error: Optional[str] = None
    throughput_mbps: float = 0.0

    def __post_init__(self):
        if self.success and self.duration_ms > 0:
            self.throughput_mbps = (self.bytes_transferred / (1024 * 1024)) / (self.duration_ms / 1000)

@dataclass
class BenchmarkStats:
    scenario: str
    total: int = 0
    succeeded: int = 0
    failed: int = 0
    durations: List[float] = field(default_factory=list)
    throughputs: List[float] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    start_time: float = field(default_factory=time.time)
    end_time: float = 0.0

    def add(self, result: TransferResult):
        self.total += 1
        if result.success:
            self.succeeded += 1
            self.durations.append(result.duration_ms)
            if result.throughput_mbps > 0:
                self.throughputs.append(result.throughput_mbps)
        else:
            self.failed += 1
            if result.error:
                self.errors.append(result.error)

    def print_report(self):
        self.end_time = time.time()
        wall_time = self.end_time - self.start_time
        error_rate = (self.failed / self.total * 100) if self.total > 0 else 0

        print(f"\n{'='*60}")
        print(f"SFTP Benchmark: {self.scenario}")
        print(f"{'='*60}")

        rows = [
            ["Total transfers",    self.total],
            ["Succeeded",          f"{self.succeeded} ({100-error_rate:.1f}%)"],
            ["Failed",             f"{self.failed} ({error_rate:.1f}%)"],
            ["Wall time",          f"{wall_time:.1f}s"],
            ["Throughput (files/s)", f"{self.succeeded/wall_time:.1f}"],
        ]

        if self.durations:
            sorted_d = sorted(self.durations)
            rows += [
                ["Latency p50",    f"{statistics.median(self.durations):.0f}ms"],
                ["Latency p95",    f"{sorted_d[int(len(sorted_d)*0.95)]:.0f}ms"],
                ["Latency p99",    f"{sorted_d[int(len(sorted_d)*0.99)]:.0f}ms"],
                ["Latency max",    f"{max(self.durations):.0f}ms"],
            ]
        if self.throughputs:
            rows += [
                ["Throughput p50 (MB/s)", f"{statistics.median(self.throughputs):.1f}"],
                ["Throughput max (MB/s)", f"{max(self.throughputs):.1f}"],
            ]

        print(tabulate(rows, tablefmt="simple"))

        if self.errors:
            unique_errors = list(set(self.errors[:5]))
            print(f"\nTop errors:")
            for e in unique_errors[:5]:
                print(f"  - {e}")

        # Pass/fail
        if error_rate < 1.0:
            print(f"\n✓ PASS — error rate {error_rate:.2f}% < 1%")
        elif error_rate < 5.0:
            print(f"\n! WARN — error rate {error_rate:.2f}%")
        else:
            print(f"\n✗ FAIL — error rate {error_rate:.2f}% > 5%")


def make_sftp_client() -> paramiko.SFTPClient:
    """Create an SFTP client connection."""
    transport = paramiko.Transport((SFTP_HOST, SFTP_PORT))
    transport.connect(username=SFTP_USER, password=SFTP_PASS)
    return paramiko.SFTPClient.from_transport(transport), transport


def generate_data(size_bytes: int) -> bytes:
    """Generate test payload of given size."""
    chunk = b"TranzFerMFTTestData1234567890ABCDEFGHIJ"
    return (chunk * (size_bytes // len(chunk) + 1))[:size_bytes]


def upload_file(file_size: int, remote_name: str = None, client_tuple=None) -> TransferResult:
    """Upload a single file. Creates its own connection if client not provided."""
    sftp = transport = None
    own_connection = client_tuple is None

    try:
        if own_connection:
            sftp, transport = make_sftp_client()
        else:
            sftp, transport = client_tuple

        data = generate_data(file_size)
        fname = remote_name or f"perf-{time.time():.6f}-{random.randint(1000,9999)}.dat"
        remote_path = f"{SFTP_REMOTE_DIR}/{fname}"

        start = time.time()
        buf = io.BytesIO(data)
        sftp.putfo(buf, remote_path)
        duration_ms = (time.time() - start) * 1000

        return TransferResult(
            success=True,
            duration_ms=duration_ms,
            bytes_transferred=file_size,
        )
    except Exception as e:
        return TransferResult(success=False, duration_ms=0, bytes_transferred=0, error=str(e))
    finally:
        if own_connection:
            try:
                if sftp: sftp.close()
                if transport: transport.close()
            except: pass


def scenario_small_files(count: int = 1000, file_size: int = SIZE_MAP["10KB"]) -> BenchmarkStats:
    """1000 concurrent small file uploads."""
    print(f"\nScenario: small-files | {count} files × {file_size//1024}KB | workers=50")
    stats = BenchmarkStats(scenario=f"small-files ({count}×{file_size//1024}KB)")

    with ThreadPoolExecutor(max_workers=50) as ex:
        futures = [ex.submit(upload_file, file_size) for _ in range(count)]
        with tqdm(total=count, desc="Uploading") as pbar:
            for f in as_completed(futures):
                stats.add(f.result())
                pbar.update(1)

    return stats


def scenario_large_file(size_label: str = "1GB") -> BenchmarkStats:
    """Upload a single large file and measure throughput."""
    size_bytes = SIZE_MAP.get(size_label, SIZE_MAP["1GB"])
    print(f"\nScenario: large-file | size={size_label} ({size_bytes//(1024*1024)}MB)")
    stats = BenchmarkStats(scenario=f"large-file ({size_label})")

    result = upload_file(size_bytes, remote_name=f"large-{size_label}-perf.dat")
    stats.add(result)

    if result.success:
        print(f"  Upload time: {result.duration_ms/1000:.1f}s")
        print(f"  Throughput:  {result.throughput_mbps:.1f} MB/s")

    return stats


def scenario_concurrent(connections: int = 50, file_size: int = SIZE_MAP["1MB"]) -> BenchmarkStats:
    """Open N simultaneous SFTP connections and upload concurrently."""
    print(f"\nScenario: concurrent | {connections} simultaneous connections | {file_size//1024}KB files")
    stats = BenchmarkStats(scenario=f"concurrent-{connections}")

    def connect_and_upload():
        return upload_file(file_size)

    with ThreadPoolExecutor(max_workers=connections) as ex:
        futures = [ex.submit(connect_and_upload) for _ in range(connections)]
        for f in as_completed(futures):
            stats.add(f.result())

    return stats


def scenario_bulk(count: int = 10000, file_size: int = SIZE_MAP["1KB"]) -> BenchmarkStats:
    """10,000 tiny files — maximum file-per-second throughput."""
    print(f"\nScenario: bulk | {count} × 1KB files | workers=100")
    stats = BenchmarkStats(scenario=f"bulk-{count}")

    with ThreadPoolExecutor(max_workers=100) as ex:
        futures = [ex.submit(upload_file, file_size) for _ in range(count)]
        with tqdm(total=count, desc="Bulk upload") as pbar:
            for f in as_completed(futures):
                stats.add(f.result())
                pbar.update(1)

    return stats


def scenario_ramp_connections(start: int, end: int, step: int, hold_seconds: int = 30) -> None:
    """Ramp from `start` to `end` concurrent connections, hold each for `hold_seconds`."""
    print(f"\nScenario: ramp-connections | {start}→{end} step={step} hold={hold_seconds}s")
    print(f"{'Connections':>12} {'Files/s':>10} {'p95 (ms)':>10} {'Error%':>8}")
    print("-" * 45)

    for n_conns in range(start, end + 1, step):
        stats = BenchmarkStats(scenario=f"ramp-{n_conns}")
        duration_end = time.time() + hold_seconds

        with ThreadPoolExecutor(max_workers=n_conns) as ex:
            while time.time() < duration_end:
                futures = [ex.submit(upload_file, SIZE_MAP["10KB"]) for _ in range(n_conns)]
                for f in as_completed(futures):
                    stats.add(f.result())

        wall = time.time() - stats.start_time
        fps = stats.succeeded / wall
        p95 = sorted(stats.durations)[int(len(stats.durations) * 0.95)] if stats.durations else 0
        err_pct = stats.failed / stats.total * 100 if stats.total else 0

        marker = "✓" if err_pct < 1 else ("!" if err_pct < 5 else "✗")
        print(f"{marker} {n_conns:>11} {fps:>10.1f} {p95:>10.0f} {err_pct:>8.1f}%")

        if err_pct > 10:
            print(f"\n  → Breaking point reached at {n_conns} connections (error rate {err_pct:.1f}%)")
            break


def scenario_memory_pressure(concurrent: int = 100, size_label: str = "100MB") -> BenchmarkStats:
    """Upload many large files simultaneously — triggers JVM heap pressure."""
    size_bytes = SIZE_MAP.get(size_label, SIZE_MAP["100MB"])
    print(f"\nScenario: memory-pressure | {concurrent} × {size_label} concurrently")
    print("Watch: docker stats --no-stream | grep mft")
    stats = BenchmarkStats(scenario=f"memory-pressure-{concurrent}×{size_label}")

    with ThreadPoolExecutor(max_workers=concurrent) as ex:
        futures = [ex.submit(upload_file, size_bytes) for _ in range(concurrent)]
        with tqdm(total=concurrent, desc="Memory pressure") as pbar:
            for f in as_completed(futures):
                stats.add(f.result())
                pbar.update(1)

    return stats


def scenario_quota_exceed() -> None:
    """Attempt to upload files exceeding account quota — should be rejected."""
    print("\nScenario: quota-exceed | attempting to exceed storage quota")
    sftp = transport = None
    try:
        sftp, transport = make_sftp_client()
        total_bytes = 0
        file_count  = 0
        max_attempts = 1000

        for i in range(max_attempts):
            size = SIZE_MAP["10MB"]
            try:
                result = upload_file(size, client_tuple=(sftp, transport))
                if result.success:
                    total_bytes += size
                    file_count  += 1
                else:
                    print(f"\n  ✓ Quota enforced after {file_count} files ({total_bytes//(1024*1024)}MB): {result.error}")
                    return
            except Exception as e:
                print(f"\n  ✓ Quota enforced after {file_count} files ({total_bytes//(1024*1024)}MB): {e}")
                return

        print(f"\n  ! WARN: Uploaded {file_count} × 10MB ({total_bytes//(1024*1024)}MB total) without quota rejection")
    finally:
        try:
            if sftp: sftp.close()
            if transport: transport.close()
        except: pass


def main():
    parser = argparse.ArgumentParser(description="TranzFer MFT SFTP Benchmark")
    parser.add_argument("--scenario", required=True,
        choices=["small-files", "large-file", "concurrent", "bulk",
                 "ramp-connections", "full-pipeline", "memory-pressure", "quota-exceed"])
    parser.add_argument("--count",        type=int,   default=1000)
    parser.add_argument("--connections",  type=int,   default=50)
    parser.add_argument("--size",         type=str,   default="1GB")
    parser.add_argument("--file-size",    type=str,   default="10KB")
    parser.add_argument("--users",        type=int,   default=50)
    parser.add_argument("--start",        type=int,   default=10)
    parser.add_argument("--end",          type=int,   default=200)
    parser.add_argument("--step",         type=int,   default=10)
    parser.add_argument("--hold-seconds", type=int,   default=30)
    parser.add_argument("--concurrent",   type=int,   default=100)
    args = parser.parse_args()

    print(f"SFTP Benchmark → {SFTP_HOST}:{SFTP_PORT} (user: {SFTP_USER})")

    if args.scenario == "small-files":
        stats = scenario_small_files(count=args.count, file_size=SIZE_MAP.get(args.file_size, SIZE_MAP["10KB"]))
        stats.print_report()
    elif args.scenario == "large-file":
        stats = scenario_large_file(size_label=args.size)
        stats.print_report()
    elif args.scenario == "concurrent":
        stats = scenario_concurrent(connections=args.connections, file_size=SIZE_MAP.get(args.file_size, SIZE_MAP["1MB"]))
        stats.print_report()
    elif args.scenario == "bulk":
        stats = scenario_bulk(count=args.count, file_size=SIZE_MAP.get(args.file_size, SIZE_MAP["1KB"]))
        stats.print_report()
    elif args.scenario == "ramp-connections":
        scenario_ramp_connections(start=args.start, end=args.end, step=args.step, hold_seconds=args.hold_seconds)
    elif args.scenario == "memory-pressure":
        stats = scenario_memory_pressure(concurrent=args.concurrent, size_label=args.file_size)
        stats.print_report()
    elif args.scenario == "quota-exceed":
        scenario_quota_exceed()
    elif args.scenario == "full-pipeline":
        # Full pipeline: run concurrent uploads and track file transfer status
        print(f"\nScenario: full-pipeline | {args.users} concurrent SFTP uploads")
        stats = scenario_concurrent(connections=args.users, file_size=SIZE_MAP["100KB"])
        stats.print_report()


if __name__ == "__main__":
    main()
