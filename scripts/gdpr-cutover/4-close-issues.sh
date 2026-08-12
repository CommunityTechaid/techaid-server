#!/usr/bin/env bash
#
# STEP 4 of 4 - Measure production, then close only the GitHub issues whose own condition holds.
#
# Every issue here has a DIFFERENT closing condition, checked separately. An issue is closed
# only if its specific gate passes, and the comment it is closed with carries the measured
# numbers that justified it. Nothing closes on "the deploy ran".
#
# Usage:
#   ./4-close-issues.sh --dry-run     measure and report; close nothing (default if unsure)
#   ./4-close-issues.sh               measure, report, then ask before closing the passers
#
# Safe to run repeatedly. Already-closed issues are skipped.

source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

command -v gh >/dev/null || die "gh CLI not found"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated - run: gh auth login"

say "STEP 4 - measure production, close what genuinely passes"
$DRY_RUN && warn "DRY RUN - nothing will be closed"

ensure_docker
get_pw

# --------------------------------------------------------------------------------------
# Measure once. Everything below is derived from these.
# --------------------------------------------------------------------------------------
say "measuring techaid_prod"

q() { run_sql_quiet techaid_prod "$1"; }

FLAG=$(q          "SELECT enabled FROM feature_flags WHERE flag_key = 'gdpr-in-app-cleanup'")
RUNS=$(q          "SELECT count(*) FROM gdpr_cleanup_runs")
LAST_RUN=$(q      "SELECT coalesce(max(ran_at)::text, 'never') FROM gdpr_cleanup_runs")
VIEW_DEF=$(q      "SELECT replace(definition, chr(10), ' ') FROM pg_views WHERE schemaname='gdpr' AND viewname='donors_to_archive'")
FN_SRC_LEN=$(q    "SELECT length(prosrc) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='gdpr' AND p.proname='performgdprcleanup'")

DONORS=$(q        "SELECT count(*) FROM gdpr.donors_to_archive")
PARENTLESS=$(q    "SELECT count(*) FROM donors d WHERE d.donor_parent_id IS NULL AND d.name <> 'Donor - Erased due to GDPR policy' AND coalesce((SELECT max(k.created_at) FROM kits k WHERE k.donor_id = d.id), d.created_at) <= (current_date - interval '12 months')")
LEADS=$(q         "SELECT count(*) FROM donors d WHERE d.is_lead_contact AND d.name <> 'Donor - Erased due to GDPR policy' AND coalesce((SELECT max(k.created_at) FROM kits k WHERE k.donor_id = d.id), d.created_at) <= (current_date - interval '12 months')")

DR_DETAILS=$(q    "SELECT count(*) FROM device_requests WHERE updated_at <= (current_date - interval '26 weeks') AND details <> 'RECORD DELETED BY SYSTEM - GDPR'")
DR_CLIENTREF=$(q  "SELECT count(*) FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND client_ref <> 'WIPED - GDPR'")
DR_CONTACT=$(q    "SELECT count(*) FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND collection_contact_name IS NOT NULL AND collection_contact_name <> 'WIPED - GDPR'")

AUD_DETAILS=$(q   "SELECT count(*) FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id=d.id WHERE (d.updated_at <= (current_date - interval '26 weeks') OR d.details = 'RECORD DELETED BY SYSTEM - GDPR') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR'")
AUD_CLIENTREF=$(q "SELECT count(*) FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id=d.id WHERE (d.updated_at <= (current_date - interval '52 weeks') OR d.client_ref = 'WIPED - GDPR') AND dat.client_ref <> 'WIPED - GDPR'")
AUD_CONTACT=$(q   "SELECT count(*) FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id=d.id WHERE (d.updated_at <= (current_date - interval '52 weeks') OR d.collection_contact_name = 'WIPED - GDPR') AND dat.collection_contact_name IS NOT NULL AND dat.collection_contact_name <> 'WIPED - GDPR'")

NOTES=$(q         "SELECT count(*) FROM device_requests_notes WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy'")
REF_LIVE=$(q      "SELECT count(*) FROM referring_organisation_contacts WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy'")
REF_AUD=$(q       "SELECT count(*) FROM referring_organisation_contacts_audit_trail cat JOIN referring_organisation_contacts c ON cat.id=c.id WHERE (c.updated_at <= (current_date - interval '12 months') OR c.full_name = 'Contact - Erased due to GDPR policy') AND cat.full_name <> 'Contact - Erased due to GDPR policy'")
KITS=$(q          "SELECT count(*) FROM kits WHERE coordinates IS NOT NULL AND created_at <= (current_date - interval '12 months')")

PGCRON=$(run_sql_quiet postgres "SELECT active FROM cron.job WHERE jobid = 2")
[[ -z "$PGCRON" ]] && PGCRON="absent"

# Derived structural facts
VIEW_HAS_PARENTS="no";  [[ "$VIEW_DEF" == *donor_parents* ]]  && VIEW_HAS_PARENTS="yes"
VIEW_HAS_LEAD="no";     [[ "$VIEW_DEF" == *is_lead_contact* ]] && VIEW_HAS_LEAD="yes"

cat <<EOF

  ---------------------------------------------------------------------------
   PRODUCTION STATE
  ---------------------------------------------------------------------------
   in-app flag ................................ $FLAG        (want: t)
   recorded runs .............................. $RUNS        (want: >=1)
   last run ................................... $LAST_RUN
   live function size ......................... $FN_SRC_LEN chars   (want: >5000)
   pg_cron job 2 active ....................... $PGCRON      (want: f)
   view still joins donor_parents ............. $VIEW_HAS_PARENTS  (want: no)
   view still filters is_lead_contact ......... $VIEW_HAS_LEAD  (want: no)

   REMAINING PAST RETENTION (all want 0)
   donors eligible ............................ $DONORS
     of which parentless ...................... $PARENTLESS
     of which lead contacts ................... $LEADS
   device_requests.details .................... $DR_DETAILS
   device_requests.client_ref ................. $DR_CLIENTREF
   device_requests.collection_contact_name .... $DR_CONTACT
   audit details (incl. OR-branch) ............ $AUD_DETAILS
   audit client_ref (incl. OR-branch) ......... $AUD_CLIENTREF
   audit collection_contact_name .............. $AUD_CONTACT
   device_requests_notes ...................... $NOTES
   referring_organisation_contacts ............ $REF_LIVE
     their audit trail ........................ $REF_AUD
   kits holding coordinates past 12mo ......... $KITS
  ---------------------------------------------------------------------------

EOF

# --------------------------------------------------------------------------------------
# Per-issue gates. Each issue closes on ITS OWN condition, not on a global pass.
# --------------------------------------------------------------------------------------
declare -A GATE_OK GATE_WHY GATE_EVIDENCE

check() {  # check <issue> <condition-bool> <why> <evidence-markdown>
    GATE_OK[$1]="$2"; GATE_WHY[$1]="$3"; GATE_EVIDENCE[$1]="$4"
}

# GLOBAL PRECONDITION - the cutover must actually have happened.
#
# Without this, any issue whose category happens to measure zero would close on the strength
# of there never having been anything to clear. #128 (collection_contact_name) is exactly that
# case: it measured 0 rows past threshold BEFORE the cutover as well as after, so a
# counts-only gate passed it while production was still running the old narrow function.
# Caught by a dry run on 2026-08-12; the fix is that "the rule exists and has run" is a
# separate condition from "no rows remain".
if [[ "$FLAG" == "t" && "$RUNS" -ge 1 && "$FN_SRC_LEN" -gt 5000 ]]; then
    CUTOVER_DONE=true
else
    CUTOVER_DONE=false
fi

if ! $CUTOVER_DONE; then
    warn "the cutover has NOT run in production yet:"
    warn "  flag=$FLAG (want t), runs=$RUNS (want >=1), function=$FN_SRC_LEN chars (want >5000)"
    warn "No issue can close until steps 1-3 have completed. Reporting measurements only."
fi

# #62 - pg_cron retired and the in-app job demonstrably owns retention
if [[ "$FLAG" == "t" && "$RUNS" -ge 1 && "$PGCRON" != "t" && "$FN_SRC_LEN" -gt 5000 ]]; then C62=true; else C62=false; fi
check 62 "$C62" \
  "flag=$FLAG runs=$RUNS pg_cron_active=$PGCRON fn_chars=$FN_SRC_LEN" \
  "| check | value | wanted |
|---|---|---|
| \`gdpr-in-app-cleanup\` flag | \`$FLAG\` | \`t\` |
| runs recorded in \`gdpr_cleanup_runs\` | $RUNS | >= 1 |
| last run | $LAST_RUN | |
| live function size | $FN_SRC_LEN chars | > 5000 (corrected body) |
| pg_cron job 2 active | \`$PGCRON\` | \`f\` |

The in-app job has run in production as \`api_prod\` and pg_cron no longer owns retention. Reversible with \`SELECT cron.alter_job(2, active := true);\` against the \`postgres\` database if ever needed."

# #93 - parentless donors: predicate gone AND none left eligible
if $CUTOVER_DONE && [[ "$VIEW_HAS_PARENTS" == "no" && "$PARENTLESS" == "0" && "$DONORS" == "0" ]]; then C93=true; else C93=false; fi
check 93 "$C93" \
  "view_joins_donor_parents=$VIEW_HAS_PARENTS parentless_eligible=$PARENTLESS donors_eligible=$DONORS" \
  "| check | value | wanted |
|---|---|---|
| view still joins \`donor_parents\` | $VIEW_HAS_PARENTS | no |
| parentless donors past 12 months, unscrubbed | $PARENTLESS | 0 |
| donors eligible overall | $DONORS | 0 |

The predicate that could silently skip parentless donors was removed entirely rather than patched — \`gdpr.donors_to_archive\` no longer joins \`donor_parents\` at all."

# #95 - lead-contact exemption gone AND no exempted individuals left past threshold
if $CUTOVER_DONE && [[ "$VIEW_HAS_LEAD" == "no" && "$LEADS" == "0" && "$DONORS" == "0" ]]; then C95=true; else C95=false; fi
check 95 "$C95" \
  "view_filters_is_lead_contact=$VIEW_HAS_LEAD lead_contacts_eligible=$LEADS donors_eligible=$DONORS" \
  "| check | value | wanted |
|---|---|---|
| view still filters \`is_lead_contact\` | $VIEW_HAS_LEAD | no |
| lead-contact donors past 12 months, unscrubbed | $LEADS | 0 |
| donors eligible overall | $DONORS | 0 |

The 34 named individuals this issue identified are no longer exempt, and no lead-contact donor now sits past threshold unscrubbed."

# #126 - the historical backlog: EVERY category at zero
BACKLOG=$(( DONORS + DR_DETAILS + DR_CLIENTREF + DR_CONTACT + AUD_DETAILS + AUD_CLIENTREF + AUD_CONTACT + NOTES + REF_LIVE + REF_AUD + KITS ))
if $CUTOVER_DONE && [[ "$BACKLOG" == "0" ]]; then C126=true; else C126=false; fi
check 126 "$C126" \
  "total_rows_past_retention=$BACKLOG runs=$RUNS" \
  "Every retention category measured at **0** rows remaining past threshold, total $BACKLOG.

| category | remaining |
|---|---|
| donors eligible | $DONORS |
| \`device_requests.details\` | $DR_DETAILS |
| \`device_requests.client_ref\` | $DR_CLIENTREF |
| \`device_requests.collection_contact_name\` | $DR_CONTACT |
| audit \`details\` | $AUD_DETAILS |
| audit \`client_ref\` | $AUD_CLIENTREF |
| audit \`collection_contact_name\` | $AUD_CONTACT |
| \`device_requests_notes\` | $NOTES |
| \`referring_organisation_contacts\` | $REF_LIVE |
| their audit trail | $REF_AUD |
| \`kits.coordinates\` past 12mo | $KITS |

Cleared by the forward job's first run rather than by a one-off script, which is why PR #130 was closed as superseded — the same work now happens weekly."

# #127 - audit trail in step with the live row, including the OR-branch rows
if $CUTOVER_DONE && [[ "$AUD_DETAILS" == "0" && "$AUD_CLIENTREF" == "0" && "$AUD_CONTACT" == "0" ]]; then C127=true; else C127=false; fi
check 127 "$C127" \
  "cutover=$CUTOVER_DONE aud_details=$AUD_DETAILS aud_clientref=$AUD_CLIENTREF aud_contact=$AUD_CONTACT" \
  "| audit column | remaining past retention |
|---|---|
| \`details\` | $AUD_DETAILS |
| \`client_ref\` | $AUD_CLIENTREF |
| \`collection_contact_name\` | $AUD_CONTACT |

These counts use the **widened** predicate (\`live row past threshold OR live row already carries the sentinel\`), so they include the rows the original threshold-only predicate could never have reached — measured at 1,069 \`details\` + 471 \`client_ref\` before the cutover."

# #128 - collection_contact_name, live and audit
if $CUTOVER_DONE && [[ "$DR_CONTACT" == "0" && "$AUD_CONTACT" == "0" ]]; then C128=true; else C128=false; fi
check 128 "$C128" \
  "cutover=$CUTOVER_DONE live=$DR_CONTACT audit=$AUD_CONTACT" \
  "| | remaining past 52 weeks |
|---|---|
| \`device_requests.collection_contact_name\` | $DR_CONTACT |
| its audit trail | $AUD_CONTACT |

Note this field measured 0 rows past threshold before the cutover too — this issue closes on the forward rule existing and running, not on a backlog having been cleared."

# #129 - referring organisation contacts, live and audit
if $CUTOVER_DONE && [[ "$REF_LIVE" == "0" && "$REF_AUD" == "0" ]]; then C129=true; else C129=false; fi
check 129 "$C129" \
  "cutover=$CUTOVER_DONE live=$REF_LIVE audit=$REF_AUD" \
  "| | remaining past 12 months |
|---|---|
| \`referring_organisation_contacts\` | $REF_LIVE |
| \`referring_organisation_contacts_audit_trail\` | $REF_AUD |

The audit-trail count uses the widened OR-branch predicate. This table was not covered by PR #130 at all — #129 landed after that script was written — so the audit gap here would have survived even a full historical scrub."

# --------------------------------------------------------------------------------------
say "gate results"
ISSUES=(62 93 95 126 127 128 129)
TO_CLOSE=()
for i in "${ISSUES[@]}"; do
    state=$(gh issue view "$i" --json state --jq .state 2>/dev/null || echo "UNKNOWN")
    if [[ "$state" != "OPEN" ]]; then
        printf '   #%-4s \033[1;30mSKIP\033[0m   already %s\n' "$i" "$state"
        continue
    fi
    if [[ "${GATE_OK[$i]}" == "true" ]]; then
        printf '   #%-4s \033[1;32mPASS\033[0m   %s\n' "$i" "${GATE_WHY[$i]}"
        TO_CLOSE+=("$i")
    else
        printf '   #%-4s \033[1;31mFAIL\033[0m   %s\n' "$i" "${GATE_WHY[$i]}"
    fi
done

if [[ ${#TO_CLOSE[@]} -eq 0 ]]; then
    warn "nothing passed its gate - no issues will be closed"
    exit 0
fi

echo
printf '   would close: %s\n' "${TO_CLOSE[*]}"

if $DRY_RUN; then
    ok "dry run - stopping here. Re-run without --dry-run to close these."
    exit 0
fi

confirm "Close the ${#TO_CLOSE[@]} issue(s) listed above, each with its measured evidence? Type yes:"

STAMP="$(date -u +'%Y-%m-%d %H:%M UTC')"
for i in "${TO_CLOSE[@]}"; do
    body="## Verified closed — production measurement, $STAMP

Closed by \`scripts/gdpr-cutover/4-close-issues.sh\`, which re-measures production and closes each issue only if that issue's own condition holds. The numbers below are this issue's gate, not a general pass.

${GATE_EVIDENCE[$i]}

Cutover: \`GDPR-CUTOVER-RUNBOOK.md\`. Retention now runs in-app (Mon 09:30 London, plus a startup catch-up for any missed slot). First unattended run will be the next Monday after this date."
    printf '   closing #%s ... ' "$i"
    gh issue close "$i" --comment "$body" >/dev/null && printf '\033[1;32mdone\033[0m\n' || printf '\033[1;31mFAILED\033[0m\n'
done

say "remaining open GDPR issues"
gh issue list --state open --limit 50 --json number,title \
   --template '{{range .}}#{{.number}} {{.title}}{{"\n"}}{{end}}' | grep -iE 'gdpr|retention' || echo "   none"

ok "STEP 4 complete."
