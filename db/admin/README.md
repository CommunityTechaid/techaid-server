# Admin-applied SQL (NOT Flyway-managed)

SQL in this directory is applied **manually, as `techaid_admin`**, to each environment in turn.
Flyway does **not** pick it up: `spring.flyway.locations` is `classpath:db/migration` only, and this
directory sits outside `src/`, so it never reaches the classpath.

## Why this directory exists

Everything in the `gdpr` schema — the `donors_archive` table, the `donors_to_archive` view, the
`archive_donor_info()` trigger function, `performgdprcleanup()` — is **owned by `techaid_admin`**.
Flyway runs as the application role (`api_uat` / `api_prod`), which cannot replace objects it does
not own: a migration touching them fails with `ERROR: must be owner of function ...`. The same
ownership trap broke a deploy on 2026-07-02 with `must be owner of table kits`.

So these statements cannot go in `src/main/resources/db/migration/`. That left them being applied
ad hoc and recorded nowhere — which is what this directory fixes.

## The incident that motivated it

On 2026-07-21, `gdpr.archive_donor_info()` was found to reference `gdpr.donors_to_delete`, a view
that **does not exist** in `techaid_uat` or `techaid_prod`. Both have `gdpr.donors_to_archive`
instead. The rename had been applied by hand and committed nowhere — `donors_to_archive` appears
in no commit, on any branch, in this repo or `ops-private`.

The consequences went unnoticed for a long time because they are invisible to CI:

- Every `DELETE FROM donors` failed, for **every** role including `techaid_admin`. The `deleteDonor`
  GraphQL mutation has been broken in UAT and production.
- Tests could never catch it: the zonky embedded-Postgres test database is built from the migrations
  in this repo, where `donors_to_delete` *is* created. So donor deletion passes locally and in CI,
  and fails in every real environment.

`gdpr.performgdprcleanup()` — the weekly retention job — had been updated to use `donors_to_archive`
and was unaffected. It anonymises via `UPDATE` rather than deleting, so GDPR retention stayed intact.

## Rules

1. **Record every admin-applied statement here before running it.** File name
   `YYYY-MM-DD__short_description.sql`.
2. Each file states, in a header comment: what it does, why it cannot be a Flyway migration, and
   which environments it has been applied to, with dates. Update that header when you apply it
   somewhere new.
3. **Never edit an already-applied Flyway migration** to reflect one of these changes. Flyway stores
   a checksum; editing it bricks startup. `V22.20.13.1517__gdpr.sql` still creates
   `donors_to_delete` and must stay that way.
4. This means the repo migrations and the live `gdpr` schema **do not match**, and cannot be made to
   match without transferring ownership of the `gdpr` objects to the application roles. Treat this
   directory as the record of that gap, not as a fix for it.

## Known drift between `V22.20.13.1517__gdpr.sql` and the live databases

| Object | Repo migration says | UAT / production actually have |
|---|---|---|
| retention view | `gdpr.donors_to_delete` | `gdpr.donors_to_archive` |
| `archive_donor_info()` | references `donors_to_delete`, `SECURITY INVOKER` | references `donors_to_archive`, `SECURITY DEFINER` (see `2026-07-21__fix_archive_donor_info.sql`) |

Anything writing new code against the `gdpr` schema must target the **live** names, not the
migration. A fresh database built only from the migrations will have `donors_to_delete` instead.
