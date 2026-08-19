# Production promote — August 2026 batch

**Prepared 2026-08-18. For execution later this week.** Single combined promote, decided by Tony
over the two-deploy split — one evening instead of two, at the cost of a two-step rollback.

You need: Git Bash, the `techaid_admin` password (Bitwarden), approval rights on the GitHub
`production` environment, and about 45 minutes.

---

## What is shipping

| repo | version | what |
|---|---|---|
| techaid-server | **3.0.0** | GDPR flag + pg_cron retired, `kitAudits` no-op flags, `deleteDonor` fix, **`coordinates` dropped from `donors` and `kits`** |
| techaid-dashboard | **1.4.0** | flag copy removed, device history hides automated rewrites |

The major bump is correct: `Donor.coordinates` and `Kit.coordinates` are gone from the GraphQL
schema, and the `gdpr-in-app-cleanup` flag no longer exists.

**Already done in production, not part of this promote:** the pg_cron job deletion (2026-08-18)
and the 2,116-kit `updated_at` correction (2026-08-18).

## Rollback targets — write these down before you start

- **Production image today:** `ghcr.io/communitytechaid/techaid-server:dev-8046593`
- **Image being promoted:** whatever `api-testing` runs at the time (`dev-42faa0e` as of preparing this)
- **PITR window:** 35 days, earliest restore 2026-07-15

---

## Step 0 — pre-flight (5 min)

```bash
cd /d/Code/techaid-server
git checkout dev && git pull

# prod is healthy and on the image you expect
curl -s -o /dev/null -w "%{http_code}\n" https://api.communitytechaid.org.uk/actuator/health
az containerapp show -g tada-2026 -n api-production \
  --query "properties.template.containers[0].image" -o tsv

# UAT is on the image you are about to promote, and it is healthy
az containerapp show -g tada-2026 -n api-testing \
  --query "properties.template.containers[0].image" -o tsv
curl -s -o /dev/null -w "%{http_code}\n" https://api-testing.communitytechaid.org.uk/actuator/health
```

Confirm your IP is still allowed through the Postgres firewall — the home rule drifts:

```bash
curl -s https://api.ipify.org; echo
az postgres flexible-server firewall-rule list --server-name techaid-pg-svr -g tada-2026 -o table
```

## Step 1 — logical dump (5 min)

Recovery for a dropped column is PITR only, and PITR restores a whole server rather than one
table. Take the dump first; it is the only cheap way back to the 16 donor rows that still hold
coordinates.

```bash
export MSYS_NO_PATHCONV=1
PW=$(az containerapp secret show -g tada-2026 -n api-production \
      --secret-name datasource-password --query value -o tsv)
mkdir -p /d/Code/_archive/prod-dumps

docker run --rm -e PGPASSWORD="$PW" -v "$(cygpath -w /d/Code/_archive/prod-dumps)":/w \
  postgres:17-alpine pg_dump -Fc -n public \
  -h techaid-pg-svr.postgres.database.azure.com -U api_prod -d techaid_prod \
  -f /w/techaid_prod_pre_coordinates_drop.dump

ls -la /d/Code/_archive/prod-dumps/
```

`-n public` is not optional: `api_prod` cannot read the `gdpr` schema, and an unrestricted
`pg_dump` dies with `permission denied for schema gdpr` leaving a 0-byte file. **Check the file
size before continuing.**

## Step 2 — replace the GDPR function in production (5 min)

**This must happen BEFORE the columns are dropped.** A plpgsql body resolves column names at
execution, so a stale function does not fail the deploy — it fails the next Friday 18:00
retention run, silently, in the only job now performing retention (#174).

Safe to run any time beforehand: a function that no longer scrubs coordinates still runs
correctly while the columns exist.

```bash
export MSYS_NO_PATHCONV=1
read -s -p "techaid_admin password: " ADMINPW; echo

docker run --rm -e PGPASSWORD="$ADMINPW" -v "$(cygpath -w /d/Code/techaid-server/db/admin)":/w \
  postgres:17-alpine psql \
  "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=techaid_admin sslmode=require" \
  -X -P pager=off -f /w/2026-08-18__admin_apply_gdpr_drop_coordinates_scrub.sql
```

**Gate:** the script prints `mentions_coordinates_must_be_0` — it must read **0**. Expected md5
afterwards is `8e14adec66f174413a4c363f82d9e91b` (identical to what UAT now runs).

Then prove it still executes as the app role, which is what CREATE OR REPLACE can quietly break:

```bash
PW=$(az containerapp secret show -g tada-2026 -n api-production \
      --secret-name datasource-password --query value -o tsv)
echo "SELECT gdpr.performgdprcleanup();" > /tmp/run.sql
docker run --rm -e PGPASSWORD="$PW" -v "$(cygpath -w /tmp)":/w postgres:17-alpine psql \
  "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=api_prod sslmode=require" \
  -X -P pager=off -f /w/run.sql
```

**Gate:** returns a summary string with no "kit coordinates" clause. It will erase anything past
retention — that is its job and the backlog is currently clear.

## Step 3 — promote the server (10 min)

```bash
gh workflow run promote.yml --repo CommunityTechaid/techaid-server
gh run list --workflow=promote.yml --limit 1
```

Approve the `production` environment gate in the GitHub UI when prompted. Leave `image_tag`
blank so it promotes whatever UAT is running.

**Then verify the deploy actually landed — do not trust a green commit list.** On 2026-08-18 a
CI build failed at the GHCR push, `deploy-testing` was skipped, and UAT silently stayed on the
previous image while the commit row still showed green (those were CodeQL and Release Please).

```bash
az containerapp show -g tada-2026 -n api-production \
  --query "properties.template.containers[0].image" -o tsv     # must be the new tag
curl -s -o /dev/null -w "%{http_code}\n" https://api.communitytechaid.org.uk/actuator/health
```

## Step 4 — verify production (10 min)

```bash
export MSYS_NO_PATHCONV=1
PW=$(az containerapp secret show -g tada-2026 -n api-production \
      --secret-name datasource-password --query value -o tsv)
```

Migrations, columns, function:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;
-- expect 26.08.18.1700, .1600, .1500 all true

SELECT count(*) AS coordinates_columns_remaining FROM information_schema.columns
 WHERE column_name='coordinates' AND table_schema='public';
-- expect 0

SELECT count(*) FILTER (WHERE prosrc ILIKE '%coordinates%') AS must_be_0, md5(prosrc)
  FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='gdpr' AND p.proname='performgdprcleanup' GROUP BY prosrc;

SELECT flag_key FROM feature_flags ORDER BY 1;
-- gdpr-in-app-cleanup must be ABSENT; the other four remain
```

Then the standing retention check — every row must read 0:

```bash
docker run --rm -e PGPASSWORD="$PW" -v "$(cygpath -w /d/Code/techaid-server/db/admin)":/w \
  postgres:17-alpine psql \
  "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=api_prod sslmode=require" \
  -X -P pager=off -f /w/gdpr_retention_verification.sql
```

And the API surface, with a prod bearer token:

- `{ kit(where: ...) { coordinates } }` → must error `Field 'coordinates' in type 'Kit' is undefined`
- `kitAudits` must return `changedNothingAudited` and `siblingKitsInRevision`
- `location(address: "SE1 1AA")` must still resolve — the live lookup is deliberately kept

## Step 5 — dashboard to production (10 min)

```bash
cd /d/Code/techaid-dashboard
gh workflow run deploy-prod.yml --repo CommunityTechaid/techaid-dashboard
```

Then, on https://app.communitytechaid.org.uk:

- a device's History tab shows the "N automated rewrites hidden" notice, and "Show them" restores them
- Admin Panel → Feature Flags lists four flags, no GDPR row
- the device index and a device detail page load (this is what a missed column would break)

## Step 6 — Superset (2 min)

Datasets → `kits` → **Sync columns from source**. It carries an auto-introspected `coordinates`
column (`superset_prod.table_columns` id 24) which is now stale. No chart, saved query or
dashboard uses it — checked directly — so nothing breaks either way; this just tidies it.

## Step 7 — bookkeeping

Promote first, then merge `dev` → `master`. **`master` needs explicit permission each time** —
it is the record of what production runs, not a deploy trigger.

---

## Rollback

**Two steps, in this order.** The second is not optional once the columns are gone.

```bash
# 1. re-pin the previous image
gh workflow run promote.yml --repo CommunityTechaid/techaid-server -f image_tag=dev-8046593

# 2. put the columns back, EMPTY
docker run --rm -e PGPASSWORD="$PW" -v "$(cygpath -w /d/Code/techaid-server/db/admin)":/w \
  postgres:17-alpine psql \
  "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=api_prod sslmode=require" \
  -X -P pager=off -f /w/2026-08-18__ROLLBACK_readd_coordinates_columns.sql
```

Step 2 is **proven, not theoretical** — it was run against UAT on 2026-08-18 after the drop, both
columns came back as nullable `jsonb`, and the app stayed healthy across a re-drop.

**Recognising the failure matters as much as fixing it.** Production runs `ddl-auto=none`, so the
old image does not fail at boot on a missing column — it fails at *runtime*, on every donor and
kit read. It presents as a total outage of the dashboard's main screens. The tell is
`column k1_0.coordinates does not exist` in the container logs, not anything in the health
endpoint.

Do not delete the `flyway_schema_history` row for `26.08.18.1700`. Rolling forward again re-drops
the columns; the migration is guarded with `IF EXISTS`.

---

## Known-not-blocking

- Two `login-callback-guarded-root` e2e specs fail on UAT. They failed identically before this
  batch, touch Auth0 login which nothing here changes, and the assertion (`authorizeHits() == 1`)
  is plausibly upset by the suite injecting a bearer token into storage state. Worth a look on
  its own ticket.
- `#174` — nothing alerts if the Friday retention run stops. That gap predates this work but
  matters more now that the in-app job is the only retention path.
