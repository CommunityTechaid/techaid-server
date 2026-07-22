#!/usr/bin/env bash
# verify-scale-rules.sh — show current replica bounds and scale rules for the
# three business-hours Container Apps. Read-only; flags drift from the
# expected KEDA cron setup (min 0 / max 1 / a cron rule).
#
# Usage:   ./verify-scale-rules.sh
# Requires: az CLI, already logged in.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: stop it mangling /-prefixed args

for APP in api-testing api-production superset-production; do
  echo "=== ${APP} (rg tada-2026) ==="
  az containerapp show -g tada-2026 -n "$APP" --query \
    "{minReplicas: properties.template.scale.minReplicas,
      maxReplicas: properties.template.scale.maxReplicas,
      cooldownPeriod: properties.template.scale.cooldownPeriod,
      rules: properties.template.scale.rules}" -o json
  echo
done

echo "Expected steady state (as of 2026-07-22, source: infra/apply-scale-rules.sh + the"
echo "manual cooldown bump below):"
echo "  api-production:      min 0, max 1, cooldown 300, cron rule 'business-hours' Mon-Fri 08:00-20:00 Europe/London"
echo "  superset-production: min 0, max 1, cooldown 300, cron rule 'business-hours' Mon-Fri 09:00-17:00 Europe/London"
echo "  api-testing:         min 0, max 1, cooldown 900, rules: null (default HTTP scaler — wakes on request)"
echo
echo "api-testing's cooldownPeriod is 900 (15 min), NOT Azure's 300 s default — set live on"
echo "2026-07-22 so staff testing UAT stop eating the ~40-90 s cold start mid-session."
echo "Like the cron rules it is NOT in any IaC file, so it reverts to 300 if the app is ever"
echo "recreated. If this script reports 300 for api-testing, that is the drift to fix."
echo "Gotcha: infra/apply-scale-rules.sh uses api-version 2024-03-01, which REJECTS"
echo "cooldownPeriod ('Unknown properties cooldownPeriod in ContainerAppScale are not"
echo "supported'). Setting it needs 2025-01-01 or later."
echo "min 1 means someone pinned a warm replica (e.g. during an incident); reverting"
echo "it is a change-control decision, not something this script does. To re-apply"
echo "the cron rules after recreating an app, use infra/apply-scale-rules.sh."
