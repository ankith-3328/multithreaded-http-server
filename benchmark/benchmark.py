"""
benchmark.py — simple async load generator for the HTTP server.

What it does, in plain words:
  1. Optionally runs a warm-up phase whose timings are DISCARDED, so
     server JIT warmup does not distort the measured percentiles.
  2. Fires N concurrent "workers", each hammering the server with
     requests as fast as it can, for a fixed duration.
  3. Times every single request individually (start -> response received).
  4. Once the duration is up, computes real statistics from that list of
     timings: total requests, requests/sec, average latency, and the
     p50/p95/p99 latency percentiles.
  5. Drains the keep-alive connection pool politely: one final request
     per connection carries "Connection: close", so the server closes
     each socket deliberately instead of logging "Broken pipe" when the
     client pool disappears under it.
  6. Saves everything to a JSON file under results/ so you have a real,
     reproducible record instead of a number you half-remember.

Usage:
    python3 benchmark.py --url http://localhost:8080/hello \
                          --concurrency 8 \
                          --duration 10 \
                          --workers-label 8

--workers-label is just metadata (record here how many worker threads
YOUR SERVER was configured with for this run — you set that separately
in ServerConfig / via CLI arg to Main, then restart the server, THEN run
this benchmark against it). It has nothing to do with --concurrency,
which is how many concurrent clients THIS script uses to hit the server.

Exit code is 0 when every request succeeded, 1 otherwise.

Requires: pip install httpx
"""

import argparse
import asyncio
import json
import statistics
import sys
import time
from collections import Counter
from pathlib import Path

import httpx


async def worker(client: httpx.AsyncClient, url: str, deadline: float,
                 latencies: list[float], errors: Counter) -> None:
    """
    One simulated concurrent client. Loops firing requests as fast as
    possible until the deadline (wall-clock time.monotonic() value) is
    reached. Appends each request's latency (in milliseconds) to the
    shared `latencies` list. Failures are tallied by category in
    `errors` (e.g. "timeout", "connect", "status_500") instead of
    accumulating an unbounded list of strings.
    """
    while time.monotonic() < deadline:
        start = time.monotonic()
        try:
            response = await client.get(url)
            elapsed_ms = (time.monotonic() - start) * 1000

            if response.status_code >= 400:
                errors[f"status_{response.status_code}"] += 1
            else:
                latencies.append(elapsed_ms)

        except httpx.TimeoutException:
            errors["timeout"] += 1
        except httpx.ConnectError:
            errors["connect"] += 1
        except Exception:
            errors["other"] += 1


async def drain_connections(client: httpx.AsyncClient, url: str,
                            concurrency: int) -> None:
    """
    Close every pooled keep-alive connection cleanly.

    Without this, exiting the httpx client drops the pooled sockets
    while each server worker thread is blocked in read() waiting for
    the next request; the server then fails to write to the vanished
    socket and logs "Broken pipe". One "Connection: close" request per
    connection makes the server close its side deliberately.
    """
    for _ in range(concurrency):
        try:
            await client.get(url, headers={"Connection": "close"})
        except Exception:
            pass


def percentile(sorted_values: list[float], pct: float) -> float:
    """
    Returns the pct-th percentile (0-100) from an already-sorted list.
    Uses simple index math rather than a library so it's easy to see
    exactly what's happening — no hidden interpolation surprises.
    """
    if not sorted_values:
        return 0.0

    index = int(round((pct / 100) * (len(sorted_values) - 1)))
    index = max(0, min(index, len(sorted_values) - 1))

    return sorted_values[index]


async def run_benchmark(url: str, concurrency: int, duration: float,
                        warmup: float, timeout: float) -> dict:
    limits = httpx.Limits(
        max_connections=concurrency,
        max_keepalive_connections=concurrency,
    )

    async with httpx.AsyncClient(limits=limits, timeout=timeout) as client:

        if warmup > 0:
            # Timings from this phase are thrown away on purpose.
            warmup_latencies: list[float] = []
            warmup_errors: Counter = Counter()
            warmup_deadline = time.monotonic() + warmup

            await asyncio.gather(*(
                asyncio.create_task(
                    worker(client, url, warmup_deadline,
                           warmup_latencies, warmup_errors)
                )
                for _ in range(concurrency)
            ))

        latencies: list[float] = []
        errors: Counter = Counter()

        deadline = time.monotonic() + duration
        started_at = time.monotonic()

        await asyncio.gather(*(
            asyncio.create_task(
                worker(client, url, deadline, latencies, errors)
            )
            for _ in range(concurrency)
        ))

        actual_duration = time.monotonic() - started_at

        await drain_connections(client, url, concurrency)

    latencies.sort()

    failed_requests = sum(errors.values())

    result = {
        "url": url,
        "concurrency": concurrency,
        "warmup_seconds": warmup,
        "requested_duration_seconds": duration,
        "actual_duration_seconds": round(actual_duration, 3),
        "total_requests": len(latencies) + failed_requests,
        "successful_requests": len(latencies),
        "failed_requests": failed_requests,
        "error_breakdown": dict(errors),
        "requests_per_second": round(len(latencies) / actual_duration, 2)
            if actual_duration > 0 else 0.0,
        "latency_ms": {
            "min": round(min(latencies), 3) if latencies else None,
            "max": round(max(latencies), 3) if latencies else None,
            "avg": round(statistics.mean(latencies), 3) if latencies else None,
            "p50": round(percentile(latencies, 50), 3),
            "p95": round(percentile(latencies, 95), 3),
            "p99": round(percentile(latencies, 99), 3),
        },
    }

    return result


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Async load generator for the multithreaded HTTP server."
    )
    parser.add_argument(
        "--url",
        default="http://localhost:8081/hello",
        help="Endpoint to hammer (default: %(default)s)",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=8,
        help="Number of concurrent simulated clients (default: %(default)s)",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=10.0,
        help="How long to run the measured load test, in seconds "
             "(default: %(default)s)",
    )
    parser.add_argument(
        "--warmup",
        type=float,
        default=2.0,
        help="Warm-up phase length in seconds; its timings are discarded "
             "(default: %(default)s). Use 0 to disable.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=20.0,
        help="Per-request timeout in seconds (default: %(default)s)",
    )
    parser.add_argument(
        "--workers-label",
        default="unknown",
        help="Label recording how many WORKER THREADS the server itself was "
             "configured with for this run (set that separately in "
             "ServerConfig, restart the server, THEN run this). Purely "
             "metadata for the output filename/JSON, does not affect "
             "the load this script generates.",
    )
    parser.add_argument(
        "--output-dir",
        default="results",
        help="Directory to write the JSON results file into (default: %(default)s)",
    )

    args = parser.parse_args()

    print(f"Target:       {args.url}")
    print(f"Concurrency:  {args.concurrency} simulated clients")
    print(f"Warm-up:      {args.warmup}s (discarded)")
    print(f"Duration:     {args.duration}s")
    print(f"Timeout:      {args.timeout}s per request")
    print(f"Server label: workers={args.workers_label}")
    print("Running...")

    result = asyncio.run(
        run_benchmark(
            args.url, args.concurrency, args.duration,
            args.warmup, args.timeout,
        )
    )

    result["server_workers_label"] = args.workers_label

    print()
    print("Results (warm-up excluded):")
    print(f"  Total requests:      {result['total_requests']}")
    print(f"  Successful:          {result['successful_requests']}")
    print(f"  Failed:              {result['failed_requests']}")
    if result["error_breakdown"]:
        print(f"  Error breakdown:     {result['error_breakdown']}")
    print(f"  Requests/sec:        {result['requests_per_second']}")
    print(f"  Latency avg (ms):    {result['latency_ms']['avg']}")
    print(f"  Latency p50 (ms):    {result['latency_ms']['p50']}")
    print(f"  Latency p95 (ms):    {result['latency_ms']['p95']}")
    print(f"  Latency p99 (ms):    {result['latency_ms']['p99']}")

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    filename = f"http_workers{args.workers_label}_c{args.concurrency}.json"
    output_path = output_dir / filename

    with open(output_path, "w") as f:
        json.dump(result, f, indent=2)

    print()
    print(f"Saved: {output_path}")

    sys.exit(0 if result["failed_requests"] == 0 else 1)


if __name__ == "__main__":
    main()
