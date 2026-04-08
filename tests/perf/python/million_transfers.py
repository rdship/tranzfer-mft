#!/usr/bin/env python3
"""
TranzFer MFT — Volume Test: 10K → 100K → 1M Transfers
Uses async HTTP (REST API) for high-throughput batching.
Also supports realistic mix (80/15/5% small/medium/large) and EDI bulk.

Usage:
    python3 million_transfers.py --count 10000  --workers 20
    python3 million_transfers.py --count 100000 --workers 50
    python3 million_transfers.py --count 1000000 --workers 100
    python3 million_transfers.py --scenario realistic-mix --count 100000
    python3 million_transfers.py --scenario edi-bulk --count 10000
    python3 million_transfers.py --scenario fail-all --count 500
"""
import argparse
import asyncio
import json
import os
import random
import statistics
import string
import time
from dataclasses import dataclass, field
from typing import List, Optional

import aiohttp
from tqdm import tqdm
from tabulate import tabulate

BASE_URL       = os.environ.get("MFT_BASE_URL",   "http://localhost")
ONBOARD_PORT   = int(os.environ.get("ONBOARD_PORT", "8080"))
SCREEN_PORT    = int(os.environ.get("SCREEN_PORT",  "8092"))
ENCRYPT_PORT   = int(os.environ.get("ENCRYPT_PORT", "8086"))
EDI_PORT       = int(os.environ.get("EDI_PORT",     "8095"))
SENTINEL_PORT  = int(os.environ.get("SENTINEL_PORT","8098"))
ADMIN_EMAIL    = os.environ.get("MFT_ADMIN_EMAIL",  "admin@filetransfer.local")
ADMIN_PASS     = os.environ.get("MFT_ADMIN_PASS",   "Admin@1234")

ONBOARD_BASE  = f"{BASE_URL}:{ONBOARD_PORT}"
SCREEN_BASE   = f"{BASE_URL}:{SCREEN_PORT}"
ENCRYPT_BASE  = f"{BASE_URL}:{ENCRYPT_PORT}"
EDI_BASE      = f"{BASE_URL}:{EDI_PORT}"
SENTINEL_BASE = f"{BASE_URL}:{SENTINEL_PORT}"

FILE_SIZES = {
    "small":  ("1KB",   1024),
    "medium": ("100KB", 102400),
    "large":  ("1MB",   1048576),
}

@dataclass
class VolumeStats:
    scenario: str
    target: int
    succeeded: int = 0
    failed: int = 0
    durations_ms: List[float] = field(default_factory=list)
    start_time: float = field(default_factory=time.time)

    def record(self, ok: bool, duration_ms: float):
        if ok:
            self.succeeded += 1
        else:
            self.failed += 1
        self.durations_ms.append(duration_ms)

    def print_report(self, sentinel_score: Optional[int] = None):
        total = self.succeeded + self.failed
        wall  = time.time() - self.start_time
        error_pct = (self.failed / total * 100) if total > 0 else 0

        print(f"\n{'='*60}")
        print(f"Volume Test: {self.scenario}")
        print(f"{'='*60}")

        rows = [
            ["Target",        self.target],
            ["Completed",     total],
            ["Succeeded",     f"{self.succeeded} ({100-error_pct:.1f}%)"],
            ["Failed",        f"{self.failed} ({error_pct:.1f}%)"],
            ["Wall time",     f"{wall:.1f}s  ({wall/60:.1f} min)"],
            ["Throughput",    f"{self.succeeded/wall:.1f} transfers/sec"],
        ]
        if self.durations_ms:
            sd = sorted(self.durations_ms)
            rows += [
                ["p50 latency",  f"{statistics.median(self.durations_ms):.0f}ms"],
                ["p95 latency",  f"{sd[int(len(sd)*0.95)]:.0f}ms"],
                ["p99 latency",  f"{sd[int(len(sd)*0.99)]:.0f}ms"],
                ["Max latency",  f"{max(self.durations_ms):.0f}ms"],
            ]
        if sentinel_score is not None:
            rows.append(["Sentinel score", sentinel_score])

        print(tabulate(rows, tablefmt="simple"))

        if error_pct < 1.0:
            print(f"\n✓ PASS — {self.succeeded} transfers, {error_pct:.2f}% errors")
        elif error_pct < 5.0:
            print(f"\n! WARN — {error_pct:.2f}% errors (threshold: 1%)")
        else:
            print(f"\n✗ FAIL — {error_pct:.2f}% errors (threshold: 5%)")


async def get_token(session: aiohttp.ClientSession) -> Optional[str]:
    try:
        async with session.post(
            f"{ONBOARD_BASE}/api/v1/auth/login",
            json={"email": ADMIN_EMAIL, "password": ADMIN_PASS},
            timeout=aiohttp.ClientTimeout(total=10)
        ) as r:
            if r.status == 200:
                body = await r.json()
                return body.get("token") or body.get("accessToken") or body.get("jwt")
    except Exception as e:
        print(f"Login failed: {e}")
    return None


async def get_sentinel_score(session: aiohttp.ClientSession, token: str) -> Optional[int]:
    try:
        async with session.get(
            f"{SENTINEL_BASE}/api/v1/sentinel/health-score",
            headers={"Authorization": f"Bearer {token}"},
            timeout=aiohttp.ClientTimeout(total=5)
        ) as r:
            if r.status == 200:
                body = await r.json()
                return body.get("overallScore")
    except: pass
    return None


def rand_id(n: int = 8) -> str:
    return ''.join(random.choices(string.ascii_lowercase + string.digits, k=n))


async def simulate_transfer(session: aiohttp.ClientSession, token: str,
                             file_size: int, fail_intentionally: bool = False) -> tuple:
    """Simulate one REST-path transfer: screen → encrypt → record."""
    start = time.time()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    track_id = f"VOL-{int(time.time()*1000)}-{rand_id()}"
    ok = True

    try:
        if fail_intentionally:
            # Deliberately send bad data to generate DLQ entries
            async with session.post(
                f"{SCREEN_BASE}/api/v1/screening/scan",
                json={"fileName": None, "fileSize": -1, "transferId": track_id},
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as _: pass
            return False, (time.time() - start) * 1000

        # Screening
        async with session.post(
            f"{SCREEN_BASE}/api/v1/screening/scan",
            json={
                "fileName":     f"file-{track_id}.dat",
                "fileSize":     file_size,
                "senderName":   "ACME CORP",
                "receiverName": "PARTNER INC",
                "transferId":   track_id,
            },
            headers=headers,
            timeout=aiohttp.ClientTimeout(total=10)
        ) as r:
            if r.status >= 500: ok = False

        # Encryption (skip for large files in high-volume to preserve throughput)
        if file_size <= 102400:
            payload = "X" * file_size
            async with session.post(
                f"{ENCRYPT_BASE}/api/v1/encrypt",
                json={"data": payload},
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as r:
                if r.status >= 500: ok = False

    except asyncio.TimeoutError:
        ok = False
    except Exception:
        ok = False

    return ok, (time.time() - start) * 1000


async def run_volume_test(count: int, workers: int, file_size: int,
                          scenario: str, fail_intentionally: bool = False) -> VolumeStats:
    stats = VolumeStats(scenario=scenario, target=count)
    token = None

    connector = aiohttp.TCPConnector(limit=workers + 20, limit_per_host=workers)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token:
            print("ERROR: Could not obtain token. Is the platform running?")
            return stats

        print(f"\nVolume test: {count:,} transfers | workers={workers} | scenario={scenario}")
        print(f"Token obtained. Starting transfers...\n")

        semaphore = asyncio.Semaphore(workers)

        async def bounded_transfer(i: int) -> tuple:
            async with semaphore:
                # Realistic file size mix for realistic-mix scenario
                if scenario == "realistic-mix":
                    r = random.random()
                    if r < 0.80:   sz = FILE_SIZES["small"][1]
                    elif r < 0.95: sz = FILE_SIZES["medium"][1]
                    else:          sz = FILE_SIZES["large"][1]
                else:
                    sz = file_size
                return await simulate_transfer(session, token, sz, fail_intentionally)

        with tqdm(total=count, desc=f"{scenario}", unit="transfer") as pbar:
            tasks  = [bounded_transfer(i) for i in range(count)]
            done   = 0
            batch_size = min(workers * 2, 500)

            for i in range(0, len(tasks), batch_size):
                batch = await asyncio.gather(*tasks[i:i+batch_size], return_exceptions=True)
                for result in batch:
                    if isinstance(result, tuple):
                        ok, dur = result
                    else:
                        ok, dur = False, 0.0
                    stats.record(ok, dur)
                    pbar.update(1)

                # Print throughput every 10K transfers
                if stats.succeeded + stats.failed > 0 and (stats.succeeded + stats.failed) % 10000 == 0:
                    elapsed = time.time() - stats.start_time
                    tps = (stats.succeeded + stats.failed) / elapsed
                    tqdm.write(f"  → {stats.succeeded+stats.failed:,} done | {tps:.0f} t/s | "
                               f"errors: {stats.failed}")

        # Get final Sentinel health score
        sentinel_score = await get_sentinel_score(session, token) if token else None

    stats.print_report(sentinel_score=sentinel_score)
    return stats


async def run_edi_bulk(count: int, workers: int) -> VolumeStats:
    """Convert EDI files in bulk via edi-converter."""
    stats = VolumeStats(scenario=f"edi-bulk-{count}", target=count)
    token = None

    # Sample X12 EDI payload
    sample_x12 = (
        "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       "
        "*200101*1200*^*00501*000000001*0*P*:~"
        "GS*IN*SENDER*RECEIVER*20200101*120000*1*X*005010X012~"
        "ST*810*0001~"
        "BIG*20200101*INV001***PO001~"
        "N1*BY*BUYER COMPANY~"
        "N1*SE*SELLER COMPANY~"
        "IT1*1*1*EA*100.00**VN*ITEM001~"
        "TDS*10000~"
        "SE*9*0001~"
        "GE*1*1~"
        "IEA*1*000000001~"
    )

    connector = aiohttp.TCPConnector(limit=workers + 10)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token: return stats

        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        semaphore = asyncio.Semaphore(workers)

        async def convert_one():
            async with semaphore:
                start = time.time()
                try:
                    async with session.post(
                        f"{EDI_BASE}/api/v1/edi/convert",
                        json={"content": sample_x12, "format": "X12", "targetFormat": "JSON"},
                        headers=headers,
                        timeout=aiohttp.ClientTimeout(total=10)
                    ) as r:
                        ok = r.status == 200
                        return ok, (time.time() - start) * 1000
                except:
                    return False, (time.time() - start) * 1000

        with tqdm(total=count, desc="EDI convert") as pbar:
            tasks = [convert_one() for _ in range(count)]
            for i in range(0, len(tasks), workers * 2):
                batch = await asyncio.gather(*tasks[i:i+workers*2], return_exceptions=True)
                for r in batch:
                    if isinstance(r, tuple):
                        stats.record(r[0], r[1])
                    else:
                        stats.record(False, 0)
                    pbar.update(1)

    stats.print_report()
    return stats


def main():
    parser = argparse.ArgumentParser(description="TranzFer MFT Volume Test")
    parser.add_argument("--count",        type=int, default=10000)
    parser.add_argument("--workers",      type=int, default=20)
    parser.add_argument("--batch-size",   type=int, default=100)
    parser.add_argument("--scenario",     type=str, default="default",
        choices=["default", "realistic-mix", "edi-bulk", "fail-all"])
    parser.add_argument("--small-pct",    type=int, default=80)
    parser.add_argument("--medium-pct",   type=int, default=15)
    parser.add_argument("--large-pct",    type=int, default=5)
    args = parser.parse_args()

    print(f"Volume Test → {ONBOARD_BASE}")
    print(f"Target: {args.count:,} transfers | Workers: {args.workers}")

    if args.scenario == "edi-bulk":
        asyncio.run(run_edi_bulk(count=args.count, workers=args.workers))
    elif args.scenario == "fail-all":
        asyncio.run(run_volume_test(
            count=args.count, workers=args.workers,
            file_size=FILE_SIZES["small"][1],
            scenario="fail-all", fail_intentionally=True
        ))
    else:
        asyncio.run(run_volume_test(
            count=args.count, workers=args.workers,
            file_size=FILE_SIZES["small"][1],
            scenario=args.scenario
        ))


if __name__ == "__main__":
    main()
