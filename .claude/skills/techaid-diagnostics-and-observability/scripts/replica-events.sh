#!/usr/bin/env bash
# replica-events.sh — Container Apps replica lifecycle events for one app.
#
# Usage:   ./replica-events.sh [api-production|api-testing] [--hours N]
# Example: ./replica-events.sh api-production --hours 48
#
# Shows ContainerAppSystemLogs_CL chronologically: scale activations, probe
# results, container create/terminate events. This is the one-command view
# that diagnosed the 2026-07-01 outage (repeated ContainerCreateFailure on
# one revision = broken Azure host node, not an app fault).
# Requires: az CLI, already logged in. Read-only.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: stop it mangling /-prefixed args

APP="api-production"
HOURS=24
while [ $# -gt 0 ]; do
  case "$1" in
    api-production|api-testing) APP="$1"; shift ;;
    --hours) HOURS="$2"; shift 2 ;;
    *) echo "Usage: $0 [api-production|api-testing] [--hours N]" >&2; exit 2 ;;
  esac
done

WORKSPACE_GUID=$(az monitor log-analytics workspace show \
  -g tada-2026 -n workspace-tada2026ubat --query customerId -o tsv)

QUERY="ContainerAppSystemLogs_CL
| where TimeGenerated > ago(${HOURS}h)
| where ContainerAppName_s == '${APP}'
| project TimeGenerated, Reason_s, RevisionName_s, Log_s
| order by TimeGenerated asc"

az monitor log-analytics query -w "$WORKSPACE_GUID" --analytics-query "$QUERY" -o table
