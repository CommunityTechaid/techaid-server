---
name: techaid-database-operations
description: >-
  Load this skill for ANY database work on techaid-server: writing or debugging Flyway
  migrations, "Migration checksum mismatch" / "must be owner of table" / Hibernate
  "Schema-validation: missing table/column" errors, SchemaValidationTest or
  IndexMigrationTest failures, changing DDL_AUTO / ddl-auto, connecting to the Azure
  Postgres server (techaid-pg-svr) or the local docker-compose Postgres, pg_dump /
  pg_restore / backups / point-in-time restore, table ownership or permissions problems,
  Envers audit (_audit_trail) tables, the GDPR pg_cron cleanup job, or restoring dummy
  data locally. Owns everything DDL/DML and Postgres-operational.
---

# TechAid Database Operations

Everything database for `techaid-server`: Flyway migration discipline, the Azure
PostgreSQL topology, safe access patterns, backups/restore, and the schema's audit
and GDPR machinery.

**When NOT to use this skill:**
- Deploying/restarting the app, Container Apps revisions, KEDA scaling → **techaid-deploy-and-operate**
- Promoting dev → master / production releases → **techaid-prod-promotion-campaign**
- Non-DB build/test environment problems → **techaid-build-and-env**
- App-level config that isn't `DDL_AUTO`/datasource → **techaid-config-and-flags**
- Querying logs/metrics (App Insights, Log Analytics) → **techaid-diagnostics-and-observability**
- What the entities *mean* (kits, device requests, donors) → **techaid-domain-reference**

## Glossary (defined once)

| Term | Meaning |
|------|---------|
| **Flyway** | Migration tool. Runs versioned SQL files from `src/main/resources/db/migration` automatically at app startup; records each applied file (name + checksum) in the `flyway_schema_history` table of the target DB. |
| **Checksum** | Hash Flyway stores for each applied migration. If the file changes afterwards, startup fails with "Migration checksum mismatch". Hence: never edit an applied migration. |
| **ddl-auto** | Hibernate schema handling mode. `validate` = compare entity mappings to the real schema and fail on mismatch (no changes); `update` = mutate schema to match entities (dangerous, historical source of the "unmanaged schema" problem); `none` = do nothing. |
| **PITR** | Point-In-Time Restore. Azure Flexible Server can restore the server to any moment inside its backup retention window — but only while the server exists. |
| **Envers** | Hibernate auditing. Every change to an `@Audited` entity writes a row to a companion audit table, linked to a revision row in `custom_rev_info`. |
| **Private endpoint / privatelink** | A NIC inside the VNet giving the Postgres server a private IP; DNS zone `privatelink.postgres.database.azure.com` resolves the server name to it. Production apps reach the DB only this way. |
| **Zonky** | Test library (`io.zonky.test:embedded-postgres`) that runs a real Postgres from downloaded binaries — no Docker — so every `./gradlew test` run exercises the actual migrations. |

## Non-negotiables

1. **The production database (`techaid_prod`) is sacred.** No direct DML/DDL against it
   without explicit user instruction — route the decision through **techaid-change-control**.
   Before any risky prod operation, confirm a PITR-safe restore path (or take a fresh
   `pg_dump`). Rationale: Burstable-tier Azure Postgres keeps **no backups of a deleted
   server**, and the May-2026 restore showed manual surgery leaves landmines — it left
   every table owned by `techaid_admin`, which crashed a Flyway migration weeks later
   ("must be owner of table kits", 2026-07-02). See **techaid-failure-archaeology**.
2. **Never edit a migration that has been applied anywhere** (UAT, prod, a teammate's
   machine). Checksum mismatch bricks startup. Fix forward with a new migration.
3. **Never flip `DDL_AUTO` to `update` to "fix" a schema-validation failure.** A
   validation failure means a migration is missing — write it. `update` silently mutates
   schemas and created the years-long "unmanaged schema" mess that V26.07.03.0900 had to
   baseline. (`SchemaValidationTest`'s own javadoc says the same.)
4. **Guard all DDL** (see house style below) so migrations are no-ops where objects
   already exist. UAT/prod schemas were partly Hibernate-created; unguarded DDL will
   collide.

## Topology: servers, databases, roles

Server hostname, role and database names were taken from the maintainer's local
migration scripts (gitignored, not in the repo — absent from fresh clones) and are
embedded here as of 2026-07-03; sizes/retention are operational facts — verify
before relying (command in Provenance).

**Server:** `techaid-pg-svr` (Azure Database for PostgreSQL Flexible Server, resource
group `tada-2026`). As of 2026-07-03: PostgreSQL 17, Standard_B1ms (Burstable, 1 vCPU /
2 GiB), 32 GiB storage, 35-day backup retention. Admin login `techaid_admin`; the
password lives in **Bitwarden** — never in the repo or a skill.

| Database | App role (owner of objects) | Consumer |
|----------|------------------------------|----------|
| `techaid_prod` | `api_prod` | `api-production` Container App |
| `techaid_uat`  | `api_uat`  | `api-testing` Container App (UAT) |
| `superset_prod` | `superset_prod` | `superset-production` |
| `superset_uat`  | `superset_uat`  | `superset-testing` |

**Access paths:**
- Production/UAT apps connect via the VNet **private endpoint**:
  `techaid-pg-svr.privatelink.postgres.database.azure.com` (VNet `TaDaApplicationNetwork`,
  RG `TechAidDatabaseApp`).
- Public network access is enabled with a small named firewall allow-list (named
  office/admin rules; check current entries with the `az` command in Provenance — do not
  assume your IP is on it; rules go stale as IPs change).

**Ownership invariant** (operational history as of 2026-07-03, not repo-verifiable):
every table and sequence in `public` is owned by `api_uat` on `techaid_uat` and by
`api_prod` on `techaid_prod`. If a Flyway migration fails with
`ERROR: must be owner of table <x>`, some restore/import broke this invariant — that is
the diagnosis. The fix is one-off `ALTER TABLE ... OWNER TO ...` SQL run as
`techaid_admin`, which **requires explicit user sign-off** (non-negotiable 1).

## Flyway discipline

**How migrations run:** automatically at application startup — there is no manual Flyway
CLI step anywhere (no Flyway Gradle task; `application.yml` configures
`spring.flyway.locations: classpath:db/migration`). Deploying a new image to a Container
App is what applies new migrations to that environment's DB. Consequences:

- A broken migration = a crash-looping app. On Container Apps the failing revision wedges
  and traffic silently stays on the previous healthy revision (see
  **techaid-debugging-playbook** / **techaid-deploy-and-operate**).
- Migration errors surface **first** in `./gradlew test`, because the zonky embedded
  Postgres applies every migration to a fresh DB on each test run. Run tests before
  pushing; a red `SchemaValidationTest` locally is the cheap version of a wedged UAT deploy.

**Config that matters** (`src/main/resources/application.yml`):

```yaml
spring:
  flyway:
    out-of-order: true          # allows applying a migration whose version sorts
                                # BEFORE already-applied ones — needed because parallel
                                # branches merge in non-chronological order
    locations:
      - classpath:db/migration
```

`out-of-order: true` means version numbers are naming hygiene, not an execution
guarantee. Do not write migration B assuming migration A (higher version, merged
earlier) already ran — each migration must stand alone or check its own preconditions.

Caveat: `application-local.yml` also lists `classpath:db/local`, but no `db/local`
directory exists in the repo (as of 2026-07-03) — it's a dormant hook, not a real
location.

**Naming convention:** `VYY.MM.DD.HHMM__snake_case_description.sql`, e.g.
`V26.07.03.0900__baseline_unmanaged_schema.sql`. (Historical anomaly: `V22.20.13.1517__gdpr.sql`
has an impossible month "20" — leave it alone; renaming an applied migration breaks
Flyway's history. It's a cautionary tale, not a pattern.)

## Migration inventory (as of 2026-07-03 — 19 files)

All in `src/main/resources/db/migration/`. Re-verify with the `ls` in Provenance.

| Migration | Purpose |
|-----------|---------|
| `V20.03.09.1500__init.sql` | Original schema: donors, kits, volunteers + sequences. |
| `V20.04.19.1430__update.sql` | Rename ward→post_code, add coordinates (jsonb), kit/volunteer links. |
| `V20.04.21.2245__add_blog.sql` | FAQs and blog posts tables. |
| `V20.04.30.1100__update_enums.sql` | Convert kit status/type from ints to varchar enum values. |
| `V20.05.01.1600__kit_users.sql` | `kit_volunteers` join table; kits.archived flag. |
| `V20.05.03.1011__add_organisations.sql` | Legacy `organisations` + `email_templates` tables. |
| `V20.05.04.1040__add_org_contact.sql` | organisations.contact; posts.secured. |
| `V20.05.29.1135__update_orgs.sql` | organisations volunteer link + archived. |
| `V20.08.02.1740__update_boolean.sql` | archived boolean → 'Y'/'N' varchar(1) on kits/organisations. |
| `V21.01.10.2013__update_kit_image.sql` | `kit_images` (jsonb) table. |
| `V21.01.31.1042__donor_consent.sql` | donors.consent. |
| `V21.04.27.1022__org_address.sql` | organisations.address. |
| `V22.20.13.1517__gdpr.sql` | GDPR machinery: `gdpr` schema, retention views, archive triggers, FK on-delete rules (see GDPR section). Malformed version string — do not imitate. |
| `V25.11.17.1148__add_lot_id_to_kits.sql` | kits.lot_id. |
| `V25.12.04.1200__add_location_code_to_kits.sql` | kits.location_code. |
| `V25.12.04.1201__add_status_updated_at_to_kits.sql` | kits.status_updated_at. |
| `V26.04.29.1000__fix_device_request_is_sales_not_null.sql` | Backfill + NOT NULL/default on device_requests.is_sales (table-existence-guarded). |
| `V26.07.02.1000__add_indexes.sql` | All secondary/FK indexes, column-existence-guarded (see house style). |
| `V26.07.03.0900__baseline_unmanaged_schema.sql` | **The baseline**: every table/sequence Hibernate `update` had created over the years, verbatim from the Hibernate 6 schema export with `if not exists` guards; plus guarded column adds, the kits.archived varchar→char(1) alter, guarded FKs, and a re-run of the index block. Makes Flyway the single owner of the schema so `ddl-auto=validate` works everywhere. |

## ddl-auto matrix

Source of truth: `application.yml` (`hibernate.ddl-auto: ${DDL_AUTO:${ddl-auto:validate}}`),
`application-production.yml` (`ddl-auto: none`), `docker-compose.yml` (`DDL_AUTO: update`).

| Context | Effective mode | Why |
|---------|---------------|-----|
| Default / UAT | `validate` | Fail fast if a migration is missing. |
| `production` profile | `none` | Prod never validates at boot (avoids boot-failure risk on a mapping quirk); correctness is guaranteed upstream by UAT running `validate` + `SchemaValidationTest`. |
| Local docker-compose | `update` (env `DDL_AUTO: update`) | Convenience on a throwaway local DB. Do not copy this to any shared environment. |
| Tests | `validate` (forced in `SchemaValidationTest`) | The gate. |

## Guarded-SQL house style

Why: most tables historically had **no** CREATE TABLE migration (Hibernate
`ddl-auto=update` created them at boot for years). V26.07.03.0900 baselined all of that,
but UAT/prod already have the objects — so every statement must be a no-op where its
object already exists, and column-dependent DDL must check the column exists. Patterns
to copy (both from real migrations in this repo):

**1. `if not exists` on everything creatable:**

```sql
create sequence if not exists referring_organisation_sequence start with 1 increment by 1;
create table if not exists note (created_at timestamp(6) with time zone, id bigint not null, ... primary key (id));
alter table kits add column if not exists serial_no varchar(255);
```

**2. Column-existence-guarded `DO` block** (from `V26.07.02.1000__add_indexes.sql` —
creates each index only if its table+column exist, `IF NOT EXISTS` makes re-runs safe):

```sql
DO $$
DECLARE
    idx record;
BEGIN
    FOR idx IN
        SELECT * FROM (VALUES
            ('kits', 'donor_id', 'ix_kits_donor_id')
            -- , more (table, column, index) triples ...
        ) AS t(table_name, column_name, index_name)
    LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.columns c
            WHERE c.table_schema = current_schema()
              AND c.table_name = idx.table_name
              AND c.column_name = idx.column_name
        ) THEN
            EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (%I)', idx.index_name, idx.table_name, idx.column_name);
        END IF;
    END LOOP;
END $$;
```

**3. Guarded constraint add** (from the baseline — `pg_constraint` lookup because
`ADD CONSTRAINT` has no `IF NOT EXISTS`):

```sql
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = fk.constraint_name) THEN
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I',
                   fk.table_name, fk.constraint_name, fk.column_name, fk.ref_table);
END IF;
```

**4. Table-existence guard for DML** (from `V26.04.29.1000...`):

```sql
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'device_requests') THEN
        UPDATE device_requests SET is_sales = false WHERE is_sales IS NULL;
        ALTER TABLE device_requests ALTER COLUMN is_sales SET NOT NULL;
    END IF;
END $$;
```

Also: when adding a NOT NULL column to a table that has rows, supply a `default`
(the baseline does: `add column if not exists archived char(1) not null default 'N'`).

## How to add a migration (checklist)

1. **Classify the change** via **techaid-change-control** (schema changes are
   behavior-changing; anything touching prod data needs explicit sign-off).
2. Create `src/main/resources/db/migration/V<YY.MM.DD.HHMM>__<description>.sql`
   using today's date/time. Never reuse or edit an existing version.
3. Write **guarded** SQL per the house style above. Assume it may run on: a fresh empty
   DB (zonky tests), UAT (objects exist), prod (objects exist, `ddl-auto=none`).
4. If the change alters an `@Audited` entity's table, mirror it on the audit table
   (see Envers section) — Hibernate `validate` checks audit tables too.
5. **Red/green test first** (house rule — see **techaid-validation-and-qa**): follow
   `IndexMigrationTest`'s pattern if the migration's effect needs a production-shaped
   fixture, or rely on `SchemaValidationTest` going red before the entity/migration pair
   is complete.
6. Run the gates locally:
   ```bash
   ./gradlew ktlintCheck test
   ```
   Zonky spins up a real embedded Postgres (no Docker; `zonky.test.database.provider:
   zonky` in `src/test/resources/application.yml`) and applies all 19+ migrations fresh.
   `SchemaValidationTest` then boots Hibernate in `validate` mode against the
   Flyway-only schema — if your entity needs something no migration provides, it fails,
   and the fix is a migration, not ddl-auto.
7. PR to `dev` (never master — **techaid-change-control**). The push to `dev`
   auto-deploys to UAT, where the migration actually applies to `techaid_uat`. Verify
   the deploy log shows `Successfully applied N migration(s)` and the revision is
   healthy before considering it done.
8. Prod application happens only via the promotion flow (**techaid-prod-promotion-campaign**).

## Connecting to the databases

All CLI work from a Windows dev machine: use Git Bash, not PowerShell (house rule; see
**techaid-build-and-env**). Prefix `az` commands that take `/subscriptions/...` IDs with
`MSYS_NO_PATHCONV=1`.

**Option A — public endpoint (read-mostly admin work).** Works only from an IP on the
server firewall allow-list. Credentials: `techaid_admin` from Bitwarden, or the app role
password from the Container App's secret:

```bash
# App-role password — self-service read-only retrieval (no rotation involved; telling
# the maintainer you're doing prod-adjacent work is still good practice). As of 2026-07-03:
az containerapp secret show -g tada-2026 -n api-production \
  --secret-name <db-password-secret-name> --query value -o tsv
psql "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_uat user=api_uat sslmode=require"
```

**Option B — ACI jump pod (VNet path; needed when the firewall doesn't cover you, or for
private-endpoint-only work).** Spins a throwaway `postgres:17-alpine` container inside
the VNet. **Creating Azure resources requires user sign-off** (techaid-change-control).
Operational pattern as of 2026-07-03 — verify subnet still exists before use:

```bash
az container create --resource-group TechAidDatabaseApp --name pg-admin-jump \
  --image postgres:17-alpine --os-type Linux --cpu 1 --memory 1 \
  --vnet TaDaApplicationNetwork --subnet aci-admin --restart-policy Never \
  --secure-environment-variables "PGPASSWORD=<from Bitwarden>" \
  --command-line "/bin/sh -c 'sleep 3600'"
az container exec -g TechAidDatabaseApp -n pg-admin-jump --exec-command /bin/sh
# inside: psql -h techaid-pg-svr.privatelink.postgres.database.azure.com -U techaid_admin -d techaid_uat
# ALWAYS delete afterwards:
az container delete --resource-group TechAidDatabaseApp --name pg-admin-jump --yes
```

**Option C — one-shot logical backup to blob storage.** The pattern used for the legacy
archive dumps (`tadaarchive2026` storage account, as of 2026-05; operational history):
an ephemeral ACI in the VNet runs `pg_dump -Fc`, then uploads to a container using a
short-lived **user-delegation SAS** (a signed URL minted from your Azure AD identity via
`az storage container generate-sas --auth-mode login --as-user`). No credentials persist
anywhere. Use this before any risky prod operation when PITR alone isn't enough
(e.g. before dropping objects, since PITR restores a whole server, not one table).

## Backups and PITR

- `techaid-pg-svr` retention: **35 days** (as of 2026-07-03 — verify, Provenance).
  PITR can rebuild the server state at any point in that window, into a *new* server.
- **Deleting the server destroys its backups.** Burstable tier has no
  dropped-server retention. Before deleting/recreating any Postgres server: take a
  logical dump (Option C) or confirm the data is archived. This is a hard rule.
- pg_dump does **not** capture pg_cron jobs (they live in the `postgres` maintenance DB)
  — after any restore, re-schedule the GDPR job (next section) and re-check extensions
  (`fuzzystrmatch`, `pg_cron`). (Recipe from the maintainer's local, gitignored
  migration script — essentials embedded in the GDPR section below.)
- After any restore, re-verify the **ownership invariant** before the next deploy
  (`\dt` + `\ds` owners must be the app role) — this is exactly the landmine that fired
  on 2026-07-02.

## Envers audit tables

Configured in `application.yml` (`audit_table_suffix: _AUD`, revision field
`revision_id`… — but note most entities override the table name):

- Audited entities carry `@Audited` + `@AuditTable("<name>_audit_trail")`:
  `kit_audit_trail`, `device_requests_audit_trail`, `donors_audit_trail`,
  `donorParents_audit_trail` (note the camelCase — Postgres folds it to
  `donorparents_audit_trail`; the baseline creates `donor_parents_audit_trail`
  per the Hibernate export — trust the baseline/actual DB, not the annotation string),
  `referring_organisations_audit_trail`, `referring_organisation_contacts_audit_trail`.
- `Note` has no `@AuditTable`, so it uses the default suffix → `note_aud`.
- Revisions live in `custom_rev_info` (custom `@RevisionEntity` with a `custom_user`
  column recording who made the change); every audit table's `rev` column FKs to it.
- **Migration consequence:** adding/renaming a column on an audited table requires the
  same change on its audit twin, or `ddl-auto=validate` (UAT, tests) fails at boot.
- These tables grow forever by design (that's the audit trail). Don't "clean them up"
  without change-control sign-off.

## GDPR machinery (in-schema, easy to miss)

`V22.20.13.1517__gdpr.sql` created a `gdpr` schema: retention views
(e.g. `gdpr.donors_to_delete` — donors 12 months after their last kit donation,
businesses/droppoints excluded), a `BEFORE DELETE` trigger archiving donor info, and
FK `ON DELETE` rules so deletes cascade cleanly. The actual cleanup runs as a
**pg_cron job** `gdpr-weekly-cleanup`, schedule `4 4 * * 6` (Saturdays 04:04 UTC),
executing `SELECT gdpr.PerformGDPRCleanup()` in `techaid_prod` — scheduled from the
`postgres` database via `cron.schedule_in_database(...)` (embedded from the
maintainer's local, gitignored migration script; as of 2026-07-03).
This job is **server-side state, invisible to the app and to pg_dump** — check it exists
after any restore:

```sql
-- as techaid_admin, database "postgres":
SELECT jobid, schedule, command, database, active FROM cron.job;
```

## Local development database

From `docker-compose.yml` and `README.md`:

- `docker compose up -d` starts Postgres 16 (`techaid_api` / `postgres` / `password`,
  host port **5423**) and Adminer at **http://localhost:8900** (server field: `postgres`).
- Restore dummy data into the container:
  ```bash
  docker cp dump_file.sql.tar techaid-server-postgres-1:/home/
  docker exec --workdir /home techaid-server-postgres-1 bash -c 'pg_restore -d $POSTGRES_DB dump_file.sql.tar -U $POSTGRES_USER'
  ```
- The compose file sets `DDL_AUTO: update` for the web container — acceptable only here.
- Tests never touch this DB: zonky provisions its own embedded Postgres per run.

## Provenance and maintenance

Authored 2026-07-03 from the repo at commit `76b092f` plus date-stamped operational
facts (marked inline). Re-verify volatile facts:

```bash
ls src/main/resources/db/migration/                                  # migration inventory (19 files as of 2026-07-03)
grep -n "ddl-auto\|out-of-order" src/main/resources/application*.yml # ddl-auto default=validate, prod=none; out-of-order true
grep -n "DDL_AUTO" docker-compose.yml                                # local dev still 'update'
grep -n "zonky\|flyway" build.gradle                                 # flyway 10.22.0; zonky 2.5.1/2.1.0
ls src/test/kotlin/cta/db/                                           # SchemaValidationTest, IndexMigrationTest still exist
az postgres flexible-server show -g tada-2026 -n techaid-pg-svr --query "{ver:version,sku:sku.name,storage:storage.storageSizeGb,retention:backup.backupRetentionDays}"
az postgres flexible-server firewall-rule list -g tada-2026 -n techaid-pg-svr -o table   # current public allow-list
# GDPR pg_cron job still scheduled — live check, as techaid_admin on database "postgres":
#   SELECT jobname, schedule, database FROM cron.job;
```
