#!/usr/bin/env bash
# check-health.sh — cold-start-aware health check for the TechAid API.
#
# Usage:   ./check-health.sh <testing|production>
# Example: ./check-health.sh testing
#
# Curls /actuator/health with retries (the app scales to zero; first request
# can take ~40s while a replica starts), then prints the running build's git
# commit from /actuator/info. No auth or az login required. Read-only.
set -euo pipefail

ENV_NAME="${1:-}"
case "$ENV_NAME" in
  testing)    BASE_URL="https://api-testing.communitytechaid.org.uk" ;;
  production) BASE_URL="https://api.communitytechaid.org.uk" ;;
  *) echo "Usage: $0 <testing|production>" >&2; exit 2 ;;
esac

MAX_ATTEMPTS=12   # 12 x 5s pause + request time ≈ 90s worst case, covers cold start
ATTEMPT=1
STATUS_JSON=""

echo "Checking ${BASE_URL}/actuator/health (up to ${MAX_ATTEMPTS} attempts; cold start can take ~40s)..."
while [ "$ATTEMPT" -le "$MAX_ATTEMPTS" ]; do
  # -m 20: generous per-request timeout so a cold-start request can complete.
  if STATUS_JSON=$(curl -sf -m 20 "${BASE_URL}/actuator/health" 2>/dev/null); then
    break
  fi
  echo "  attempt ${ATTEMPT}/${MAX_ATTEMPTS}: not up yet"
  ATTEMPT=$((ATTEMPT + 1))
  sleep 5
done

if [ -z "$STATUS_JSON" ]; then
  echo "VERDICT: DOWN — no successful response after ${MAX_ATTEMPTS} attempts." >&2
  echo "Next step: check replica events with ./replica-events.sh (same skill)." >&2
  exit 1
fi

# Anonymous callers get status-only (show-details: when-authorized), e.g. {"status":"UP"}
echo "Health response: ${STATUS_JSON}"
if printf '%s' "$STATUS_JSON" | grep -q '"status":"UP"'; then
  echo "VERDICT: UP"
else
  echo "VERDICT: responding but NOT UP"
fi

# Build info: git.commit is injected at image build time (build.gradle buildInfo block).
# Shape: {"build":{"version":"2.1.0",...,"git":{"commit":"<full-sha>"},...}}
INFO_JSON=$(curl -sf -m 20 "${BASE_URL}/actuator/info" 2>/dev/null || true)
if [ -n "$INFO_JSON" ]; then
  COMMIT=$(printf '%s' "$INFO_JSON" | grep -o '"git":{"commit":"[^"]*"' | head -1 | sed 's/.*"commit":"//; s/"$//' || true)
  VERSION=$(printf '%s' "$INFO_JSON" | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
  echo "Running build: version ${VERSION:-?}, git.commit ${COMMIT:-<not found in /actuator/info>}"
else
  echo "Note: /actuator/info not readable (unexpected — it is in the exposed endpoint list)."
fi
