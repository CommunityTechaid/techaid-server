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
      rules: properties.template.scale.rules}" -o json
  echo
done

echo "Expected steady state (as of 2026-07-04, source: infra/apply-scale-rules.sh):"
echo "  api-production:      min 0, max 1, cron rule 'business-hours' Mon-Fri 08:00-20:00 Europe/London"
echo "  superset-production: min 0, max 1, cron rule 'business-hours' Mon-Fri 09:00-17:00 Europe/London"
echo "  api-testing:         min 0, max 1, rules: null (default HTTP scaler — wakes on request)"
echo "min 1 means someone pinned a warm replica (e.g. during an incident); reverting"
echo "it is a change-control decision, not something this script does. To re-apply"
echo "the cron rules after recreating an app, use infra/apply-scale-rules.sh."
