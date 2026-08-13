"""
benchmark.py — simple async load generator for the HTTP server.

What it does, in plain words:
  1. Fires N concurrent "workers", each hammering the server with
     requests as fast as it can, for a fixed duration.
  2. Times every single request individually (start -> response received).
  3. Once the duration is up, computes real statistics from that list of
     timings: total requests, requests/sec, average latency, and the
     p50/p95/p99 latency percentiles.
  4. Saves everything to a JSON file under results/ so you have a real,
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

Requires: pip install httpx
"""

import argparse
import asyncio
import json
import statistics
import time
from pathlib import Path

import httpx


async def worker(client: httpx.AsyncClient, url: str, deadline: float,
                  latencies: list[float], errors: list[str]) -> None:
    """
    One simulated concurrent client. Loops firing requests as fast as
    possible until the deadline (wall-clock time.monotonic() value) is
    reached. Appends each request's latency (in milliseconds) to the
    shared `latencies` list, or an error string to `errors` on failure.
    """
    while time.monotonic() < deadline:
        start = time.monotonic()
        try:
            response = await client.get(url)
            elapsed_ms = (time.monotonic() - start) * 1000

            if response.status_code >= 400:
                errors.append(f"status {response.status_code}")
            else:
                latencies.append(elapsed_ms)

        except Exception as exc:
            errors.append(str(exc))


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


async def run_benchmark(url: str, concurrency: int, duration: float) -> dict:
    latencies: list[float] = []
    errors: list[str] = []

    deadline = time.monotonic() + duration

    limits = httpx.Limits(
        max_connections=concurrency,
        max_keepalive_connections=concurrency,
    )

    started_at = time.monotonic()

    async with httpx.AsyncClient(limits=limits, timeout=10.0) as client:
        tasks = [
            asyncio.create_task(worker(client, url, deadline, latencies, errors))
            for _ in range(concurrency)
        ]
        await asyncio.gather(*tasks)

    actual_duration = time.monotonic() - started_at

    latencies.sort()

    total_requests = len(latencies) + len(errors)
    successful_requests = len(latencies)

    result = {
        "url": url,
        "concurrency": concurrency,
        "requested_duration_seconds": duration,
        "actual_duration_seconds": round(actual_duration, 3),
        "total_requests": total_requests,
        "successful_requests": successful_requests,
        "failed_requests": len(errors),
        "requests_per_second": round(successful_requests / actual_duration, 2)
            if actual_duration > 0 else 0.0,
        "latency_ms": {
            "min": round(min(latencies), 3) if latencies else None,
            "max": round(max(latencies), 3) if latencies else None,
            "avg": round(statistics.mean(latencies), 3) if latencies else None,
            "p50": round(percentile(latencies, 50), 3),
            "p95": round(percentile(latencies, 95), 3),
            "p99": round(percentile(latencies, 99), 3),
        },
        "sample_errors": errors[:10],  # first 10 only, so the file doesn't explode
    }

    return result


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Async load generator for the multithreaded HTTP server."
    )
    parser.add_argument(
        "--url",
        default="http://localhost:8080/hello",
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
        help="How long to run the load test, in seconds (default: %(default)s)",
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
    print(f"Duration:     {args.duration}s")
    print(f"Server label: workers={args.workers_label}")
    print("Running...")

    result = asyncio.run(
        run_benchmark(args.url, args.concurrency, args.duration)
    )

    result["server_workers_label"] = args.workers_label

    print()
    print("Results:")
    print(f"  Total requests:      {result['total_requests']}")
    print(f"  Successful:          {result['successful_requests']}")
    print(f"  Failed:              {result['failed_requests']}")
    print(f"  Requests/sec:        {result['requests_per_second']}")
    print(f"  Latency avg (ms):    {result['latency_ms']['avg']}")
    print(f"  Latency p50 (ms):    {result['latency_ms']['p50']}")
    print(f"  Latency p95 (ms):    {result['latency_ms']['p95']}")
    print(f"  Latency p99 (ms):    {result['latency_ms']['p99']}")

    if result["failed_requests"] > 0:
        print(f"  Sample errors:       {result['sample_errors']}")

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    filename = f"http_workers{args.workers_label}_c{args.concurrency}.json"
    output_path = output_dir / filename

    with open(output_path, "w") as f:
        json.dump(result, f, indent=2)

    print()
    print(f"Saved: {output_path}")


if __name__ == "__main__":
    main()