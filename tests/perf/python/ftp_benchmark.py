#!/usr/bin/env python3
"""
TranzFer MFT — FTP Protocol Benchmark
Tests passive mode uploads, concurrent connections, and bulk transfers.

Usage:
    python3 ftp_benchmark.py --scenario passive-upload
    python3 ftp_benchmark.py --scenario concurrent --connections 30
    python3 ftp_benchmark.py --scenario bulk --count 5000
"""
import argparse
import ftplib
import io
import os
import random
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import List, Optional

from tqdm import tqdm
from tabulate import tabulate

FTP_HOST = os.environ.get("FTP_HOST", "localhost")
FTP_PORT = int(os.environ.get("FTP_PORT", "21"))
FTP_USER = os.environ.get("FTP_USER", "admin")
FTP_PASS = os.environ.get("FTP_PASS", "Admin@1234")
FTP_DIR  = os.environ.get("FTP_DIR",  "/upload")

SIZE_MAP = {
    "1KB":   1024,
    "10KB":  10 * 1024,
    "100KB": 100 * 1024,
    "1MB":   1024 * 1024,
    "10MB":  10 * 1024 * 1024,
    "100MB": 100 * 1024 * 1024,
}

@dataclass
class FTPResult:
    success: bool
    duration_ms: float
    bytes_transferred: int
    error: Optional[str] = None
    throughput_mbps: float = 0.0

    def __post_init__(self):
        if self.success and self.duration_ms > 0:
            self.throughput_mbps = (self.bytes_transferred / (1024 * 1024)) / (self.duration_ms / 1000)

@dataclass
class FTPStats:
    scenario: str
    results: List[FTPResult] = field(default_factory=list)
    start_time: float = field(default_factory=time.time)

    def add(self, r: FTPResult):
        self.results.append(r)

    def print_report(self):
        total     = len(self.results)
        succeeded = sum(1 for r in self.results if r.success)
        failed    = total - succeeded
        durations = [r.duration_ms for r in self.results if r.success]
        throughputs = [r.throughput_mbps for r in self.results if r.success and r.throughput_mbps > 0]
        wall_time = time.time() - self.start_time
        error_rate = (failed / total * 100) if total > 0 else 0

        print(f"\n{'='*55}")
        print(f"FTP Benchmark: {self.scenario}")
        print(f"{'='*55}")

        rows = [
            ["Total",       total],
            ["Succeeded",   f"{succeeded} ({100-error_rate:.1f}%)"],
            ["Failed",      f"{failed} ({error_rate:.1f}%)"],
            ["Wall time",   f"{wall_time:.1f}s"],
            ["Files/sec",   f"{succeeded/wall_time:.1f}" if wall_time > 0 else "N/A"],
        ]
        if durations:
            sd = sorted(durations)
            rows += [
                ["p50 latency", f"{statistics.median(durations):.0f}ms"],
                ["p95 latency", f"{sd[int(len(sd)*0.95)]:.0f}ms"],
                ["p99 latency", f"{sd[int(len(sd)*0.99)]:.0f}ms"],
            ]
        if throughputs:
            rows.append(["Throughput median", f"{statistics.median(throughputs):.1f} MB/s"])

        print(tabulate(rows, tablefmt="simple"))

        if error_rate < 1.0:
            print(f"\n✓ PASS — error rate {error_rate:.2f}% < 1%")
        elif error_rate < 5.0:
            print(f"\n! WARN — error rate {error_rate:.2f}%")
        else:
            print(f"\n✗ FAIL — error rate {error_rate:.2f}% > 5%")


def ftp_upload(file_size: int) -> FTPResult:
    """Upload one file via FTP passive mode."""
    ftp = None
    try:
        ftp = ftplib.FTP()
        ftp.connect(FTP_HOST, FTP_PORT, timeout=30)
        ftp.login(FTP_USER, FTP_PASS)
        ftp.set_pasv(True)

        try:
            ftp.cwd(FTP_DIR)
        except ftplib.error_perm:
            pass

        chunk = b"FTPTestData1234567890ABCDEFGHIJ"
        data  = (chunk * (file_size // len(chunk) + 1))[:file_size]
        fname = f"ftp-perf-{time.time():.6f}-{random.randint(1000,9999)}.dat"

        start = time.time()
        ftp.storbinary(f"STOR {fname}", io.BytesIO(data))
        duration_ms = (time.time() - start) * 1000

        return FTPResult(success=True, duration_ms=duration_ms, bytes_transferred=file_size)
    except Exception as e:
        return FTPResult(success=False, duration_ms=0, bytes_transferred=0, error=str(e)[:80])
    finally:
        try:
            if ftp: ftp.quit()
        except: pass


def scenario_passive_upload(count: int = 100, size: int = SIZE_MAP["1MB"]) -> FTPStats:
    print(f"\nFTP Scenario: passive-upload | {count} × {size//1024}KB")
    stats = FTPStats(scenario=f"passive-upload-{count}")

    for i in tqdm(range(count), desc="FTP upload"):
        stats.add(ftp_upload(size))
        time.sleep(0.05)

    return stats


def scenario_concurrent(connections: int = 30, size: int = SIZE_MAP["100KB"]) -> FTPStats:
    print(f"\nFTP Scenario: concurrent | {connections} simultaneous connections")
    stats = FTPStats(scenario=f"concurrent-{connections}")

    with ThreadPoolExecutor(max_workers=connections) as ex:
        futures = [ex.submit(ftp_upload, size) for _ in range(connections)]
        for f in as_completed(futures):
            stats.add(f.result())

    return stats


def scenario_bulk(count: int = 5000, size: int = SIZE_MAP["1KB"]) -> FTPStats:
    print(f"\nFTP Scenario: bulk | {count} × 1KB files | workers=50")
    stats = FTPStats(scenario=f"bulk-{count}")

    with ThreadPoolExecutor(max_workers=50) as ex:
        futures = [ex.submit(ftp_upload, size) for _ in range(count)]
        with tqdm(total=count, desc="FTP bulk") as pbar:
            for f in as_completed(futures):
                stats.add(f.result())
                pbar.update(1)

    return stats


def main():
    parser = argparse.ArgumentParser(description="TranzFer MFT FTP Benchmark")
    parser.add_argument("--scenario",    required=True,
                        choices=["passive-upload", "concurrent", "bulk"])
    parser.add_argument("--count",       type=int, default=100)
    parser.add_argument("--connections", type=int, default=30)
    parser.add_argument("--file-size",   type=str, default="1MB")
    args = parser.parse_args()

    print(f"FTP Benchmark → {FTP_HOST}:{FTP_PORT} (user: {FTP_USER})")
    size = SIZE_MAP.get(args.file_size, SIZE_MAP["1MB"])

    if args.scenario == "passive-upload":
        scenario_passive_upload(count=args.count, size=size).print_report()
    elif args.scenario == "concurrent":
        scenario_concurrent(connections=args.connections, size=size).print_report()
    elif args.scenario == "bulk":
        scenario_bulk(count=args.count, size=size).print_report()


if __name__ == "__main__":
    main()
