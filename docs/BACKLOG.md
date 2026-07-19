# Team backlog — issue index and sequencing

Formalized 2026-07-19 from the team backlog spreadsheet (rows numbered as on
the sheet). Each actionable row is a GitHub issue labelled `backlog` in this
repo; discussion-only rows stay on the spreadsheet. Code-grounded notes in
each issue cite file:line as of the 2026-07-18 review — line numbers drift.

This doc records the **sequencing rationale** — the "why this order" that
individual issues can't hold. Issues are the source of truth for scope and
status; update this doc when the ordering logic changes, not for routine
issue progress.

## Issue index

| Row | Issue | Title | Size | Owner |
|---|---|---|---|---|
| 19 | [#62](https://github.com/CommunityTechaid/techaid-server/issues/62) | Migrate GDPR donor-PII cleanup from pg_cron to in-app @Scheduled | S | Tony |
| 20 | [#63](https://github.com/CommunityTechaid/techaid-server/issues/63) | Spike: verify printed labels carry scannable barcodes | XS | Tony/Steve |
| 21 | [#64](https://github.com/CommunityTechaid/techaid-server/issues/64) | Spike: locate and commit the HW collection script | XS | Tony/Steve |
| 4 | [#65](https://github.com/CommunityTechaid/techaid-server/issues/65) | Change device hardware info collection method | S–M | Steve |
| 3 | [#66](https://github.com/CommunityTechaid/techaid-server/issues/66) | Better device update flow (scan-driven) | M | Tony |
| 5 | [#67](https://github.com/CommunityTechaid/techaid-server/issues/67) | Wipe mobiles/tablets/Macs with certs (vendor evaluation) | eval | Steve |
| 6 | [#68](https://github.com/CommunityTechaid/techaid-server/issues/68) | Wipe-report check + status-progression blocking | M | Tony |
| 14 | [#69](https://github.com/CommunityTechaid/techaid-server/issues/69) | Device prep flow (scan-driven prep mode) | M | Tony |
| 9 | [#70](https://github.com/CommunityTechaid/techaid-server/issues/70) | Wiping and OS install station design | S (physical) | Steve |
| 15 | [#71](https://github.com/CommunityTechaid/techaid-server/issues/71) | Automatic supply-led request availability | S | unassigned |
| 11 | [#72](https://github.com/CommunityTechaid/techaid-server/issues/72) | Booking system: finish draft, promote to production | S/M | Tony |
| 17 | [#73](https://github.com/CommunityTechaid/techaid-server/issues/73) | Admin exception collections bookings | S | Tony |
| 13 | [#74](https://github.com/CommunityTechaid/techaid-server/issues/74) | Dynamic pre-filled links in collections/delivery emails | S | Tony |
| 7 | [#75](https://github.com/CommunityTechaid/techaid-server/issues/75) | Repair process: automate exit emails | S | Steve/M |
| 12 | [#76](https://github.com/CommunityTechaid/techaid-server/issues/76) | Reporting/dashboard layer (Superset PoC) | in flight | Cat + Mahi |
| 16 | [#77](https://github.com/CommunityTechaid/techaid-server/issues/77) | Integrate device deliveries into the app | L | Tony |
| 2 | [#78](https://github.com/CommunityTechaid/techaid-server/issues/78) | Public/client portal | L (blocked) | Tony |

Not formalized as issues (stay on the spreadsheet):

- **Row 1** (Azure monitoring false positives) — complete 16/07/26.
- **Row 8** (parts tracking) — discussion only, per its own description.
- **Row 10** (semi-automated HW testing) — first step is a conversation with
  EraseIT; becomes an issue if that conversation produces work.
- **Row 18** (better feedback) — no mechanism proposed yet; likely folds into
  the portal (#78) or delivery flow (#77) as a capture point.

## Near-term sequence

1. **#62 + #64** — half a day each, both are live risks today (silent GDPR
   compliance fragility; unversioned single-copy script).
2. **#63 barcode test + the #65 sample-report diff** — cheap spikes that
   de-risk the two biggest programmes before any design work.
3. **#72 to production, then #73** — fastest visible wins; the booking
   draft's hard parts are done and tested.
4. **#66 scan foundation, then #69, then #68** — the scan programme in
   dependency order.

## Why this order

- **Two spikes gate two programmes.** #63 (do labels scan?) gates the entire
  scan programme (#66 → #69); #64 (find the script) gates the hardware-info
  work (#65 → #67, #68). Both are under a day combined — run them before
  committing to any design.
- **The scan programme is frontend-heavy and shares one foundation.** The
  backend primitives for both #66 and #69 already exist; the shared
  scan-session service is built once in #66 and reused in #69. #71 only
  makes sense after both, because it depends on the record accuracy they
  deliver.
- **The delivery track converges on one domain model.** #72 (booking draft)
  is the foundation; #73 (admin exceptions) and #77 (delivery integration)
  must be designed against it, not alongside it. Production promotion of the
  booking domain is a prerequisite for both — prod master has none of it.
- **#62 is both a live fix and a prerequisite.** The in-app retention sweeper
  pattern it establishes is the GDPR mechanism #77 must ship with (storing
  beneficiary addresses breaks the deliberate no-client-PII invariant).
- **#78 (portal) sequences last.** Blocked by the Auth0 app audit and by
  #76's verdict on the reporting layer.

## Cross-references to the vetted roadmap

`.claude/skills/techaid-roadmap-and-frontier` tracks engineering candidates
from repo review; this backlog is the team's product view. Overlaps:

- **#76 ↔ roadmap B1** (impact analytics): the same work. B1 holds the
  concrete first steps (audit-table query patterns, one funder-grade KPI).
- **#77's motivation ↔ roadmap A4**: #77 eases the 3-request-limit pressure
  legitimately; A4 records that the limit is also trivially bypassable via
  contact minting (accepted-risk 2026-07-02). A fix for A4 would change the
  pressure #77 is partly justified by.
- Roadmap items A1–A3, A5–A8, B2 are engineering-internal and deliberately
  not on this product backlog.

## Provenance and maintenance

Source: `CTA-backlog-updated.xlsx` (team columns A–H; code-grounded analysis
columns I–O added 18/07/26). Issues #62–#78 created 2026-07-19. When an issue
closes or the dependency logic changes, update the index and sequencing here
in the same PR as any related behavior change (docs rule of motion,
`techaid-change-control` §6).
