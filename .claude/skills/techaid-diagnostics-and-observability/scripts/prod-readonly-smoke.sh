#!/usr/bin/env bash
# Read-only production smoke for techaid-server + techaid-dashboard.
#
# SAFETY: every check is anonymous and read-only. No mutations, no auth, no writes,
# no emails. Safe to run against production at any time, as often as you like.
#
# Deliberately NOT included, because they are not benign against production:
#   - the full Playwright suite  -> creates real donors/device requests/kits/orgs
#   - @live-smoke                -> submits a REAL booking and sends a REAL email
#                                   (written for UAT, whose Turnstile key is
#                                    Cloudflare's always-passes test key)
#   - e2e:cleanup                -> a deletion tool
#
# Usage: bash prod-readonly-smoke.sh

API=https://api.communitytechaid.org.uk
APP=https://app.communitytechaid.org.uk
pass=0; fail=0

ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1"; echo "        got: $2"; fail=$((fail+1)); }

gql() { curl -s -m 90 -X POST "$API/graphql" -H "Content-Type: application/json" -d "$1"; }

echo "=== 1. API identity and health ==="
info=$(curl -s -m 120 "$API/actuator/info")
echo "$info" | grep -q '"version":"2.4.0"' \
  && ok "version 2.4.0" || bad "version 2.4.0" "$info"
echo "$info" | grep -q '3aa71fff3c6782d789d95fe21833def91b5d7229' \
  && ok "commit 3aa71ff" || bad "commit 3aa71ff" "$info"

health=$(curl -s -m 60 "$API/actuator/health")
echo "$health" | grep -q '"status":"UP"' \
  && ok "health UP" || bad "health UP" "$health"
# Hardening that landed with this promote: no component details to anonymous callers.
echo "$health" | grep -q '"components"' \
  && bad "health leaks no component details" "$health" \
  || ok "health leaks no component details"

echo
echo "=== 2. Public GraphQL surface still serves anonymously ==="
r=$(gql '{"query":"query { referringOrganisationsPublic(where:{name:{_contains:\"a\"}}) { id name } }"}')
echo "$r" | grep -q '"referringOrganisationsPublic":\[' \
  && ok "referringOrganisationsPublic returns data" \
  || bad "referringOrganisationsPublic returns data" "$r"

r=$(gql '{"query":"query { featureFlagsPublic { key enabled } }"}')
echo "$r" | grep -q '"featureFlagsPublic":\[' \
  && ok "featureFlagsPublic returns data" || bad "featureFlagsPublic returns data" "$r"

r=$(gql '{"query":"query { deliveryAvailabilityPublic { date dayLabel } }"}')
echo "$r" | grep -q '"deliveryAvailabilityPublic":\[' \
  && ok "deliveryAvailabilityPublic returns data" \
  || bad "deliveryAvailabilityPublic returns data" "$r"

echo
echo "=== 3. Auth gates enforced anonymously (the 17 new @PreAuthorize) ==="
for op in \
  'mutation { synchronizeCollectionDataForDeviceRequest(data:{id:1}) { id } }' \
  'mutation { createReferringOrganisation(data:{name:"__smoke__"}) { id } }' \
  'query { location(address:"London") { lat lng } }' \
  'query { featureFlags { key enabled } }'
do
  name=$(echo "$op" | sed 's/[^a-zA-Z]*\([a-zA-Z]*\).*/\1/;s/mutation//;s/query//')
  r=$(gql "$(printf '{"query":%s}' "$(printf '%s' "$op" | sed 's/\\/\\\\/g;s/"/\\"/g;s/^/"/;s/$/"/')")")
  echo "$r" | grep -q 'Access Denied' \
    && ok "denied anonymously: ${op:0:48}..." \
    || bad "denied anonymously: ${op:0:48}..." "$r"
done

echo
echo "=== 4. Feature flags shipped OFF (public booking page stays hidden) ==="
r=$(gql '{"query":"query { featureFlagsPublic { key enabled } }"}')
for f in delivery-booking update-scanner blocking-flag-enforcement wipe-cert-enforcement; do
  echo "$r" | grep -q "{\"key\":\"$f\",\"enabled\":false}" \
    && ok "$f = false" || bad "$f = false" "$(echo "$r" | grep -o "\"key\":\"$f\"[^}]*}")"
done

echo
echo "=== 5. Dashboard is served and routes correctly ==="
code=$(curl -s -m 60 -o /dev/null -w '%{http_code}' "$APP/")
[ "$code" = "200" ] && ok "app root 200" || bad "app root 200" "HTTP $code"

# SPA deep link must fall through to index.html, not 404.
code=$(curl -s -m 60 -o /dev/null -w '%{http_code}' "$APP/donors")
[ "$code" = "200" ] && ok "SPA deep link /donors 200" || bad "SPA deep link /donors 200" "HTTP $code"

bundle=$(curl -s -m 60 "$APP/" | grep -o 'main-[A-Za-z0-9]*\.js' | head -1)
if [ -n "$bundle" ]; then
  code=$(curl -s -m 60 -o /dev/null -w '%{http_code}' "$APP/$bundle")
  [ "$code" = "200" ] && ok "bundle $bundle loads" || bad "bundle loads" "HTTP $code"
else
  bad "bundle reference found in index.html" "none"
fi

echo
echo "=== 6. Security headers present on the dashboard ==="
hdrs=$(curl -s -m 60 -D - -o /dev/null "$APP/")
for h in "content-security-policy" "x-content-type-options" "strict-transport-security"; do
  echo "$hdrs" | grep -qi "^$h:" && ok "$h present" || bad "$h present" "missing"
done

echo
echo "==================================================="
echo "  PASS: $pass    FAIL: $fail"
[ "$fail" -eq 0 ] && echo "  RESULT: prod looks healthy (read-only checks)" \
                  || echo "  RESULT: investigate the failures above"
echo "==================================================="
echo
echo "NOTE: this proves the surface is up and correctly gated. It cannot prove the"
echo "system is stable under real use — that comes from tomorrow's business-hours"
echo "traffic, the shadow-guard logs, and watch-calendar-sync.sh."
[ "$fail" -eq 0 ]
