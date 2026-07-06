---
name: techaid-domain-reference
description: Load when touching or interpreting ANY domain concept or business logic in techaid-server — kits, device requests, donors, donor parents, referring organisations, contacts, notes, audit trails, AdminConfig, emails, the Typeform intake webhook, collection/calendar sync, Auth0 permission scopes (write:organisations etc.), the public (unauthenticated) GraphQL surface, or any domain enum/field (KitStatus, DeviceRequestStatus, lotId, correlationId, archived, requestCount…). This is the definitive "what does this field/scope/status mean" and "where is the business logic for X" reference.
---

# TechAid Domain Reference

The domain knowledge pack for `techaid-server`. Everything here was verified against the source on 2026-07-03; business-meaning claims not provable from code are marked **(inferred)**.

## Mission (why this API exists)

Community TechAid is a London charity that collects donated devices ("**kits**"), refurbishes them, and distributes them to digitally excluded people. Distribution is not direct-to-public: **referring organisations** (charities, councils, social prescribers — *inferred*) have named **contacts** ("referees") who file **device requests** on behalf of their clients. This API (`cta.*` Kotlin/Spring GraphQL, endpoint `/graphql`) backs the staff ops dashboard at `app.communitytechaid.org.uk` plus a small public request-form surface.

## When NOT to use this skill

- Auth *architecture* (why `permitAll()` + `@PreAuthorize`, JWT decoding, filter order) → **techaid-architecture-contract**. This skill only catalogs *which scope guards which resolver*.
- Deploy/runtime/Azure questions → **techaid-deploy-and-operate**; measuring behaviour → **techaid-diagnostics-and-observability**.
- Schema/DB migration mechanics → **techaid-database-operations**.
- History of why a design ended up this way (LenientString saga, auth-gap fixes) → **techaid-failure-archaeology**.
- Writing/changing code → also load **techaid-change-control** and **techaid-validation-and-qa**.

## Actor glossary

| Term | Entity / table | Meaning |
|---|---|---|
| Donor | `Donor` / `donors` | Individual or org giving devices. Fields: name, email, phone, postCode, referral, `isLeadContact`, geocoded `coordinates`. `kitCount` is a live `@Formula` count. |
| Donor parent | `DonorParent` / `donor_parents` | Umbrella over donors: `type` is `BUSINESS` (corporate donation drive — *inferred*) or `DROPPOINT` (physical drop-off location — *inferred*; default). `donorCount` is `@Formula`. |
| Kit | `Kit` / `kits` | One physical device moving through refurbishment. See Kit domain. |
| Referring organisation | `ReferringOrganisation` / `referring_organisations` | Body allowed to refer clients. |
| Referring organisation contact ("referee") | `ReferringOrganisationContact` / `referring_organisation_contacts` | Named person at an org who files requests. Deduped on (fullName, email, org) at creation. |
| Device request | `DeviceRequest` / `device_requests` | A contact's request for devices for one client (`clientRef` is the org's own client reference; no client PII stored beyond that). |
| Client | — (no entity) | The end beneficiary. Exists only as `clientRef`/`borough`/needs data on a request. |
| CTA staff / volunteers | Auth0 users | No local user table. Identity and permission scopes live in Auth0 (`UsersGraph.kt` proxies the Auth0 Management API). Notes record the author in a `volunteer` string field. |

## Kit domain (`KitModels.kt`)

Key fields on `Kit`: `type`, `status`, `model`/`make`/`deviceVersion`, `serialNo`, `age`, spec fields (`storageCapacity` GB, `typeOfStorage`, `ramCapacity`, `cpuType`, `cpuCores`, `tpmVersion`, `batteryHealth` %), `location` (free text; geocoded into `coordinates` JSONB on create/update via Google Places), `lotId` + `locationCode` (bulk-import provenance from the Google-Sheet importer — often numeric in the sheet, hence the `LenientString` scalar, see below), `archived` (stored as `Y`/`N` char via `YesNoConverter`; set true when delivered/recycled — hides the kit from active work, *inferred*), `statusUpdatedAt` (bumped in mutation code whenever `status` changes — NOT automatic at the DB level), `donor` (nullable FK), `deviceRequest` (nullable FK = current allocation), `notes`, `subStatus` (embedded refurb flags: `installationOfOSFailed`, `wipeFailed`, `needsSparePart`, `needsFurtherInvestigation`, `network`, `installedOSName`, `lockedToUser`), `attributes` (legacy JSONB grab-bag: `otherType`, `state`, `credentials`, `status: List<String>`, `network`, `otherNetwork`).

**Enums:**

- `KitType` (declared order): `OTHER` (default), `LAPTOP`, `DESKTOP`, `TABLET`, `SMARTPHONE`, `ALLINONE`, `COMMSDEVICE` (SIM card — see email wording below), `BROADBANDHUB`.
- `KitStorageType`: `HDD, SSD, HYBRID, UNKNOWN`.
- `KitStatus` — the refurbishment pipeline, in declared order (stage meanings *inferred* from names; the three `DISTRIBUTION_*` values are confirmed "terminal" by code — see cascade below):

| Status | Stage | Meaning |
|---|---|---|
| `DONATION_NEW` | intake | just donated (default on create) |
| `PROCESSING_START` | refurb | work started |
| `PROCESSING_WIPED` | refurb | data wiped |
| `PROCESSING_OS_INSTALLED` | refurb | OS installed |
| `PROCESSING_STORED` | refurb | refurbished, in storage |
| `ALLOCATION_ASSESSMENT` | allocation | being matched/assessed |
| `ALLOCATION_READY` | allocation | ready to allocate |
| `ALLOCATION_QC_COMPLETED` | allocation | final quality check passed |
| `ALLOCATION_DELIVERY_ARRANGED` | allocation | delivery booked |
| `DISTRIBUTION_DELIVERED` | terminal | with the client |
| `DISTRIBUTION_RECYCLED` | terminal | scrapped/recycled |
| `DISTRIBUTION_REPAIR_RETURN` | terminal | returned for repair (*inferred*) |

**Kit ↔ request assignment:** `Kit.deviceRequest` FK; `DeviceRequest.addKit/removeKit` maintain both sides. Bulk assignment: mutation `assignKitsToDeviceRequest(deviceRequestId, kitIds)` moves kits (detaching them from any previous request). `DeviceRequest.kitCount` is a `@Formula` subquery count.

**Audit:** `@Audited` with audit tables `kit_audit_trail`, `donors_audit_trail`, `device_requests_audit_trail`, `referring_organisations*_audit_trail`, `donorParents_audit_trail`; revision metadata in `custom_rev_info` where `CustomRevisionEntityListener` stamps `customUser = "email|name"` of the acting user. JSONB fields, formula counts, and note collections are `@NotAudited`.

**Kit equality:** `equals`/`hashCode` are id-based; kits are persisted before entering sets, so hashes are stable.

## Device request domain

### Status lifecycle (`DeviceRequestStatus`)

| Status | How it's reached |
|---|---|
| `NEW` | created via public `createDeviceRequest` (default) |
| `PROCESSING_EQUALITIES_DATA_COMPLETE` | Typeform webhook confirms the equalities form (see intake protocol) |
| `PROCESSING_COLLECTION_DELIVERY_ARRANGED` | staff/calendar-sync set (*inferred*: booking made) |
| `PROCESSING_ON_HOLD` | staff set (*inferred*) |
| `REQUEST_COMPLETED` | staff set — **triggers the kit-archival cascade below** |
| `REQUEST_COLLECTION_DELIVERY_FAILED` | staff/calendar-sync set (*inferred*) |
| `REQUEST_DECLINED` | staff set, or **auto-set** by the 20-minute stale-intake sweeper |
| `REQUEST_CANCELLED` | staff set |

### Two-step intake protocol (the `correlationId` dance)

1. Public form calls `createDeviceRequest` (no auth). Server checks the contact's open-request count against `DEVICE_REQUEST_LIMIT = 3` (constant in `DeviceRequestMutations.kt`; throws `ExceededDeviceRequestLimitException` → GraphQL BAD_REQUEST). The request is saved `NEW` with a random `correlationId` (`Random.nextLong(1, MAX)`), which marks it "equalities data pending".
2. The applicant is sent to a Typeform (equalities monitoring form) carrying `corr_id` as a hidden field.
3. Typeform POSTs to `/typeform/hook` (`TypeformWebhookController`). The HMAC-SHA256 signature header `Typeform-Signature` is verified against `TYPEFORM_KEY` (constant-time compare). On match by `corr_id`: status → `PROCESSING_EQUALITIES_DATA_COMPLETE`, `correlationId` cleared, acknowledgement email sent.
4. Sweeper `DeclineIncompleteDeviceRequests` (`@Scheduled(cron = "0 */20 * * * *", zone = "UTC")` — every 20 min) finds requests still holding a `correlationId` older than 20 minutes, sets `REQUEST_DECLINED`, clears the id, and emails the contact a declined notice. So: **a request that never completes the Typeform is auto-declined within ~20–40 minutes.**

`correlationId` is `@NotAudited`; a non-null value always means "Typeform pending".

**Known accepted risk (as of 2026-07-03, parked):** `createReferringOrganisationContact` is also public and dedupes only on exact (fullName, email, org) — a caller can mint a fresh contact to sidestep the 3-request limit. Details in **techaid-failure-archaeology**.

### Request payload fields

- `deviceRequestItems` (embedded counts): `phones, tablets, laptops, allInOnes, desktops, other, commsDevices, broadbandHubs`. `commsDevices` = SIM cards — the email formatter renders it as "SIM card (6 months, 20GB data, unlimited UK calls)". `broadbandHubs` = broadband hubs.
- `deviceRequestNeeds` (embedded): `hasInternet, hasMobilityIssues, needQuickStart`.
- `clientRef` (org's client reference), `borough`, `details` (TEXT), `isSales` (NOT NULL, default false; sales vs charity distribution — *inferred*), `isPrepped`.
- Collection logistics: `collectionDate: Instant?`, `collectionMethod` (`COLLECTION | DELIVERY | UNKNOWN`), `collectionContactName`.

### The two update mutations differ on null handling — do not confuse them

- `updateDeviceRequest` (dashboard, full-replace): an explicit null `collectionDate` **clears** the stored date (deliberate; PR #45). Other nullable fields fall back to the existing value.
- `synchronizeCollectionDataForDeviceRequest` (partial merge): every field including `collectionDate` keeps the old value when null. Caller is a **Google Apps Script calendar sync** authenticating with an Auth0 client-credentials token that must carry `write:organisations` (code comment warns to verify the grant before any prod promote).

### REQUEST_COMPLETED cascade

In `updateDeviceRequest`, when status transitions *to* `REQUEST_COMPLETED` (from anything else): every kit on the request whose status is not already one of `DISTRIBUTION_DELIVERED / DISTRIBUTION_RECYCLED / DISTRIBUTION_REPAIR_RETURN` is set to `DISTRIBUTION_DELIVERED` **and** `archived = true`.

### AdminConfig (public request-form switches)

`admin_config` is a singleton row (id = 1) of booleans: `canPublicRequestSIMCard / Laptop / Phone / BroadbandHub / Tablet / Desktop`. Query `adminConfig` is **public** (the form reads it to show/hide device types); mutation `updateAdminConfig` requires `app:admin`. Enforcement is dashboard-side only — the API does not itself reject a `createDeviceRequest` containing a disabled type (verified: no check in `DeviceRequestMutations`).

## Referring organisations

- `ReferringOrganisation`: name, website, phoneNumber, `archived` (Y/N). `requestCount` `@Formula` counts **only `status='NEW'`** requests across its contacts.
- `ReferringOrganisationContact`: fullName, email, phoneNumber, address, `archived`. Its `requestCount` `@Formula` counts requests **NOT IN** (`REQUEST_CANCELLED`,`REQUEST_COMPLETED`,`REQUEST_DECLINED`) — i.e. "open" — and this is the count the 3-request limit checks. Note the org-level and contact-level formulas deliberately(?) differ — org counts NEW only. (*Discrepancy is in code; intent unverified.*)
- Public typeahead surfaces (both unauthenticated, both return **projections**, not entities):
  - `referringOrganisationsPublic(where: ReferringOrganisationPublicWhereInput)` → `{id, name}` only; filter restricted to `name` + `archived` (tightened 2026-07, PR #48).
  - `referringOrganisationContactsPublic(where: ReferringOrganisationContactPublicWhereInput)` → `{id, fullName}` only; filter allows `email`, nested `referringOrganisation`, `archived`, AND/OR/NOT — the public form uses email lookup to find an existing referee (*inferred*).
- `createReferringOrganisationContact` is public (the form registers new referees); returns the existing contact if (fullName, email, org) matches. `createReferringOrganisation` is **not** public (requires `write:organisations`; the form's inline org-creation path was removed from the dashboard 2026-07).

## Notes & audit trails

| Note entity | Table | Attached to | Created via | Deleted via (scope) |
|---|---|---|---|---|
| `Note` | `note` | Kit | `createNote`/`updateNote` or inline in kit mutations (`write:kits`) | `deleteNote` (`write:kits`) |
| `DeviceRequestNote` | `device_requests_notes` | DeviceRequest | inline in `updateDeviceRequest` | `deleteDeviceRequestNote` (`write:organisations`) |
| `ReferringOrganisationNote` | `referring_organisations_notes` | Organisation | inline in `updateReferringOrganisation` | `deleteReferringOrganisationNote` (`write:organisations`) |
| `ReferringOrganisationContactNote` | `referring_organisation_contacts_notes` | Contact | inline in `updateReferringOrganisationContact` (added 2026-06, PR #47) | `deleteReferringOrganisationContactNote` (`write:organisations`) |

All notes carry `content` (≤4096), timestamps, and `volunteer` (author's name-or-email string). Note collections render newest-first (`@SQLOrder("updatedAt DESC")`).

Envers history is exposed to the dashboard via `kitAudits` (`read:kits`, `KitAuditTrailQueries.kt`) and `deviceRequestAudits` (`app:admin` or `read:organisations`, `DeviceRequestAuditTrailQueries.kt`). Each revision row includes the acting user from `custom_rev_info`.

## Auth0 permission scope inventory — THE "what scope do I need" table

Security model: everything is `permitAll()` at the HTTP layer; authorization = per-method/class `@PreAuthorize`. **No annotation ⇒ anonymous.** (Why, and the incidents this caused → **techaid-architecture-contract** / **techaid-failure-archaeology**.)

| Scope | Grants |
|---|---|
| `read:kits` | `kits`, `kitsConnection`, `kit`, `statusCount`, `typeCount`, `getAttributes`, `kitAudits` |
| `write:kits` | `createKit`, `quickCreateKit`, `updateKit`, `updateKits` (bulk), `autoCreateKit`, `autoUpdateKit`; kit-note create/update/delete |
| `delete:kits` | `deleteKit` |
| `read:organisations` (or `app:admin`) | `deviceRequests`, `deviceRequestConnection`, `deviceRequest`, `deviceRequestAudits`, `referringOrganisations(+Connection, single)`, `referringOrganisationContacts(+Connection, single)` |
| `write:organisations` | `updateDeviceRequest`, `synchronizeCollectionDataForDeviceRequest` (calendar sync!), `assignKitsToDeviceRequest`, `createReferringOrganisation`, `updateReferringOrganisation`, `updateReferringOrganisationContact`; org/contact/request note deletes |
| `delete:organisations` | `deleteDeviceRequest`, `deleteReferringOrganisation`, `deleteReferringOrganisationContact` |
| `read:donors` (or `app:admin`) | `donors`, `donorsConnection`, `donor` |
| `write:donors` / `delete:donors` | `createDonor`, `updateDonor` / `deleteDonor` |
| `read:donorParents` (or `app:admin`) | `donorParents(+Connection, single)` |
| `write:donorParents` / `delete:donorParents` | create/update / delete donor parents |
| `read:users` | `users`, `user`, `roles`, `role` (Auth0 Management API proxy) |
| `write:users` | `assignRoles`, `removeRoles`, `deleteUser`, `removePermissions` |
| `read:content` | `posts`, `postsConnection`; also unlocks `secured` posts on public `post` |
| `write:content` | `createPost`, `updatePost`, `deletePost` |
| `app:admin` | `updateAdminConfig`; also accepted wherever listed above |
| any authenticated user | `location(address)` geocoding proxy (billed Google key — gated 2026-07); `requestCount` (returns null anonymously) |
| `admin:kits`, `admin:donors` | Checked in `FilterService.kitFilter()/donorFilter()` but both branches currently return the same empty filter — **effectively no-ops today** (verified 2026-07-03) |

### Public (unauthenticated) surface — keep this list in your head

`createDeviceRequest` · `createReferringOrganisationContact` · `referringOrganisationsPublic` · `referringOrganisationContactsPublic` · `adminConfig` (query) · `buildInfo` · `post` (unless `secured`) · `POST /typeform/hook` (HMAC-gated) · `/actuator/health` (status only). Anything you add without `@PreAuthorize` joins this list silently — write a `PublicSurfaceAuthorizationTest` case for every new resolver (see **techaid-validation-and-qa**).

## Email flows (`MailService.kt`, templates in `src/main/resources/templates/email/`)

| Trigger | Template | Subject |
|---|---|---|
| Typeform completes intake (`acknowledgeSubmission`) | `device-request-acknowledged.html` | "Community TechAid: Device Request Acknowledged" |
| 20-min sweeper declines (`notifyDeclinedRequest`) | `device-request-declined.html` | "Community TechAid: Device Request Declined" |

Both go **to the referring contact's email**, from `gmail.address` (default `communitytechaid@gmail.com`), **BCC `gmail.bcc-address`** (default `distributions@communitytechaid.org.uk`). Transport = Gmail API with OAuth refresh token (no SMTP). **Master switch `gmail.enabled` / `GMAIL_ENABLED` (default false)** — when off, senders return silently; send failures are logged, never thrown. `fragments.html` holds shared layout. There is no other outbound email in the codebase (verified: only `DeviceRequestService` calls `mailService`).

## GraphQL schema map (SDL file → resolver home)

Endpoint `/graphql` (GraphiQL not exposed). Custom scalars in `root.graphqls`: `Long`, `BigDecimal`, `Instant`, and **`LenientString`** — a String that also accepts JSON numbers/booleans and stringifies them; applied ONLY to `lotId`, `locationCode`, `serialNo` on kit *inputs* (bulk-import cells are often numeric). Never redefine the built-in `String` (that failed once — see **techaid-failure-archaeology**). Filter inputs (`TextComparison`, `IntegerComparison`, `TimeComparison`, AND/OR/NOT composition) are declared in `filters.graphqls` and built into QueryDSL predicates by classes in `cta.app.graphql.filters`.

| SDL file | Resolvers |
|---|---|
| `root.graphqls` | `GlobalQueries` (`location`, `buildInfo`) + shared types |
| `kits.graphqls` | `KitQueries`, `KitMutations`, `NoteMutations` |
| `kitAuditTrail.graphqls` | `KitAuditTrailQueries` |
| `deviceRequests.graphqls` | `DeviceRequestQueries`, `DeviceRequestMutations` |
| `deviceRequestAuditTrail.graphqls` | `DeviceRequestAuditTrailQueries` |
| `deviceRequestNotes.graphqls` | `DeviceRequestNoteMutations` |
| `donors.graphqls` / `donorParents.graphqls` | `DonorQueries/Mutations`, `DonorParentQueries/Mutations` |
| `referringOrganisations.graphqls` (+`Notes`) | `ReferringOrganisationQueries/Mutations`, `ReferringOrganisationNoteMutations` |
| `referringOrganisationContact.graphqls` (+`ContactNotes`) | `ReferringOrganisationContactQueries/Mutations`, `ReferringOrganisationContactNoteMutations` |
| `adminConfig.graphqls` | `AdminConfigQueries/Mutations` |
| `users.graphqls` | `UsersGraph.kt` (`UserQueries`, `UserMutations`, `RoleResolver`, `UserResolver` — Auth0 proxy; nested Role.permissions/users via `@SchemaMapping`) |
| `blog.graphqls` | `BlogGraph.kt` |
| `filters.graphqls` / `notes.graphqls` | shared inputs/types only |

## Minor models

- **Blog** (`Post` / `posts` table, `BlogGraph.kt`): title/slug/content, `published`, `secured` (secured posts need `read:content` even on the public `post` query). Likely legacy CMS for site content; dashboard usage unconfirmed — treat as vestigial until proven otherwise (*uncertain*).
- `Coordinates` (JSONB embeddable): geocoding result `{lat, lng, address, input}` from `LocationService` (Google Places; 2s/5s timeouts).

## Provenance and maintenance

Authored 2026-07-03 against dev @ 76b092f by the outgoing maintainer; all enums, scopes, guards, formulas, and flows read directly from source. Re-verify volatile facts:

- Enums: `grep -n "enum class" src/main/kotlin/cta/app/*.kt` then read the bodies.
- Scope table: `grep -rn "PreAuthorize" src/main/kotlin --include="*.kt"` — diff against the table above; any resolver WITHOUT a hit is public.
- Public surface: run `PublicSurfaceAuthorizationTest` (`./gradlew test --tests '*PublicSurfaceAuthorizationTest'`).
- Request limit: `grep -n "DEVICE_REQUEST_LIMIT" src/main/kotlin -r` (3 as of 2026-07-03).
- Sweeper cadence: `grep -n "Scheduled" src/main/kotlin/cta/app/schedulingtasks/*.kt` (20 min as of 2026-07-03).
- LenientString reach: `grep -n "LenientString" src/main/resources/graphql/*.graphqls` (kit inputs only as of 2026-07-03).
- Email flows: `grep -rn "templateEngine.process\|sendMessage" src/main/kotlin --include="*.kt"`.
