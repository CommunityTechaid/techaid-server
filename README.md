# TechAid Api

Provides backing services for the dashboard site at https://app.communitytechaid.org.uk/

## START HERE (new maintainer / new session)

**The operational knowledge for this project lives in `.claude/skills/` — 14 skills covering
build, deploy, database, debugging, config, and change control. Open
[`.claude/skills/README.md`](.claude/skills/README.md) first.** A Claude Code session loads them
automatically by description; a human should read them in the onboarding order that file lists.

Quick reference (the skills have the full detail):

- **Run locally / tests:** see the sections below, and `techaid-build-and-env`. Tests are
  `./gradlew ktlintCheck test` — zonky embedded Postgres, **no Docker daemon needed**. A red
  `SchemaValidationTest` locally means a Flyway migration is missing.
- **Deploy to UAT:** automatic on push to `dev`. Verify:
  `curl -s https://api-testing.communitytechaid.org.uk/actuator/info` → `git.commit` matches your
  SHA and the latest revision is Healthy. Full detail: `techaid-deploy-and-operate`.
- **Deploy to production:** manual only — Actions → "Promote UAT → Production" (requires
  `production` environment approval). Rollback = same workflow with an `image_tag` input. Gated
  runbook: `techaid-prod-promotion-campaign`. **Never merge to `master` or create `v*` tags by
  hand** — release-please owns those.
- **"The site is down":** see [`SITE-IS-DOWN.md`](SITE-IS-DOWN.md) — first check is almost always
  a 40–90 s cold start, not an outage.
- **Environment:** maintained on **Windows via Git Bash** (PowerShell is blocked by antivirus on
  the maintainer's machine). Prefix `az` commands taking `/subscriptions/...` IDs with
  `MSYS_NO_PATHCONV=1`.

```bash
# It's best to run with docker 
# To build the codebase and start the service locally on http://localhost:8080
# Copy the sample config file first 
cp .env.sample .env
# Start the app
./gradlew ktlintFormat build -x test && docker-compose up 
```

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

## Setting up for the dev environment

The `setup_branch` contains the docker files necessary for the dev environment. This might be moved into a submodule later. Do the following. 

- Copy the `.env.sample` file to a `.env` (create if one does not already exist)
- Run `docker compose up -d` to run the containers (`-d` option runs it in the background)
- Execute the following command to obtain a bash shell of the container running the server api:
    `docker exec -it techaid-server-web-1 bash`
- Once in the container, execute the following command to run the application:
    `./gradlew bootRun -PskipDownload=true`
- The server should now be running and accessible at [http://localhost:8080](http://localhost:8080). 

You can now use PostMan or other similar clients to test the api. Or you can follow the instructions in the dashboard repo for running the frontend application on the local machine.


> After editing any source files, press Crtl+C and rerun the last command (`./gradlew bootRun -PskipDownload=true`) to reload. If you update libraries or change dependencies you might have to restart the container entirely (include `--build` option while running `docker compose up`)


### Further steps
#### Database
- You might need to do a pg_restore to populate the database with dummy data. You might also need to execute an SQL update to update some statuses in the kits table (this would depend on the data you are restoring).
pg_restore is preinstalled in the postgres container. 
 
You can copy the sql dump to the home directory of the postgres container and run the pg_restore using the following commands:


``` bash
#copy dump file to the home directory of the postgres container 
docker cp dump_file.sql.tar techaid-server-postgres-1:/home/
#execute the pg_restore 
docker exec --workdir /home techaid-server-postgres-1 bash -c 'pg_restore -d $POSTGRES_DB dump_file.sql.tar -U $POSTGRES_USER'
```
#### Adminer
Adminer is a lightweight convenient tool to browse the database in your web-browser. It is available at `localhost:8900`
Select PostGreSQL in the Systems dropdown, type `postgres` in the server field, enter the database username and password (specified in the docker compose file) and type the name of the database in database field (optional)
 

### Notes
Ideally, you can attach VSCode to a running container using DevContainer options. But this is currently not working due to some bug in VSCode itself, I'm presuming. Feel free to give it a shot to see if it works. Or you could just use IntelliJ for a less painful experience. 