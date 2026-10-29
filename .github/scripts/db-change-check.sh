#!/usr/bin/env bash
# Lists the Flyway migrations a promotion would apply to production, for promote.yml.
#
# Compares the commit production runs (api.communitytechaid.org.uk/actuator/info) with the commit
# being promoted: the commit UAT runs, or the dev-<sha> image tag asked for. Writes the result to
# the run summary, which shows on the run page while the deploy waits for approval.
#
# Exits non-zero, before the approval gate, when the promotion changes migrations (or they cannot
# be determined) and the operator did not tick confirm_db_changes.
#
# Inputs (env): IMAGE_TAG (promote.yml's image_tag), CONFIRMED ("true" when ticked).
# Run from a full-history checkout. Works locally too: output goes to stdout.
set -euo pipefail

MIGRATIONS=src/main/resources/db/migration
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/stdout}
OUTPUT=${GITHUB_OUTPUT:-/dev/null}
IMAGE_TAG=${IMAGE_TAG:-}
CONFIRMED=${CONFIRMED:-false}
# Statements that remove or rewrite existing data. A line-by-line heuristic, matched
# case-insensitively outside `--` comments: it prompts a closer read, it is not a parser.
DESTRUCTIVE='\b(drop[[:space:]]+(table|column|schema)|truncate|delete[[:space:]]+from|rename|update[[:space:]]+[a-z_.]+[[:space:]]+set|alter[[:space:]]+column[[:space:]]+[a-z_"]+[[:space:]]+(set[[:space:]]+data[[:space:]]+)?type)\b'

# git.commit from a deployed API's /actuator/info. Retries, because an idle Container App can take
# 40-90 s to cold-start. Prints nothing if it cannot be read.
deployed_commit() {
  local body
  for _ in 1 2 3; do
    if body=$(curl -sf -m 120 "https://$1/actuator/info"); then
      grep -o '"commit":"[0-9a-f]*"' <<<"$body" | cut -d'"' -f4
      return
    fi
    sleep 10
  done
}

# Full SHA for a commit-ish, or nothing if this checkout does not have it.
resolve() { git rev-parse --verify --quiet "$1^{commit}" 2>/dev/null || true; }

PROD=$(resolve "$(deployed_commit api.communitytechaid.org.uk)")
case "$IMAGE_TAG" in
  "") TARGET=$(resolve "$(deployed_commit api-testing.communitytechaid.org.uk)"); WHAT="the image UAT is running" ;;
  dev-*) TARGET=$(resolve "${IMAGE_TAG#dev-}"); WHAT="image tag \`$IMAGE_TAG\`" ;;
  # v<version> image tags are re-pushed by every dev build of that version, so they do not
  # identify a commit.
  *) TARGET=""; WHAT="image tag \`$IMAGE_TAG\`" ;;
esac
echo "target_sha=$TARGET" >> "$OUTPUT"

{
  echo "# Database changes in this promotion"
  echo
  echo "- Production runs: \`${PROD:-unknown}\`"
  echo "- Promoting: $WHAT, \`${TARGET:-unknown}\`"
  echo
} >> "$SUMMARY"

if [ -z "$PROD" ] || [ -z "$TARGET" ]; then
  NEEDS_CONFIRM=true
  {
    echo "## ⚠️ Could not determine the database changes"
    echo
    echo "One of the commits above could not be resolved, so this promotion **may** include"
    echo "migrations. Check \`$MIGRATIONS\` by hand before approving."
  } >> "$SUMMARY"
else
  CHANGES=$(git diff --name-status "$PROD" "$TARGET" -- "$MIGRATIONS")
  if [ -z "$CHANGES" ]; then
    NEEDS_CONFIRM=false
    echo "## ✅ No database migrations in this promotion" >> "$SUMMARY"
  else
    NEEDS_CONFIRM=true
    COUNT=$(wc -l <<<"$CHANGES" | tr -d ' ')
    {
      echo "## ⚠️ This promotion changes $COUNT database migration file(s)"
      echo
      if ! git merge-base --is-ancestor "$PROD" "$TARGET"; then
        echo "> **This is a rollback (or the commits have diverged).** Flyway never undoes a"
        echo "> migration: production keeps its current schema, and the older code must still"
        echo "> pass Hibernate \`validate\` against it."
        echo
      fi
      echo "| Change | File |"
      echo "|---|---|"
      while IFS=$'\t' read -r status file rest; do
        case "$status" in A) s="added" ;; M) s="**modified**" ;; D) s="**deleted**" ;; R*) s="**renamed**"; file="$file → $rest" ;; *) s="$status" ;; esac
        echo "| $s | \`$file\` |"
      done <<<"$CHANGES"
      echo
    } >> "$SUMMARY"

    # Destructive statements in the migrations production does not have yet.
    HITS=""
    while IFS=$'\t' read -r status file rest; do
      [ "${status:0:1}" = D ] && continue
      [ "${status:0:1}" = R ] && file=$rest
      found=$(git show "$TARGET:$file" | sed 's/--.*$//' | grep -inE "$DESTRUCTIVE" || true)
      [ -n "$found" ] && HITS+="$file"$'\n'"$found"$'\n\n'
    done <<<"$CHANGES"
    if [ -n "$HITS" ]; then
      {
        echo "### 🛑 Statements that remove or rewrite data"
        echo
        echo '```'
        printf '%s' "$HITS"
        echo '```'
        echo
      } >> "$SUMMARY"
    fi

    {
      echo "Before approving: read each migration, confirm it ran cleanly on UAT, and make sure a"
      echo "recent production backup exists. A modified or deleted migration that production has"
      echo "already applied will fail Flyway's checksum validation on startup."
    } >> "$SUMMARY"
  fi
fi

if [ "$NEEDS_CONFIRM" = true ] && [ "$CONFIRMED" != true ]; then
  {
    echo
    echo "---"
    echo "**Promotion stopped before the approval gate.** Review the above, then run the workflow"
    echo "again with **\"I have reviewed the database changes\"** ticked."
  } >> "$SUMMARY"
  echo "::error::This promotion changes database migrations (or they could not be determined). Re-run with confirm_db_changes ticked once reviewed."
  exit 1
fi
if [ "$NEEDS_CONFIRM" = true ]; then
  printf '\n---\nDatabase changes acknowledged by the operator (confirm_db_changes ticked).\n' >> "$SUMMARY"
fi
