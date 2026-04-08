#!/usr/bin/env python3
"""
TranzFer MFT — VFS (Virtual File System) Management Benchmark
Tests the config-service VFS REST API which manages storage buckets, intents,
account storage quotas, and the virtual storage dashboard.

The VFS layer underpins all SFTP/FTP file operations. This benchmark measures
API responsiveness under concurrent load for the REST management surface.

Endpoints under test (all on config-service, port 8084):
  GET /api/vfs/dashboard          — aggregate storage metrics
  GET /api/vfs/buckets            — storage bucket list
  GET /api/vfs/intents/health     — storage intent health
  GET /api/vfs/intents/recent     — recently active intents
  GET /api/vfs/accounts/{id}/usage — per-account quota usage

Usage:
    python3 vfs_benchmark.py --scenario dashboard          # Dashboard query load
    python3 vfs_benchmark.py --scenario buckets            # Bucket listing throughput
    python3 vfs_benchmark.py --scenario intents            # Intent health polling
    python3 vfs_benchmark.py --scenario concurrent-reads  # All endpoints, high concurrency
    python3 vfs_benchmark.py --scenario all               # Run all scenarios
"""
import argparse
import asyncio
import os
import statistics
import time
from dataclasses import dataclass, field
from typing import List, Optional

import aiohttp
from tqdm import tqdm
from tabulate import tabulate

BASE_URL     = os.environ.get("MFT_BASE_URL",    "http://localhost")
CONFIG_PORT  = int(os.environ.get("CONFIG_PORT", "8084"))
ONBOARD_PORT = int(os.environ.get("ONBOARD_PORT", "8080"))
ADMIN_EMAIL  = os.environ.get("MFT_ADMIN_EMAIL", "admin@filetransfer.local")
ADMIN_PASS   = os.environ.get("MFT_ADMIN_PASS",  "Admin@1234")

CONFIG_BASE  = f"{BASE_URL}:{CONFIG_PORT}"
ONBOARD_BASE = f"{BASE_URL}:{ONBOARD_PORT}"


@dataclass
class VFSStats:
    scenario: str
    durations: List[float] = field(default_factory=list)
    errors: int = 0
    start_time: float = field(default_factory=time.time)

    def record(self, dur: float, ok: bool):
        self.durations.append(dur)
        if not ok:
            self.errors += 1

    def print_report(self):
        total = len(self.durations)
        wall  = time.time() - self.start_time
        err_pct = (self.errors / total * 100) if total > 0 else 0

        print(f"\n{'='*55}")
        print(f"VFS Benchmark: {self.scenario}")
        print(f"{'='*55}")

        rows = [["Total ops", total], ["Errors", f"{self.errors} ({err_pct:.1f}%)"]]
        if self.durations:
            sd = sorted(self.durations)
            p95_idx = int(len(sd) * 0.95)
            p99_idx = int(len(sd) * 0.99)
            rows += [
                ["p50",   f"{statistics.median(self.durations):.1f}ms"],
                ["p95",   f"{sd[p95_idx]:.1f}ms"],
                ["p99",   f"{sd[min(p99_idx, len(sd)-1)]:.1f}ms"],
                ["max",   f"{max(self.durations):.1f}ms"],
                ["ops/s", f"{total/wall:.1f}"],
            ]

        print(tabulate(rows, tablefmt="simple"))

        p95 = sorted(self.durations)[int(len(self.durations)*0.95)] if self.durations else 9999
        if err_pct < 1 and p95 < 200:
            print("\n✓ PASS")
        elif err_pct < 5 and p95 < 500:
            print(f"\n! WARN — p95={p95:.0f}ms, errors={err_pct:.1f}%")
        else:
            print(f"\n✗ FAIL — p95={p95:.0f}ms, errors={err_pct:.1f}%")


async def get_token(session: aiohttp.ClientSession) -> Optional[str]:
    try:
        async with session.post(
            f"{ONBOARD_BASE}/api/auth/login",
            json={"email": ADMIN_EMAIL, "password": ADMIN_PASS},
            timeout=aiohttp.ClientTimeout(total=10)
        ) as r:
            if r.status == 200:
                body = await r.json()
                return body.get("accessToken") or body.get("token")
    except Exception:
        pass
    return None


async def get_accounts(session: aiohttp.ClientSession, token: str) -> List[str]:
    """Fetch a sample of account IDs for per-account usage queries."""
    try:
        headers = {"Authorization": f"Bearer {token}"}
        async with session.get(
            f"{ONBOARD_BASE}/api/accounts?page=0&size=10",
            headers=headers,
            timeout=aiohttp.ClientTimeout(total=5)
        ) as r:
            if r.status == 200:
                body = await r.json()
                items = body if isinstance(body, list) else body.get("content", [])
                return [a["id"] for a in items if "id" in a]
    except Exception:
        pass
    return []


async def vfs_get(session: aiohttp.ClientSession, token: str, path: str) -> tuple:
    """GET a VFS endpoint, return (ok, latency_ms)."""
    start = time.time()
    headers = {"Authorization": f"Bearer {token}"}
    try:
        async with session.get(
            f"{CONFIG_BASE}{path}",
            headers=headers,
            timeout=aiohttp.ClientTimeout(total=5)
        ) as r:
            # 200 = success, 204 = no content (valid), 404 = not found (valid in some cases)
            ok = r.status in (200, 204)
            return ok, (time.time() - start) * 1000
    except asyncio.TimeoutError:
        return False, (time.time() - start) * 1000
    except Exception:
        return False, (time.time() - start) * 1000


async def run_scenario_dashboard(count: int = 300) -> VFSStats:
    """Dashboard query load — most common VFS read operation."""
    stats = VFSStats(scenario=f"vfs-dashboard-{count}-ops")

    connector = aiohttp.TCPConnector(limit=50)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token:
            print("  ERROR: could not authenticate")
            return stats

        sem = asyncio.Semaphore(30)

        async def one():
            async with sem:
                return await vfs_get(session, token, "/api/vfs/dashboard")

        with tqdm(total=count, desc="VFS dashboard") as pbar:
            for i in range(0, count, 50):
                batch = await asyncio.gather(*[one() for _ in range(min(50, count - i))])
                for ok, dur in batch:
                    stats.record(dur, ok)
                    pbar.update(1)

    return stats


async def run_scenario_buckets(count: int = 300) -> VFSStats:
    """Bucket listing throughput."""
    stats = VFSStats(scenario=f"vfs-buckets-{count}-ops")

    connector = aiohttp.TCPConnector(limit=50)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token:
            print("  ERROR: could not authenticate")
            return stats

        sem = asyncio.Semaphore(30)

        async def one():
            async with sem:
                return await vfs_get(session, token, "/api/vfs/buckets")

        with tqdm(total=count, desc="VFS buckets") as pbar:
            for i in range(0, count, 50):
                batch = await asyncio.gather(*[one() for _ in range(min(50, count - i))])
                for ok, dur in batch:
                    stats.record(dur, ok)
                    pbar.update(1)

    return stats


async def run_scenario_intents(count: int = 200) -> VFSStats:
    """Intent health polling — simulates automated monitoring."""
    stats = VFSStats(scenario=f"vfs-intents-{count}-ops")

    connector = aiohttp.TCPConnector(limit=30)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token:
            print("  ERROR: could not authenticate")
            return stats

        import random
        endpoints = ["/api/vfs/intents/health", "/api/vfs/intents/recent"]

        with tqdm(total=count, desc="VFS intents") as pbar:
            tasks = []
            for _ in range(count):
                endpoint = random.choice(endpoints)
                tasks.append(vfs_get(session, token, endpoint))

            for i in range(0, len(tasks), 30):
                batch = await asyncio.gather(*tasks[i:i+30])
                for ok, dur in batch:
                    stats.record(dur, ok)
                    pbar.update(1)

    return stats


async def run_scenario_concurrent_reads(workers: int = 100, count: int = 500) -> VFSStats:
    """Mixed concurrent reads across all VFS endpoints."""
    stats = VFSStats(scenario=f"vfs-concurrent-{workers}w-{count}ops")

    connector = aiohttp.TCPConnector(limit=workers + 10)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token:
            print("  ERROR: could not authenticate")
            return stats

        account_ids = await get_accounts(session, token)

        import random
        endpoints = [
            "/api/vfs/dashboard",
            "/api/vfs/buckets",
            "/api/vfs/intents/health",
            "/api/vfs/intents/recent",
        ]
        if account_ids:
            for aid in account_ids[:5]:
                endpoints.append(f"/api/vfs/accounts/{aid}/usage")

        sem = asyncio.Semaphore(workers)

        async def one():
            async with sem:
                endpoint = random.choice(endpoints)
                return await vfs_get(session, token, endpoint)

        with tqdm(total=count, desc=f"VFS {workers} concurrent") as pbar:
            tasks = [one() for _ in range(count)]
            for i in range(0, len(tasks), workers):
                batch = await asyncio.gather(*tasks[i:i+workers])
                for ok, dur in batch:
                    stats.record(dur, ok)
                    pbar.update(1)

    return stats


async def run_all() -> None:
    print("\n=== VFS Management API Full Benchmark Suite ===\n")
    results = []

    for coro, label in [
        (run_scenario_dashboard(300),           "Dashboard"),
        (run_scenario_buckets(300),             "Buckets"),
        (run_scenario_intents(200),             "Intents"),
        (run_scenario_concurrent_reads(50, 500), "Concurrent reads"),
    ]:
        stats = await coro
        stats.print_report()
        results.append(stats)

    print("\n=== VFS Summary ===")
    rows = []
    for s in results:
        if s.durations:
            sd = sorted(s.durations)
            p95 = sd[int(len(sd) * 0.95)]
            rows.append([s.scenario, len(s.durations),
                         f"{statistics.median(s.durations):.0f}ms",
                         f"{p95:.0f}ms", f"{s.errors}"])
    print(tabulate(rows, headers=["Scenario", "Ops", "p50", "p95", "Errors"], tablefmt="simple"))


def main() -> None:
    parser = argparse.ArgumentParser(description="TranzFer MFT VFS Management API Benchmark")
    parser.add_argument("--scenario", required=True,
        choices=["dashboard", "buckets", "intents", "concurrent-reads", "all"])
    parser.add_argument("--count",   type=int, default=300,  help="Number of requests")
    parser.add_argument("--workers", type=int, default=50,   help="Concurrent workers")
    args = parser.parse_args()

    if args.scenario == "all":
        asyncio.run(run_all())
    elif args.scenario == "dashboard":
        asyncio.run(run_scenario_dashboard(args.count)).print_report()  # type: ignore
    elif args.scenario == "buckets":
        asyncio.run(run_scenario_buckets(args.count)).print_report()  # type: ignore
    elif args.scenario == "intents":
        asyncio.run(run_scenario_intents(args.count)).print_report()  # type: ignore
    elif args.scenario == "concurrent-reads":
        asyncio.run(run_scenario_concurrent_reads(args.workers, args.count)).print_report()  # type: ignore


if __name__ == "__main__":
    main()
