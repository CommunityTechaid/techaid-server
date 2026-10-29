# TechAid Api

Provides backing services for the dashboard site at https://app.communitytechaid.org.uk/

## START HERE (new maintainer / new session)

**The operational knowledge for this project lives in `.claude/skills/` — 15 skills covering
build, deploy, database, debugging, config, and change control. Open
[`.claude/skills/README.md`](.claude/skills/README.md) first.** A Claude Code session loads them
automatically by description; a human should read them in the onboarding order that file lists.
New developers (server or dashboard): follow [`ONBOARDING.md`](ONBOARDING.md) for access, setup and first steps.

Quick reference (the skills have the full detail):

- **Run locally / tests:** see the sections below, and `techaid-build-and-env`. Tests are
  `./gradlew ktlintCheck test` — zonky embedded Postgres, **no Docker daemon needed**. A red
  `SchemaValidationTest` locally means a Flyway migration is missing.
- **Deploy to UAT:** automatic on push to `dev`. Verify:
  `curl -s https://api-testing.communitytechaid.org.uk/actuator/info` → `git.commit` matches your
  SHA and the latest revision is Healthy. Full detail: `techaid-deploy-and-operate`.
- **Deploy to production:** manual only — Actions → "Promote UAT → Production" (requires
  `production` environment approval). Rollback = same workflow with an `image_tag` input. Gated
  runbook: `techaid-prod-promotion-campaign`. **Never merge to `main` or create `v*` tags by
  hand** — release-please owns those.
- **"The site is down":** see [`SITE-IS-DOWN.md`](SITE-IS-DOWN.md) — first check is almost always
  a 40–90 s cold start, not an outage.
- **Environment:** maintained on **Windows via Git Bash** (PowerShell is blocked by antivirus on
  the maintainer's machine). Prefix `az` commands taking `/subscriptions/...` IDs with
  `MSYS_NO_PATHCONV=1`.

To run the API locally against a Docker Postgres, see [Local development](#local-development) below.

## Generating Gmail Credentials 
```bash
# Get the code for permission request 
https://accounts.google.com/o/oauth2/v2/auth \
 -d client_id=<client_id> \
 -d response_type=code \
 -d scope=https://www.googleapis.com/auth/gmail.modify \
 -d redirect_uri=http://localhost \
 -d access_type=offline
 
# Exchange code for access token and refresh token 
curl -X POST \
 -d code=<code> \
 -d client_id=<client_id> \
 -d client_secret=<client_secret> \
 -d grant_type=<grant_type> \
 -d redirect_uri=<redirect_uri> \
  https://www.googleapis.com/oauth2/v4/token
```

## Local development

Runs the API and Postgres in Docker on your machine, for use with the dashboard's `npx ng serve`
(see the dashboard README, "Option A"). All ports are bound to `127.0.0.1` only.

1. Copy the sample config and set a local admin secret:
   ```bash
   cp .env.sample .env
   # then set AUTH_ADMIN_SECRET in .env to a long random value, e.g. `openssl rand -hex 32`
   ```
   The app refuses to start without `AUTH_ADMIN_SECRET`. It grants full access via the
   `X-Auth-Admin-Secret` header, so never reuse the UAT/production value.
2. Start the containers (`-d` runs them in the background):
   ```bash
   docker compose up -d --build
   ```
3. Start the API inside the `web` container (it idles until you do):
   ```bash
   docker exec -it techaid-server-web-1 bash
   ./gradlew bootRun -PskipDownload=true
   ```
4. Check it: `curl -s localhost:8080/actuator/health` returns `UP`. The API is on
   http://localhost:8080.

> After editing any source files, press Ctrl+C and rerun `./gradlew bootRun -PskipDownload=true`
> to reload. If you change dependencies, rebuild the container (`docker compose up -d --build`).

Login uses the shared Auth0 tenant, so a token from the local dashboard is accepted as normal; what
you can do is governed by your Auth0 user's permissions. `AUTH0_CLIENT_ID`/`AUTH0_CLIENT_SECRET`
(Auth0 Management API) are deliberately left unset locally, so user-management features will not
work. Email (Gmail) and Typeform are off.

### Database

Flyway builds the schema on first start. With the `local` profile it also loads synthetic test
data from `src/main/resources/db/local/R__local_seed.sql` (a few donors, kits, referring
organisations and device requests). Add to that file if you need more; it re-runs whenever it
changes, so keep every insert idempotent.

**Never restore a production or UAT dump onto a laptop** — they contain personal data.

To start again from an empty database: `docker compose down -v` (deletes the `pgdata` volume).

#### Adminer
Adminer is a lightweight convenient tool to browse the database in your web-browser. It is available at `localhost:8900`
Select PostGreSQL in the Systems dropdown, type `postgres` in the server field, enter the database username and password (specified in the docker compose file) and type the name of the database in database field (optional)
 

### Notes
Ideally, you can attach VSCode to a running container using DevContainer options. But this is currently not working due to some bug in VSCode itself, I'm presuming. Feel free to give it a shot to see if it works. Or you could just use IntelliJ for a less painful experience. 