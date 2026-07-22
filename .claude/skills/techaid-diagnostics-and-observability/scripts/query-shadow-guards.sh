#!/usr/bin/env bash
# query-shadow-guards.sh — what the status-progression guards WOULD have blocked.
#
# The wipe-cert guard (#68) and the blocking-flag sub-status guard (#90) both ship
# in SHADOW mode: they evaluate, WARN-log a "would-block" line, and allow the write.
# Reading those lines is the actual deliverable of both issues — the enforcement
# flags must not be switched on until the rate is stable and every logged kit is
# accounted for. Both guards emit the same "would-block" token on purpose, so one
# query covers both.
#
# Usage:   ./query-shadow-guards.sh [--days N] [--raw]     (default 30 days, summary)
# Example: ./query-shadow-guards.sh --days 7 --raw
#
# Requires: az CLI, already logged in (az login), subscription "CTA Nonprofit
# Azure Grant". Read-only. Resolves the Log Analytics workspace GUID itself.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: stop it mangling /-prefixed args

DAYS=30
RAW=0
while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --raw)  RAW=1; shift ;;
    *) echo "Usage: $0 [--days N] [--raw]" >&2; exit 2 ;;
  esac
done

# Query the WORKSPACE, not the App Insights component. TaDa-API is workspace-based,
# so `az monitor app-insights query` targets classic AI storage and silently returns
# nothing — see the techaid-diagnostics-and-observability skill.
WORKSPACE_GUID=$(az monitor log-analytics workspace show \
  -g tada-2026 -n workspace-tada2026ubat --query customerId -o tsv)

# Log shape both guards emit (kotlin-logging drops the Kt suffix, so the logger is
# cta.app.services.{WipeCert,BlockingFlag}GuardService):
#   wipe-cert     would-block: kitId=42 type=LAPTOP transition=A->B enforcementPoint=updateKit (shadow mode: allowed)
#   blocking-flag would-block: kitId=42 flags=wipeFailed,needsSparePart transition=A->B enforcementPoint=updateKit (shadow mode: allowed)
# NB: `first`/`last` are reserved words in KQL — hence firstSeen/lastSeen.
if [ "$RAW" -eq 1 ]; then
  QUERY="AppTraces
| where TimeGenerated > ago(${DAYS}d)
| where Message has 'would-block'
| project TimeGenerated, AppRoleInstance, Message
| order by TimeGenerated desc
| take 100"
else
  QUERY="AppTraces
| where TimeGenerated > ago(${DAYS}d)
| where Message has 'would-block'
| extend guard = extract(@'^(\\S+) would-block', 1, Message),
         point = extract(@'enforcementPoint=(\\S+)', 1, Message),
         kitId = extract(@'kitId=(\\d+)', 1, Message)
| summarize events = count(),
            kits = dcount(kitId),
            firstSeen = min(TimeGenerated),
            lastSeen = max(TimeGenerated)
  by guard, point
| order by events desc"
fi

az monitor log-analytics query -w "$WORKSPACE_GUID" --analytics-query "$QUERY" -o table

cat <<'EOF'

No rows is a legitimate result — read it before concluding anything.
As of 2026-07-22 this returns nothing over 30 days, and that is correct: neither
guard has ever run in production. The wipe-cert guard shipped in v2.3.0 and the
blocking-flag guard in v2.4.0, while production still runs 2.1.0; UAT has both but
only thin bench traffic. The signal starts after the production promote.

To tell an empty result apart from broken telemetry, check WARNs are flowing at all:
  AppTraces | where TimeGenerated > ago(2d) | summarize n=count() by SeverityLevel
SeverityLevel 2 is WARN. If that is non-zero and this script is empty, the guards
genuinely did not fire.

Watch enforcementPoint=updateDeviceRequest.REQUEST_COMPLETED: it sets
DISTRIBUTION_DELIVERED in bulk and is the likeliest legitimate workflow needing a
carve-out. Issue #90 explicitly forbids exempting it without this data.
EOF
