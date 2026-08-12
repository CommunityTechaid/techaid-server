#!/usr/bin/env bash
# Shared helpers for the GDPR production cutover scripts. Not run directly.
#
# Run everything from Git Bash. psql is not installed on the maintainer's machine, so all
# SQL goes through a throwaway postgres:17-alpine container (see reference: the working
# recipe has been stable since 2026-07-21).

set -euo pipefail

PGHOST_="techaid-pg-svr.postgres.database.azure.com"
PGUSER_="techaid_admin"
RG="tada-2026"
APP="api-production"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$REPO_ROOT/scripts/gdpr-cutover/logs"
mkdir -p "$LOG_DIR"

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m   OK  %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m   !!  %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mABORT: %s\033[0m\n\n' "$*" >&2; exit 1; }

# The password is never stored in this repo. Set TECHAID_ADMIN_PW in your shell to skip the
# prompt, e.g.  export TECHAID_ADMIN_PW='...'   (it will not appear in your bash history if
# you prefix the line with a space).
get_pw() {
    if [[ -n "${TECHAID_ADMIN_PW:-}" ]]; then
        return
    fi
    printf 'techaid_admin password: ' >&2
    read -rs TECHAID_ADMIN_PW
    printf '\n' >&2
    export TECHAID_ADMIN_PW
    [[ -n "$TECHAID_ADMIN_PW" ]] || die "no password given"
}

ensure_docker() {
    if docker info >/dev/null 2>&1; then
        ok "docker is running"
        return
    fi
    say "starting Docker Desktop (it is usually stopped on this machine)"
    "/c/Program Files/Docker/Docker/Docker Desktop.exe" >/dev/null 2>&1 &
    for _ in $(seq 1 60); do
        sleep 3
        if docker info >/dev/null 2>&1; then ok "docker is up"; return; fi
    done
    die "Docker did not start. Start Docker Desktop by hand and re-run."
}

# run_sql_file <database> <path-to-sql> <log-name>
# Streams output to the terminal AND to scripts/gdpr-cutover/logs/<log-name>-<stamp>.log
run_sql_file() {
    local db="$1" file="$2" name="$3"
    [[ -f "$file" ]] || die "SQL file not found: $file"
    local stamp log
    stamp="$(date -u +%Y%m%d-%H%M%S)"
    log="$LOG_DIR/${name}-${stamp}.log"
    local dir base
    dir="$(cd "$(dirname "$file")" && pwd)"
    base="$(basename "$file")"

    MSYS_NO_PATHCONV=1 docker run --rm \
        -e PGPASSWORD="$TECHAID_ADMIN_PW" \
        -v "$(cygpath -w "$dir")":/sql \
        postgres:17-alpine \
        psql "host=$PGHOST_ dbname=$db user=$PGUSER_ sslmode=require" \
             -v ON_ERROR_STOP=1 -f "/sql/$base" 2>&1 | tee "$log"

    local rc=${PIPESTATUS[0]}
    printf '\n   log: %s\n' "$log"
    return "$rc"
}

# run_sql <database> <sql-string>   — for one-off queries, output to terminal only
run_sql() {
    local db="$1" sql="$2"
    MSYS_NO_PATHCONV=1 docker run --rm \
        -e PGPASSWORD="$TECHAID_ADMIN_PW" \
        postgres:17-alpine \
        psql "host=$PGHOST_ dbname=$db user=$PGUSER_ sslmode=require" \
             -v ON_ERROR_STOP=1 -c "$sql"
}

# run_sql_quiet <database> <sql-string>  — single scalar, no formatting
run_sql_quiet() {
    local db="$1" sql="$2"
    MSYS_NO_PATHCONV=1 docker run --rm \
        -e PGPASSWORD="$TECHAID_ADMIN_PW" \
        postgres:17-alpine \
        psql "host=$PGHOST_ dbname=$db user=$PGUSER_ sslmode=require" \
             -tA -v ON_ERROR_STOP=1 -c "$sql" 2>/dev/null | tr -d '[:space:]'
}

# confirm: aborts the whole script unless the answer is exactly "yes".
confirm() {
    local prompt="$1" reply
    printf '\n\033[1;33m%s\033[0m ' "$prompt"
    read -r reply
    [[ "$reply" == "yes" ]] || die "not confirmed (you must type exactly: yes)"
}

# ask: returns 1 instead of aborting, for optional steps you may legitimately skip.
ask() {
    local prompt="$1" reply
    printf '\n\033[1;33m%s\033[0m ' "$prompt"
    read -r reply
    [[ "$reply" == "yes" ]]
}

# Guard: production must be running the build that carries the migrations these scripts assume.
assert_prod_has_migrations() {
    say "checking production has the required migrations"
    local n
    n="$(run_sql_quiet techaid_prod \
        "SELECT count(*) FROM flyway_schema_history WHERE version IN ('26.08.12.1000','26.08.12.1100') AND success")"
    if [[ "$n" != "2" ]]; then
        die "production is missing V26.08.12.1000/1100 (found $n of 2).
       Promote dev to production FIRST - these scripts assume the deploy has landed.
       Check with: curl -s https://api.communitytechaid.org.uk/actuator/info"
    fi
    ok "both migrations present and successful"
}
