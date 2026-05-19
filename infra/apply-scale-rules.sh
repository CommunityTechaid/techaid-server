#!/usr/bin/env bash
# Applies KEDA cron scale rules to api-production.
#
# Run this after recreating the api-production Container App — scale config is
# not stored in any IaC file and must be re-applied manually if the app is
# destroyed and recreated.
#
# Rules:
#   - Mon–Fri 08:00–20:00 Europe/London: 1 replica (warm standby)
#   - All other times: 0 replicas (scale to zero)
#
# The cron timezone field handles GMT/BST transitions automatically.

set -euo pipefail

SUBSCRIPTION="b7981f4a-b5b8-482a-ab3d-fa3b14b8006a"
RESOURCE_GROUP="tada-2026"
APP_NAME="api-production"

MSYS_NO_PATHCONV=1 az rest \
  --method PATCH \
  --url "https://management.azure.com/subscriptions/${SUBSCRIPTION}/resourceGroups/${RESOURCE_GROUP}/providers/Microsoft.App/containerApps/${APP_NAME}?api-version=2024-03-01" \
  --body '{
    "properties": {
      "template": {
        "scale": {
          "minReplicas": 0,
          "maxReplicas": 1,
          "rules": [
            {
              "name": "business-hours",
              "custom": {
                "type": "cron",
                "metadata": {
                  "desiredReplicas": "1",
                  "start": "0 8 * * 1-5",
                  "end": "0 20 * * 1-5",
                  "timezone": "Europe/London"
                }
              }
            }
          ]
        }
      }
    }
  }'

echo "Scale rules applied to ${APP_NAME}."
