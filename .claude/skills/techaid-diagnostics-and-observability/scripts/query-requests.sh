#!/usr/bin/env bash
# query-requests.sh — AppRequests summary (count, avg + p95 duration by operation).
#
# Usage:   ./query-requests.sh [--hours N]     (default 24)
# Example: ./query-requests.sh --hours 6
#
# Requires: az CLI, already logged in (az login), subscription "CTA Nonprofit
# Azure Grant". Read-only. Resolves the Log Analytics workspace GUID itself.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: stop it mangling /-prefixed args

HOURS=24
while [ $# -gt 0 ]; do
  case "$1" in
    --hours) HOURS="$2"; shift 2 ;;
    *) echo "Usage: $0 [--hours N]" >&2; exit 2 ;;
  esac
done

WORKSPACE_GUID=$(az monitor log-analytics workspace show \
  -g tada-2026 -n workspace-tada2026ubat --query customerId -o tsv)

# AppRequests = workspace-based Application Insights server-request table.
# Name carries the GraphQL operation (e.g. "POST /graphql findAllKits") thanks
# to the span enrichment in GraphQlTelemetryInterceptor.
QUERY="AppRequests
| where TimeGenerated > ago(${HOURS}h)
| summarize requests = count(),
            failures = countif(Success == false),
            avg_ms = round(avg(DurationMs), 1),
            p95_ms = round(percentile(DurationMs, 95), 1)
  by Name
| order by requests desc"

az monitor log-analytics query -w "$WORKSPACE_GUID" --analytics-query "$QUERY" -o table
