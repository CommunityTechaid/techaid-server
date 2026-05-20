#!/usr/bin/env bash
# Applies KEDA cron scale rules to production Container Apps.
#
# Run this after recreating a production Container App — scale config is
# not stored in any IaC file and must be re-applied manually if the app is
# destroyed and recreated.
#
# The cron timezone field handles GMT/BST transitions automatically.

set -euo pipefail

SUBSCRIPTION="b7981f4a-b5b8-482a-ab3d-fa3b14b8006a"
RESOURCE_GROUP="tada-2026"

apply_cron() {
  local app_name="$1"
  local start_cron="$2"
  local end_cron="$3"

  MSYS_NO_PATHCONV=1 az rest \
    --method PATCH \
    --url "https://management.azure.com/subscriptions/${SUBSCRIPTION}/resourceGroups/${RESOURCE_GROUP}/providers/Microsoft.App/containerApps/${app_name}?api-version=2024-03-01" \
    --body "{
      \"properties\": {
        \"template\": {
          \"scale\": {
            \"minReplicas\": 0,
            \"maxReplicas\": 1,
            \"rules\": [
              {
                \"name\": \"business-hours\",
                \"custom\": {
                  \"type\": \"cron\",
                  \"metadata\": {
                    \"desiredReplicas\": \"1\",
                    \"start\": \"${start_cron}\",
                    \"end\": \"${end_cron}\",
                    \"timezone\": \"Europe/London\"
                  }
                }
              }
            ]
          }
        }
      }
    }"

  echo "Scale rules applied to ${app_name}."
}

# Mon–Fri 08:00–20:00 London: 1 replica warm
apply_cron "api-production" "0 8 * * 1-5" "0 20 * * 1-5"

# Mon–Fri 09:00–17:00 London: 1 replica warm
apply_cron "superset-production" "0 9 * * 1-5" "0 17 * * 1-5"
