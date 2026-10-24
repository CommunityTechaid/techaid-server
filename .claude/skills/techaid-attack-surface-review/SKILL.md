---
name: techaid-attack-surface-review
description: Load when asked for a security review, an attack-surface review, "what can an anonymous caller reach", or before merging any change that adds or alters a public (unauthenticated) GraphQL field, a public mutation's RETURN TYPE, a servlet filter, a rate limiter, a webhook, an actuator/ingress setting, or a config flag/env var that gates a security control. Runs the three-pass attack-surface method (default reachability, early reachability, configuration interaction) against techaid-server and compares the result with the recorded baseline below. Also load when triaging a CVE to decide whether a vulnerable library is reachable from the public surface.
---

# TechAid Server — Attack Surface Review

Reducing what an anonymous caller can reach lowers the risk from every bug in that code,
including ones nobody has found yet. This skill adapts the three-pass method from Anthropic's
Frontier Red Team post "Using Claude to reduce the attack surface of code" (2026-10-20) to this
codebase, and records the last baseline so a re-run reports a **diff**, not a fresh essay.

Read `techaid-architecture-contract` first: security here is **default-open**. Every request is
`permitAll` at the filter chain, and protection lives in per-method `@PreAuthorize`. So the
public surface is "everything that is NOT annotated", which no allow-list records.

## When NOT to use this skill
- Fixing a specific known vulnerability: use `techaid-debugging-playbook` and `techaid-validation-and-qa`.
- Dependency CVE volume / Dependabot hygiene: that is `.github/workflows/security-scan.yml` and
  `dependency-submission.yml`. Use this skill only for the *reachability* question about one CVE.

## The method: three passes, each from a fresh context

Run each pass as a separate subagent with an empty context. Pass on only a short summary of the
previous pass (its inventory table). This mirrors the source method and stops one pass's framing
from steering the next. All passes are **read-only**. Never probe UAT or prod to "confirm" a
finding: a probe from Tony's machine is allowlisted at Cloudflare and proves nothing (see memory),
and a probe of a public mutation writes real rows.

### Pass 1: Default reachability (what is reachable at all)
Enumerate, in the production config:
1. HTTP: the `UnknownPathFilter` allow-list (`RequestFilterConfig.kt`), every
   `@RestController`/`@Controller` mapping, actuator exposure in `application.yml`.
2. GraphQL roots: every Query/Mutation field in `src/main/resources/graphql/*.graphqls`, matched
   to its resolver. Unannotated means public.
3. **Nested reachability, which is the step most often missed.** For every type a public field
   *returns*, walk its schema fields recursively. Any `@SchemaMapping` without auth, and any
   JPA association exposed in the schema, is reachable from that public root.
   `enable_lazy_load_no_trans: true` means lazy collections load after the transaction, so
   "it's lazy" is not a gate. `PublicSurfaceAuthorizationTest` checks only top-level fields.
4. For each item, decide whether the dashboard's public journey uses it. Grep the dashboard
   **source** at `D:/Code/techaid-dashboard/src` for the operation and the fields it selects.
   Reachable but unused is the finding class this pass exists for.

### Pass 2: Early reachability (what runs before authorisation decides)
Walk an anonymous request in order: ingress (ACA origin FQDN bypasses Cloudflare) → Tomcat
limits → servlet filters by `@Order` → Spring Security filters → Jackson body parse → GraphQL
interceptors → parse/validate (depth, complexity, alias limits) → scalar coercion → argument
binding (happens BEFORE the `@PreAuthorize` proxy) → resolver. For each step, record what it
parses, allocates, logs or writes, and whether an attacker controls its size.

### Pass 3: Configuration interaction (what silently weakens a control)
Inventory every env var / yml key / feature-flag row that gates a security control (see
`techaid-config-and-flags`). For each control, list the combinations under which it does
nothing **without an error or a startup failure**. Also check:
- Does `RequiredConfigurationCheck` refuse to start on the dangerous value?
- Does a flag row gate only the frontend while an env var decides server enforcement?
- Does a renamed/deprecated property name get silently ignored?

### Every finding must carry
`what` · `evidence (file:line)` · `abuse scenario` · `smallest gate` · `does the gate change
intended behaviour (grep the dashboard)` · `verified how (code read / test / trace)`.
A finding is **CONFIRMED** only when a failing test shows it (see `techaid-validation-and-qa`;
schema-shape issues need a GraphQL-level test, not a controller-method test). Otherwise it is
**STATIC**. Gates that change non-default or intended behaviour go to Tony; do not merge them.

## Baseline: 2026-10-24 (static, server 5.0.1 on dev)

Re-runs should report changes against this table, then update it.

**Public roots (intended):** `buildInfo`, `adminConfig`, `featureFlagsPublic` (returns *all*
flags), `boroughAvailabilityPublic`, `requestCount` (null unless authenticated),
`referringOrganisationsPublic` (unpaginated), `referringOrganisationContactsPublic` (exact email,
max 10; the old 2,387-name enumeration is fixed), `deliveryAvailabilityPublic`,
`deliveryBookingEligibilityPublic`, `submitDeliveryBookingPublic`, `createDeviceRequest`,
`createReferringOrganisationContact`, `post`; HTTP `/`, `/actuator/health|info`, `/typeform/hook`
(HMAC, constant-time), `/login` (vestigial formLogin, no template). Introspection is off
(`GraphQlIntrospectionDisabledTest`).

| # | Sev | Finding | Status |
|---|---|---|---|
| A | Critical | `createReferringOrganisationContact` / `createDeviceRequest` return full entities, so a nested selection reaches `referringOrganisation.referringOrganisationContacts{fullName email phoneNumber address notes}` anonymously. The dashboard selects only `id` / `id, correlationId`. Gate: narrow public return types + nested-selection test. | STATIC |
| B | High | Public write paths have no throttle; device-offer rules (adminConfig ∩ borough matrix) are enforced only in the browser; `borough` is client-chosen. | STATIC |
| C | Medium | Booking rate limiter keys on `CF-Connecting-IP`, which is attacker-set at the origin FQDN; `BookingRateLimiter.hits` never pruned on the hot path. | STATIC |
| D | Medium | No GraphQL complexity/alias limit (depth 15 only); `referringOrganisationsPublic` unpaginated with a per-org COUNT `@Formula`; `deliveryAvailabilityPublic` un-throttled. | STATIC |
| E | Low | `post` serves unpublished drafts; public `orderBy` accepts any property path; `GraphQlTelemetryInterceptor` logs all variables (PII) on error; Turnstile fails open on network error. | STATIC |
| F | Low | `server.max-http-header-size: 50MB` is inert under Boot 4.1 (deprecated name). Renaming it would make 50MB live; don't. | STATIC |

**Config interactions:** `DELIVERY_BOOKING_ENFORCE_FLAG=false` (default) makes the
delivery-booking flag frontend-only. `TURNSTILE_ENABLED` defaults false. The `local` profile
exposes all actuator endpoints anonymously (safe only while prod never activates `local`).
`X-Auth-Admin-Secret` does NOT fail open (blank rejected in code and at startup; constant-time
compare), but there is no attempt throttling.

**Not determined statically:** prod values of the env vars above; ACA ingress body-size/IP
restrictions; graphql-java parser token defaults for the resolved version.

## CVE reachability triage (one CVE at a time)
1. Find which artifact pulls the library: `./gradlew dependencyInsight --dependency <name>
   --configuration runtimeClasspath > build/di.log 2>&1` (never pipe gradlew).
2. Find which code calls the vulnerable API, then check whether any caller sits on a path in the
   Pass 1/2 inventory above (public root, pre-auth filter, body parsing).
3. Report one of: **publicly reachable** (name the entry point), **authenticated only**,
   **build/test only**, **not called**. Comment the verdict on the `security-scan` issue.
