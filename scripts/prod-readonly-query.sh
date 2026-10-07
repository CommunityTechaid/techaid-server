#!/usr/bin/env bash
# Run a SQL file against techaid_prod, READ ONLY. Usage:
#   bash scripts/prod-readonly-query.sh path/to/query.sql
#
# Guards (defence in depth, not a security boundary: api_prod can still write):
#   1. The file is refused if it contains any write/DDL keyword, or tries to turn read-only off.
#   2. The session starts with default_transaction_read_only=on, so a write that slips past (1)
#      fails with "cannot execute ... in a read-only transaction".
#   3. The script aborts unless it is connected to techaid_prod.
# It never touches the Postgres firewall. If your IP is not allowlisted, it fails and says so.
set -euo pipefail
export MSYS_NO_PATHCONV=1

SQL="${1:?usage: prod-readonly-query.sh <file.sql>}"
[ -f "$SQL" ] || { echo "no such file: $SQL" >&2; exit 2; }

# Strip -- comments before scanning so prose in comments doesn't trip the guard.
if sed 's/--.*$//' "$SQL" | grep -Eiwq \
  'insert|update|delete|merge|upsert|truncate|drop|alter|create|grant|revoke|copy|vacuum|reindex|cluster|refresh|call|do|lock|comment|security|read[[:space:]]+write|transaction_read_only|default_transaction_read_only|set[[:space:]]+role|set[[:space:]]+session'; then
  echo "REFUSED: $SQL contains a write/DDL keyword or tries to change read-only mode." >&2
  exit 3
fi

docker info >/dev/null 2>&1 || { echo "Docker is not running. Start Docker Desktop first." >&2; exit 4; }

PW=$(az containerapp secret show -g tada-2026 -n api-production \
  --secret-name datasource-password --query value -o tsv)
DIR=$(cd "$(dirname "$SQL")" && pwd)
WDIR=$(cygpath -w "$DIR" 2>/dev/null || echo "$DIR")

docker run --rm -e PGPASSWORD="$PW" \
  -e PGOPTIONS="-c default_transaction_read_only=on" \
  -v "$WDIR":/w:ro postgres:17-alpine \
  psql "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=api_prod sslmode=require connect_timeout=15" \
  -v ON_ERROR_STOP=1 -X \
  -c "DO \$\$ BEGIN IF current_database() <> 'techaid_prod' OR current_setting('transaction_read_only') <> 'on' THEN RAISE EXCEPTION 'ABORT: not a read-only techaid_prod session'; END IF; END \$\$;" \
  -f "/w/$(basename "$SQL")"
