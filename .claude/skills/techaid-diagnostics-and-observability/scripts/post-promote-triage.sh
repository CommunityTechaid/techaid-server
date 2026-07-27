#!/usr/bin/env bash
# post-promote-triage.sh — find erroneous or failed production activity, fast.
#
# Written for the morning after the 2026-07-27 promote (2.1.0 -> 2.4.0), but it is a
# general "is production actually OK?" sweep. Every section prints the measured
# pre-promote BASELINE next to the live number, because none of these figures mean
# anything on their own.
#
# THE CENTRAL LESSON THIS SCRIPT ENCODES:
#   Over the 7 days before the promote production served 13,652 requests with
#   ZERO failures — and 780+ GraphQL errors. GraphQL returns HTTP 200 with errors
#   in the body, so `AppRequests | where Success == false` is blind to almost every
#   real fault this system has. Section 3 is the one that actually finds problems.
#
# Usage:   ./post-promote-triage.sh [--hours N]        (default 24)
# Example: ./post-promote-triage.sh --hours 12
#
# Requires: az CLI, logged in (az login), subscription "CTA Nonprofit Azure Grant".
# Strictly read-only.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: stop it mangling /-prefixed args

HOURS=24
while [ $# -gt 0 ]; do
  case "$1" in
    --hours) HOURS="$2"; shift 2 ;;
    *) echo "Usage: $0 [--hours N]" >&2; exit 2 ;;
  esac
done

# Query the WORKSPACE, not the App Insights component — TaDa-API is workspace-based.
WS=$(az monitor log-analytics workspace show \
  -g tada-2026 -n workspace-tada2026ubat --query customerId -o tsv)

# api-testing and api-production share AppRoleName ('techaid-api') AND the same App
# Insights component, so neither AppRoleName nor _ResourceId can separate them.
# AppRoleInstance is the only discriminator.
PROD="| where AppRoleInstance startswith 'api-production'"

q() { az monitor log-analytics query -w "$WS" --analytics-query "$1" -o table; }

echo "############ PRODUCTION TRIAGE — last ${HOURS}h ############"
echo
echo "=== 1. Is prod still running the build we promoted? ==="
echo "    EXPECT commit 3aa71ff / version 2.4.0. A different value means someone"
echo "    rolled back or deployed over it."
curl -s -m 120 https://api.communitytechaid.org.uk/actuator/info; echo
curl -s -m 60  https://api.communitytechaid.org.uk/actuator/health; echo

echo
echo "=== 2. Failed requests (the shallow check — expect this to stay clean) ==="
echo "    BASELINE 7d pre-promote: 0 failed / 13,652 total, p95 360ms, p99 2081ms."
echo "    Latency well above that baseline is a candidate finding (12 new indexes"
echo "    landed in this promote and had never been measured against real data)."
echo
echo "    !! DO NOT PANIC AT THE FIRST MORNING NUMBERS. The app scales to zero, so"
echo "       the first request of the day pays a 40-90s cold start and lands in the"
echo "       percentiles. On a small sample that alone produces p95 in the seconds:"
echo "       a 3h window on promote night showed p95 4399ms / p99 9962ms over just"
echo "       37 requests, all of it cold start and probes. Judge latency only once"
echo "       total is in the hundreds, and use the cold-start-excluded figure below."
q "AppRequests
| where TimeGenerated > ago(${HOURS}h)
${PROD}
| summarize total=count(), failed=countif(Success==false),
            p95_ms=percentile(DurationMs,95), p99_ms=percentile(DurationMs,99)"
echo "    -- robust view: median is unmoved by a handful of cold starts. Compare"
echo "       median against the baseline instead of p95 on a small sample."
echo "       BASELINE 7d pre-promote (non-actuator, production):"
echo "         requests 12,927 | median 10.4ms | p95 376ms | over_5s 33 | slowest 41.3s"
echo "       The 41s slowest and the 33 over-5s ARE the scale-from-zero cold starts;"
echo "       they were normal before this promote and remain so."
q "AppRequests
| where TimeGenerated > ago(${HOURS}h)
${PROD}
| where Name !contains 'actuator'
| summarize app_requests=count(),
            median_ms=percentile(DurationMs,50),
            p95_ms=percentile(DurationMs,95),
            over_5s=countif(DurationMs > 5000),
            slowest_ms=max(DurationMs)"
echo "       over_5s counts probable cold starts; a couple per scale-from-zero is"
echo "       normal. Many, spread through a busy period, is a real problem."

echo
echo "=== 3. GraphQL errors — THE REAL SIGNAL (HTTP 200, invisible above) ==="
echo "    BASELINE 7d pre-promote, production:"
echo "      findAll               Access Denied ......... 740   <-- see section 4"
echo "      updateKit             Access Denied ........... 9"
echo "      findKit               Access Denied ........... 6"
echo "      findAllDeviceRequests Access Denied ........... 6"
echo "      createDeviceRequest   'already has 3 requests'   3   (business rule, benign)"
echo "      createDeviceRequest   non-null returned null .. 3   <-- PRE-EXISTING BUG"
echo "      updateDeviceRequest   invalid date format ..... 1"
echo "      AutoUpdateKit         ANTLR syntax error ...... 1   <-- Apps Script bad input"
echo "    ANY new error string not in that list is a candidate regression."
q "AppTraces
| where TimeGenerated > ago(${HOURS}h)
${PROD}
| where Message startswith 'GraphQL error'
| extend op  = extract(@'operation .(\\w+|<anonymous>).', 1, Message),
         err = extract(@'\\]: ([^(]+)', 1, Message)
| summarize events=count(), firstSeen=min(TimeGenerated), lastSeen=max(TimeGenerated)
  by op, err
| order by events desc"

echo
echo "=== 4. Access Denied by operation — did the 17 new auth gates break a caller? ==="
echo "    TWO OPPOSITE EXPECTATIONS HERE:"
echo "    (a) findAll SHOULD COLLAPSE from ~740/7d to ~0. That was the dashboard"
echo "        landing page firing before a token existed (#153), fixed in v1.3.2"
echo "        deployed the same night. If findAll is still high, the FE fix did not"
echo "        take — check the served bundle hash (expect main-BC76NI7H.js)."
echo "    (b) Any operation appearing here that was NOT in the section 3 baseline is"
echo "        a caller newly broken by a gate. Fix the caller's Auth0 grant — never"
echo "        weaken the gate."
q "AppTraces
| where TimeGenerated > ago(${HOURS}h)
${PROD}
| where Message contains 'Access Denied'
| extend op = extract(@'path=(\\w+)', 1, Message)
| summarize events=count(), lastSeen=max(TimeGenerated) by op
| order by events desc"

echo
echo "=== 5. Calendar sync — THE ONE UNPROVEN RISK OF THIS PROMOTE ==="
echo "    synchronizeCollectionDataForDeviceRequest gained write:organisations. Its"
echo "    Apps Script Auth0 grant was never proven to carry that scope, and it runs"
echo "    in PRODUCTION ONLY (zero UAT calls ever), so no soak exercised it."
echo "    CADENCE (measured): 2-7 calls per WEEKDAY, 08:00-16:00 UTC, none at weekends."
echo "    => Before ~10:00 UTC an empty result is INCONCLUSIVE, not success."
echo "    Anonymous probes excluded: a manual curl logs operation '<anonymous>' and"
echo "    looks identical to a broken sync."
q "AppTraces
| where TimeGenerated > ago(${HOURS}h)
${PROD}
| where Message contains 'Access Denied'
| where Message contains 'synchronizeCollectionDataForDeviceRequest'
| where Message !contains \"operation '<anonymous>'\"
| project TimeGenerated, Message
| order by TimeGenerated desc"
echo "    -- successful sync calls (the positive signal) --"
q "AppRequests
| where TimeGenerated > ago(${HOURS}h)
${PROD}
| where Name contains 'synchronizeCollectionData'
| summarize calls=count(), ok=countif(Success==true),
            earliest=min(TimeGenerated), latest=max(TimeGenerated)"

echo
echo "=== 6. Server exceptions ==="
echo "    BASELINE: zero in the window after cutover."
q "AppExceptions
| where TimeGenerated > ago(${HOURS}h)
${PROD}
| summarize events=count(), lastSeen=max(TimeGenerated) by ProblemId, OuterMessage
| order by events desc"

echo
echo "=== 7. Container restarts / probe failures ==="
echo "    A crash-loop shows here before it shows anywhere else. NEVER restart a"
echo "    wedged 0-replica revision — deploy a fresh one or roll back."
q "ContainerAppSystemLogs_CL
| where TimeGenerated > ago(${HOURS}h)
| where ContainerAppName_s == 'api-production'
| summarize events=count(), lastSeen=max(TimeGenerated) by Reason_s
| order by events desc"

echo
echo "=== 8. Dashboard browser telemetry — is the new bundle actually being used? ==="
echo "    BASELINE 7d: 967 AppPageViews + 553 AppBrowserTimings."
echo "    Zero page views during business hours = the SPA is failing to boot."
q "union AppPageViews, AppBrowserTimings
| where TimeGenerated > ago(${HOURS}h)
| summarize events=count(), lastSeen=max(TimeGenerated) by Type"

echo
echo "=== 9. Browser-side exceptions (dashboard JS) ==="
q "AppExceptions
| where TimeGenerated > ago(${HOURS}h)
| where ClientType == 'Browser' or isempty(AppRoleInstance)
| summarize events=count(), lastSeen=max(TimeGenerated) by ProblemId
| order by events desc"

cat <<'EOF'

############ NOT COVERED HERE — run separately ############

  ./query-shadow-guards.sh --days 1
      The wipe-cert (#68) and blocking-flag (#90) guards ran in PRODUCTION for the
      first time on 2026-07-27. Pre-promote baseline was ZERO would-block lines in
      30 days, correctly so — neither guard had ever executed in prod. Lines
      appearing now are the signal starting, NOT a fault. Do not flip either
      enforcement flag until the rate is stable (~2 weeks) and every logged kit is
      accounted for.

  bash prod-readonly-smoke.sh
      21 anonymous read-only checks of the API + dashboard surface.

############ HOW TO READ AN EMPTY RESULT ############

Empty is only meaningful if telemetry is flowing at all. Confirm with:
  AppTraces | where TimeGenerated > ago(2d) | summarize n=count() by SeverityLevel
SeverityLevel 2 is WARN. If that is non-zero and a section above is empty, the
absence is real.

KQL traps that have already cost time on this project:
  - `has` matches WHOLE TERMS, not substrings. `Message has 'ynchroniz'` silently
    returns nothing. Use `contains` for partial matches.
  - `first` and `last` are reserved words. Alias them (firstSeen/lastSeen).
  - Query the workspace, not the App Insights component.
EOF
