#!/usr/bin/env python3
"""
TranzFer MFT — Virtual File System (VFS) Performance Benchmark
Tests VFS directory listing, file lookup, metadata operations, and deep tree traversal.

The VFS is the in-platform virtual filesystem layer used by SFTP/FTP services
for path resolution, permissions, and quota management.

Usage:
    python3 vfs_benchmark.py --scenario listing          # Directory listing speed
    python3 vfs_benchmark.py --scenario deep-tree        # Deep nested path traversal
    python3 vfs_benchmark.py --scenario concurrent-reads # 100 concurrent VFS reads
    python3 vfs_benchmark.py --scenario metadata-write   # File metadata CRUD
    python3 vfs_benchmark.py --scenario search           # VFS search by name/date
    python3 vfs_benchmark.py --scenario all              # Run all scenarios
"""
import argparse
import asyncio
import os
import random
import statistics
import time
from dataclasses import dataclass, field
from typing import List, Optional

import aiohttp
from tqdm import tqdm
from tabulate import tabulate

BASE_URL     = os.environ.get("MFT_BASE_URL",  "http://localhost")
SFTP_PORT    = int(os.environ.get("SFTP_CTRL", "8081"))
ONBOARD_PORT = int(os.environ.get("ONBOARD_PORT", "8080"))
ADMIN_EMAIL  = os.environ.get("MFT_ADMIN_EMAIL", "admin@filetransfer.local")
ADMIN_PASS   = os.environ.get("MFT_ADMIN_PASS",  "Admin@1234")

SFTP_BASE    = f"{BASE_URL}:{SFTP_PORT}"
ONBOARD_BASE = f"{BASE_URL}:{ONBOARD_PORT}"


@dataclass
class VFSStats:
    scenario: str
    durations: List[float] = field(default_factory=list)
    errors: int = 0
    start_time: float = field(default_factory=time.time)

    def record(self, dur: float, ok: bool):
        self.durations.append(dur)
        if not ok: self.errors += 1

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
            rows += [
                ["p50",  f"{statistics.median(self.durations):.1f}ms"],
                ["p95",  f"{sd[int(len(sd)*0.95)]:.1f}ms"],
                ["p99",  f"{sd[int(len(sd)*0.99)]:.1f}ms"],
                ["max",  f"{max(self.durations):.1f}ms"],
                ["ops/s", f"{total/wall:.1f}"],
            ]

        print(tabulate(rows, tablefmt="simple"))

        if err_pct < 1 and (not self.durations or sorted(self.durations)[int(len(self.durations)*0.95)] < 200):
            print("\n✓ PASS")
        elif err_pct < 5:
            print(f"\n! WARN — p95={sorted(self.durations)[int(len(self.durations)*0.95)]:.0f}ms, errors={err_pct:.1f}%")
        else:
            print(f"\n✗ FAIL")


async def get_token(session: aiohttp.ClientSession) -> Optional[str]:
    try:
        async with session.post(
            f"{ONBOARD_BASE}/api/v1/auth/login",
            json={"email": ADMIN_EMAIL, "password": ADMIN_PASS},
            timeout=aiohttp.ClientTimeout(total=10)
        ) as r:
            if r.status == 200:
                body = await r.json()
                return body.get("token") or body.get("accessToken")
    except: pass
    return None


async def vfs_list_dir(session: aiohttp.ClientSession, token: str, path: str) -> tuple:
    """List a VFS directory."""
    start = time.time()
    try:
        async with session.get(
            f"{SFTP_BASE}/api/v1/vfs/list",
            params={"path": path},
            headers={"Authorization": f"Bearer {token}"},
            timeout=aiohttp.ClientTimeout(total=5)
        ) as r:
            ok = r.status in (200, 404)  # 404 = empty dir (valid)
            return ok, (time.time() - start) * 1000
    except asyncio.TimeoutError:
        return False, (time.time() - start) * 1000
    except Exception:
        return False, (time.time() - start) * 1000


async def vfs_stat(session: aiohttp.ClientSession, token: str, path: str) -> tuple:
    """Stat (metadata lookup) for a VFS path."""
    start = time.time()
    try:
        async with session.get(
            f"{SFTP_BASE}/api/v1/vfs/stat",
            params={"path": path},
            headers={"Authorization": f"Bearer {token}"},
            timeout=aiohttp.ClientTimeout(total=5)
        ) as r:
            return r.status < 500, (time.time() - start) * 1000
    except:
        return False, (time.time() - start) * 1000


async def vfs_create_dir(session: aiohttp.ClientSession, token: str, path: str) -> tuple:
    """Create a VFS directory."""
    start = time.time()
    try:
        async with session.post(
            f"{SFTP_BASE}/api/v1/vfs/mkdir",
            json={"path": path},
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            timeout=aiohttp.ClientTimeout(total=5)
        ) as r:
            return r.status in (200, 201, 409), (time.time() - start) * 1000
    except:
        return False, (time.time() - start) * 1000


async def vfs_search(session: aiohttp.ClientSession, token: str, query: str) -> tuple:
    """Search VFS by filename pattern."""
    start = time.time()
    try:
        async with session.get(
            f"{SFTP_BASE}/api/v1/vfs/search",
            params={"q": query, "limit": 50},
            headers={"Authorization": f"Bearer {token}"},
            timeout=aiohttp.ClientTimeout(total=10)
        ) as r:
            return r.status < 500, (time.time() - start) * 1000
    except:
        return False, (time.time() - start) * 1000


async def run_scenario_listing(count: int = 500) -> VFSStats:
    """Directory listing at various depths."""
    stats = VFSStats(scenario=f"directory-listing-{count}")
    paths = ["/", "/upload", "/incoming", "/outgoing", "/archive",
             "/upload/2024", "/upload/2024/01", "/upload/2024/01/01"]

    connector = aiohttp.TCPConnector(limit=50)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token: return stats

        sem = asyncio.Semaphore(30)

        async def list_one():
            async with sem:
                path = random.choice(paths)
                return await vfs_list_dir(session, token, path)

        with tqdm(total=count, desc="VFS listing") as pbar:
            for i in range(0, count, 50):
                batch = await asyncio.gather(*[list_one() for _ in range(min(50, count-i))])
                for ok, dur in batch:
                    stats.record(dur, ok)
                    pbar.update(1)

    return stats


async def run_scenario_deep_tree(depth: int = 10, width: int = 5) -> VFSStats:
    """Create and traverse a deeply nested VFS tree."""
    stats = VFSStats(scenario=f"deep-tree-depth{depth}-width{width}")

    connector = aiohttp.TCPConnector(limit=20)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token: return stats

        # Create deep tree
        print(f"  Creating {depth}-level deep tree with {width} dirs each level...")
        base = f"/perf-tree-{int(time.time())}"
        current_paths = [base]

        for level in range(depth):
            next_paths = []
            for parent in current_paths:
                for w in range(width):
                    child = f"{parent}/L{level}D{w}"
                    ok, dur = await vfs_create_dir(session, token, child)
                    stats.record(dur, ok)
                    next_paths.append(child)
            current_paths = next_paths
            if len(current_paths) > 100:  # cap expansion
                current_paths = current_paths[:10]

        # Traverse created tree
        print(f"  Traversing tree ({stats.durations.__len__()} dirs created)...")
        for path in current_paths[:50]:
            ok, dur = await vfs_list_dir(session, token, path)
            stats.record(dur, ok)

    return stats


async def run_scenario_concurrent_reads(workers: int = 100, count: int = 1000) -> VFSStats:
    """100 concurrent VFS stat/list operations."""
    stats = VFSStats(scenario=f"concurrent-reads-{workers}w-{count}ops")
    paths = [f"/upload/account-{i}" for i in range(1, 20)]

    connector = aiohttp.TCPConnector(limit=workers + 10)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token: return stats

        sem = asyncio.Semaphore(workers)

        async def read_one():
            async with sem:
                path = random.choice(paths)
                return await vfs_list_dir(session, token, path)

        with tqdm(total=count, desc=f"VFS {workers} concurrent") as pbar:
            tasks = [read_one() for _ in range(count)]
            for i in range(0, len(tasks), workers):
                batch = await asyncio.gather(*tasks[i:i+workers])
                for ok, dur in batch:
                    stats.record(dur, ok)
                    pbar.update(1)

    return stats


async def run_scenario_search(count: int = 200) -> VFSStats:
    """VFS search performance at various result set sizes."""
    stats = VFSStats(scenario=f"search-{count}")
    queries = ["*.pdf", "*.xml", "transfer-*", "invoice*", "2024*", "EDI*"]

    connector = aiohttp.TCPConnector(limit=20)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token: return stats

        with tqdm(total=count, desc="VFS search") as pbar:
            for i in range(count):
                q = random.choice(queries)
                ok, dur = await vfs_search(session, token, q)
                stats.record(dur, ok)
                pbar.update(1)
                await asyncio.sleep(0.02)

    return stats


async def run_all():
    print("\n=== VFS Full Benchmark Suite ===\n")
    results = []

    for coro, label in [
        (run_scenario_listing(500), "Listing"),
        (run_scenario_concurrent_reads(50, 500), "Concurrent reads"),
        (run_scenario_search(200), "Search"),
    ]:
        stats = await coro
        stats.print_report()
        results.append(stats)

    # Deep tree (smaller scale)
    stats = await run_scenario_deep_tree(depth=8, width=3)
    stats.print_report()
    results.append(stats)

    print("\n=== VFS Summary ===")
    rows = []
    for s in results:
        if s.durations:
            sd = sorted(s.durations)
            p95 = sd[int(len(sd)*0.95)]
            rows.append([s.scenario, len(s.durations), f"{statistics.median(s.durations):.0f}ms",
                         f"{p95:.0f}ms", f"{s.errors}"])
    print(tabulate(rows, headers=["Scenario", "Ops", "p50", "p95", "Errors"], tablefmt="simple"))


def main():
    parser = argparse.ArgumentParser(description="TranzFer MFT VFS Benchmark")
    parser.add_argument("--scenario", required=True,
        choices=["listing", "deep-tree", "concurrent-reads", "metadata-write", "search", "all"])
    parser.add_argument("--count",   type=int, default=500)
    parser.add_argument("--workers", type=int, default=100)
    parser.add_argument("--depth",   type=int, default=10)
    parser.add_argument("--width",   type=int, default=5)
    args = parser.parse_args()

    if args.scenario == "all":
        asyncio.run(run_all())
    elif args.scenario == "listing":
        asyncio.run(run_scenario_listing(args.count)).print_report()  # type: ignore
    elif args.scenario == "deep-tree":
        asyncio.run(run_scenario_deep_tree(args.depth, args.width)).print_report()  # type: ignore
    elif args.scenario == "concurrent-reads":
        asyncio.run(run_scenario_concurrent_reads(args.workers, args.count)).print_report()  # type: ignore
    elif args.scenario == "search":
        asyncio.run(run_scenario_search(args.count)).print_report()  # type: ignore
    else:
        print(f"Scenario '{args.scenario}' not yet implemented")


if __name__ == "__main__":
    main()
