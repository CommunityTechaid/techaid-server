# Flyway migrations

Everything here is applied by Flyway as the application role (`api_uat` / `api_prod` in the
deployed environments) from `spring.flyway.locations = classpath:db/migration`.

SQL that must run as `techaid_admin` does **not** belong here — see [`db/admin/README.md`](../../../../../db/admin/README.md).

## An applied migration is immutable

Flyway records a checksum of each file when it applies it, and re-validates on every boot.
Editing a migration that has already run in any environment makes that environment fail
startup with `Migration checksum mismatch`. This includes edits that change nothing
functional: **a comment, a typo fix, and a whitespace change all break it.**

So a mistake in a shipped migration cannot be corrected in place. It can only be superseded
by a new migration, or cleared by a deliberate `flyway repair` against every affected
database. Prefer superseding.

The practical consequence: a stray note-to-self in a migration is not a tidy-up job later,
it is a permanent fixture that costs a whole migration to remove. Say what you mean before
it merges.

## Known misleading text in shipped migrations

These files cannot be corrected. Read this table before trusting their comments.

### `V26.08.12.1100__gdpr_retention_policy_corrections.sql`

| What it says | The truth |
|---|---|
| Two copies of `-- TEMP-REVERT-FOR-TEST-D: drop the OR branch to prove B3 discriminates.` inside the body of `gdpr.performgdprcleanup()` | **Do not do this.** They are leftover diagnostic scaffolding. The `OR` branch they sit above is what closes the audit-trail gap (#127): without it, an audit row behind a request edited after being scrubbed becomes permanently unreachable. 1,069 `details` + 471 `client_ref` rows were in that state in production. `V26.08.12.1200` supersedes the body with the clean version, and `GdprCleanupFunctionBodyTest` fails if a `TEMP-REVERT` marker ever returns. |
| Closing `RAISE NOTICE` reads `notes at 26 weeks` | Wrong, and it contradicts the same file's note 0. `device_requests_notes.content` is retained for **52 weeks**, per the team's retention spreadsheet (`GDPR data removal review 26-08-11.xlsx`, sheet "Requests", row 5: 12 months). The 26-week figure in #98, #96, #126 and PR #130 predates the spreadsheet and was never reconciled to it. Applying 26 weeks would destroy a further 1,033 note bodies against written policy. `GdprSchemaConvergenceTest` pins 52 weeks. |

Neither ever reached UAT or production: `V26.08.12.1100` self-gates on `has_schema_privilege(current_user, 'gdpr', 'CREATE')`, which is false for the app role there, so those environments took their function from `db/admin/2026-08-13__admin_apply_gdpr_retention_scope_prod.sql` (and the UAT counterpart) instead — both clean. The stale text only ever affected fresh and test databases.

## Self-gated migrations

Anything touching the `gdpr` schema must no-op where it cannot run, rather than fail:

```sql
IF to_regnamespace('gdpr') IS NULL THEN RETURN; END IF;
IF NOT has_schema_privilege(current_user, 'gdpr', 'CREATE') THEN RETURN; END IF;
```

That is why `gdpr`-schema changes always come in pairs — a self-gating migration here for
fresh/test databases, and an admin-applied counterpart under `db/admin/` for UAT and
production. Keep the two in step: they drifted once, and only a line-by-line diff of the
migration body against the live `prosrc` caught it.
