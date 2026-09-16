#!/usr/bin/env bash
# Summarize a JMeter .jtl (CSV) into the three numbers that matter: QPS, P99, error rate.
# Usage: loadtest/stats.sh /tmp/gateway.jtl
set -euo pipefail

JTL="${1:?usage: stats.sh <file.jtl>}"

awk -F, '
NR == 1 { next }                                  # header row
{
  elapsed[++n] = $2 + 0
  code = $4
  if (code !~ /^2/) errors++
  if (first == 0 || $1 + 0 < first) first = $1 + 0
  if ($1 + 0 > last) last = $1 + 0
}
END {
  if (n == 0) { print "no samples"; exit 1 }
  asort(elapsed)
  duration = (last - first) / 1000.0
  if (duration <= 0) duration = 0.001
  avg = 0
  for (i = 1; i <= n; i++) avg += elapsed[i]
  avg /= n
  p50 = elapsed[int(n * 0.50) < 1 ? 1 : int(n * 0.50)]
  p95 = elapsed[int(n * 0.95) < 1 ? 1 : int(n * 0.95)]
  p99 = elapsed[int(n * 0.99) < 1 ? 1 : int(n * 0.99)]
  printf "samples=%-8d duration=%.1fs\n", n, duration
  printf "QPS=%.1f\n", n / duration
  printf "latency_ms: min=%d avg=%.1f p50=%d p95=%d p99=%d max=%d\n", elapsed[1], avg, p50, p95, p99, elapsed[n]
  printf "errors=%d error_rate=%.2f%%\n", errors, errors * 100.0 / n
}
' "$JTL"
