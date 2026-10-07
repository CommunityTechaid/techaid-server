#!/usr/bin/env bash
# One-shot logical backup of techaid_prod into tadaarchive2026/db-dumps, verified before upload.
# Written 2026-09-23 for the special-category-data incident: Burstable cannot take on-demand
# backups, so this is the "clean backup" taken after the scrub + VACUUM FULL.
#
# Usage (Tony, from Git Bash, Docker Desktop running):
#   ADMIN_PW_FILE=/path/to/pwfile [FRAGMENTS_FILE=/path/to/fragments.txt] bash scripts/prod-clean-dump.sh
#
#   ADMIN_PW_FILE   file holding the techaid_admin password (read, never printed)
#   FRAGMENTS_FILE  optional; one distinctive fragment of a scrubbed value per line. The script
#                   prints only the NUMBER of matching lines in the dump. It must be 0 to upload.
#
# Runs from this machine (its IP is on the Postgres firewall), so no firewall rule is opened.
# The dump exists only inside a --rm container's /tmp and is never written to the host disk.
# techaid_admin is a member of pg_read_all_data (verified 2026-09-23), so gdpr + import dump too.
set -euo pipefail
export MSYS_NO_PATHCONV=1

: "${ADMIN_PW_FILE:?set ADMIN_PW_FILE to the file holding the techaid_admin password}"
[ -f "$ADMIN_PW_FILE" ] || { echo "no such file: $ADMIN_PW_FILE" >&2; exit 2; }
if [ -n "${FRAGMENTS_FILE:-}" ]; then
  [ -f "$FRAGMENTS_FILE" ] || { echo "no such file: $FRAGMENTS_FILE" >&2; exit 2; }
  [ -s "$FRAGMENTS_FILE" ] || { echo "FRAGMENTS_FILE is empty" >&2; exit 2; }
fi
docker info >/dev/null 2>&1 || { echo "Docker is not running. Start Docker Desktop first." >&2; exit 4; }

SA=tadaarchive2026
CONTAINER=db-dumps
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BLOB="techaid-pg-svr/techaid_prod-${STAMP}.dump"

az storage container create --account-name "$SA" -n "$CONTAINER" --auth-mode login -o none
EXPIRY=$(date -u -d '+2 hours' +%Y-%m-%dT%H:%MZ)
SAS=$(az storage blob generate-sas --account-name "$SA" -c "$CONTAINER" -n "$BLOB" \
  --permissions cw --expiry "$EXPIRY" --auth-mode login --as-user --https-only -o tsv)
URL="https://${SA}.blob.core.windows.net/${CONTAINER}/${BLOB}?${SAS}"

FRAG_MOUNT=()
if [ -n "${FRAGMENTS_FILE:-}" ]; then
  FDIR=$(cd "$(dirname "$FRAGMENTS_FILE")" && pwd)
  FRAG_MOUNT=(-v "$(cygpath -w "$FDIR" 2>/dev/null || echo "$FDIR")":/frag:ro -e FRAG="/frag/$(basename "$FRAGMENTS_FILE")")
fi

docker run --rm -i \
  -e PGPASSWORD="$(cat "$ADMIN_PW_FILE")" -e URL="$URL" "${FRAG_MOUNT[@]}" \
  postgres:17-alpine sh -eu -s <<'INNER'
CONN="host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=techaid_admin sslmode=require connect_timeout=15"
apk add --no-cache -q curl
echo "== dumping"
pg_dump -Fc -f /tmp/d.dump "$CONN"
ls -l /tmp/d.dump; sha256sum /tmp/d.dump

echo "== row counts: table | in dump | live now (small drift = writes since the dump)"
# busybox awk mangles backslash escapes, so the end-of-COPY marker is built with sprintf.
pg_restore --data-only -f - /tmp/d.dump \
  | awk 'BEGIN{eod=sprintf("%c.", 92)} /^COPY /{t=$2; n=0; inb=1; next} inb && $0 == eod {print t, n; inb=0; next} inb{n++}' \
  > /tmp/dump_counts
psql "$CONN" -XAt -F' ' > /tmp/live_counts <<'SQL'
select table_schema || '.' || table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
  from information_schema.tables
 where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema');
SQL
awk 'FILENAME==ARGV[1]{d[$1]=$2; next} {l[$1]=$2}
     END{for (t in d) if (!(t in l)) l[t]="MISSING"
         for (t in l) printf "%-50s %9s %9s%s\n", t, ((t in d) ? d[t] : "MISSING"), l[t], (((t in d) && d[t]==l[t]) ? "" : "  <-- differs")}' \
  /tmp/dump_counts /tmp/live_counts | sort
echo "tables in dump: $(wc -l < /tmp/dump_counts)   tables live: $(wc -l < /tmp/live_counts)"

if [ -n "${FRAG:-}" ]; then
  echo "== scrubbed-value check (count only, values never printed)"
  HITS=$(pg_restore -f - /tmp/d.dump | grep -c -i -F -f "$FRAG" || true)
  echo "lines in dump matching a fragment: $HITS"
  [ "$HITS" = "0" ] || { echo "NOT UPLOADING: the dump still contains a scrubbed value." >&2; exit 5; }
else
  echo "== scrubbed-value check SKIPPED (no FRAGMENTS_FILE)"
fi

echo "== uploading"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  -H 'x-ms-blob-type: BlockBlob' -H 'x-ms-version: 2023-11-03' \
  --data-binary @/tmp/d.dump "$URL")
echo "HTTP $CODE"; [ "$CODE" = "201" ]
INNER

az storage blob show --account-name "$SA" -c "$CONTAINER" -n "$BLOB" --auth-mode login \
  --query "{name:name, bytes:properties.contentLength, modified:properties.lastModified, tier:properties.blobTier}" -o table
echo "DONE: ${CONTAINER}/${BLOB}"
