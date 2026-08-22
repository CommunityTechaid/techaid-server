# Changelog

## [3.1.0](https://github.com/CommunityTechaid/techaid-server/compare/v3.0.1...v3.1.0) (2026-08-22)


### Features

* **delivery:** the UAT feedback round — phone, email, duplicate rule, delete unwind, borough gate ([#188](https://github.com/CommunityTechaid/techaid-server/issues/188)) ([7d4f8cb](https://github.com/CommunityTechaid/techaid-server/commit/7d4f8cbf18daceb54ee5832cf40039a1f9ae9637))


### Bug Fixes

* **delivery:** match the duplicate-booking copy to the any-booking rule ([#190](https://github.com/CommunityTechaid/techaid-server/issues/190)) ([55bead7](https://github.com/CommunityTechaid/techaid-server/commit/55bead79467458ebfc041cfcf1b93150180610f8))

## [3.0.1](https://github.com/CommunityTechaid/techaid-server/compare/v3.0.0...v3.0.1) (2026-08-19)


### Bug Fixes

* **kits:** finish the updated_at repair using status history ([#183](https://github.com/CommunityTechaid/techaid-server/issues/183)) ([9396df5](https://github.com/CommunityTechaid/techaid-server/commit/9396df537e970a75123c729df7ba6660d7b4c164))

## [3.0.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.7.0...v3.0.0) (2026-08-18)


### ⚠ BREAKING CHANGES

* **gdpr:** drop the coordinates columns from donors and kits ([#180](https://github.com/CommunityTechaid/techaid-server/issues/180))
* **gdpr:** Donor.coordinates and Kit.coordinates are removed from the GraphQL schema. Audited against techaid-dashboard: zero references to coordinates, Coordinates or location(address anywhere under src/. The bulk insert script and the TechAid database updater service used by Theta were checked too.
* **gdpr:** the `gdpr-in-app-cleanup` feature flag is removed. Images built before this change read that flag and fail closed when the row is absent, so rolling production back past this migration stops retention silently - V26.08.18.1500 carries the one-line restore. The dashboard's hard-coded metadata entry for the key is now dead and should be removed on its own schedule; it decorates API rows rather than driving them, so the flags page is unaffected.

### Features

* **gdpr:** drop the coordinates columns from donors and kits ([#180](https://github.com/CommunityTechaid/techaid-server/issues/180)) ([bc25ccc](https://github.com/CommunityTechaid/techaid-server/commit/bc25ccc8fe15a983256bb3ec3a84241c58f68c6f)), closes [#161](https://github.com/CommunityTechaid/techaid-server/issues/161)
* **gdpr:** make the in-app retention job the only retention path ([#175](https://github.com/CommunityTechaid/techaid-server/issues/175)) ([3bc9e9b](https://github.com/CommunityTechaid/techaid-server/commit/3bc9e9bc3f6d7bf6334a5a05bb8f0d81f9b78920)), closes [#62](https://github.com/CommunityTechaid/techaid-server/issues/62)
* **gdpr:** stop persisting coordinates on donors and kits ([#179](https://github.com/CommunityTechaid/techaid-server/issues/179)) ([11f30a0](https://github.com/CommunityTechaid/techaid-server/commit/11f30a0a4b1f05d38173f019352f61f6fd1902bd)), closes [#161](https://github.com/CommunityTechaid/techaid-server/issues/161)
* **kits:** tell the device history which revisions changed nothing ([#178](https://github.com/CommunityTechaid/techaid-server/issues/178)) ([9bea406](https://github.com/CommunityTechaid/techaid-server/commit/9bea40696bd481898f0395079f029d868fe3adef)), closes [#148](https://github.com/CommunityTechaid/techaid-server/issues/148)


### Bug Fixes

* **gdpr:** track the shipped referee activity scope in the verification script ([#173](https://github.com/CommunityTechaid/techaid-server/issues/173)) ([0252c9c](https://github.com/CommunityTechaid/techaid-server/commit/0252c9cf21c9969ea544f84aeb8b66763232be26))
* **kits:** correct updated_at polluted by [#148](https://github.com/CommunityTechaid/techaid-server/issues/148), and stop deleteDonor throwing ([#177](https://github.com/CommunityTechaid/techaid-server/issues/177)) ([134e0ec](https://github.com/CommunityTechaid/techaid-server/commit/134e0ec51cbebd7a1e36888ec1d24c1d01ec85f0))

## [2.7.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.6.0...v2.7.0) (2026-08-17)


### Features

* **availability:** borough-group x device-type availability config ([f285d58](https://github.com/CommunityTechaid/techaid-server/commit/f285d587c3e7146fcbefbffd266209ac12ac677f))
* **flags:** gate the public journey behind borough-availability-rules ([#170](https://github.com/CommunityTechaid/techaid-server/issues/170)) ([9ab4d24](https://github.com/CommunityTechaid/techaid-server/commit/9ab4d24a1f22adc298dbe9755b76280859b538de))
* **flags:** seed the streamlined-ward-lookup feature flag ([#165](https://github.com/CommunityTechaid/techaid-server/issues/165)) ([3b562e3](https://github.com/CommunityTechaid/techaid-server/commit/3b562e3c7507c81596b3633a1e987797a7505cd8))
* **flags:** seed the tower-hamlets-borough-support feature flag ([#163](https://github.com/CommunityTechaid/techaid-server/issues/163)) ([9141894](https://github.com/CommunityTechaid/techaid-server/commit/91418941782043363f5316acb6c2f2fb7bb5f02f))
* **requests:** enforce the per-referee limit per borough group ([1e90837](https://github.com/CommunityTechaid/techaid-server/commit/1e90837f35bf010bb81d7bd91c861d9113a785d0))


### Bug Fixes

* **filters:** stop a nested OR swallowing the filters beside it ([f1e179a](https://github.com/CommunityTechaid/techaid-server/commit/f1e179a4406a195c9d9626967c8d0aef8d70727a))
* **graphql:** stop the request-limit rejection carrying a second, bogus error ([#171](https://github.com/CommunityTechaid/techaid-server/issues/171)) ([ff887ec](https://github.com/CommunityTechaid/techaid-server/commit/ff887ecf9828e313e3e832abbdcbc9e24567b629))


### Performance Improvements

* **cors:** let browsers cache the graphql preflight, to stop it waking the app ([#172](https://github.com/CommunityTechaid/techaid-server/issues/172)) ([95dd7b0](https://github.com/CommunityTechaid/techaid-server/commit/95dd7b09d6eef66c926f5860a7ef67f982a2fed9))

## [2.6.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.5.3...v2.6.0) (2026-08-13)


### Features

* **gdpr:** run the in-app retention cleanup Friday 18:00 London ([#147](https://github.com/CommunityTechaid/techaid-server/issues/147)) ([8656393](https://github.com/CommunityTechaid/techaid-server/commit/8656393e71caf506df5ffc658a5663b8273bbf5e))


### Bug Fixes

* **delivery:** make ctaReference a device request id end-to-end ([#133](https://github.com/CommunityTechaid/techaid-server/issues/133), [#155](https://github.com/CommunityTechaid/techaid-server/issues/155)) ([#159](https://github.com/CommunityTechaid/techaid-server/issues/159)) ([6e6845c](https://github.com/CommunityTechaid/techaid-server/commit/6e6845cfcede6b99aedf548abce6e874995c2d2c))

## [2.5.3](https://github.com/CommunityTechaid/techaid-server/compare/v2.5.2...v2.5.3) (2026-08-13)


### Bug Fixes

* **kits:** stop every kit being rewritten when its request's collection is loaded ([#149](https://github.com/CommunityTechaid/techaid-server/issues/149)) ([6fc0727](https://github.com/CommunityTechaid/techaid-server/commit/6fc07271bbef9367f1e2b1ceb5a94e539c5e1d7c)), closes [#148](https://github.com/CommunityTechaid/techaid-server/issues/148)


### Performance Improvements

* **kits:** stop unassigning a kit from loading its siblings ([#157](https://github.com/CommunityTechaid/techaid-server/issues/157)) ([b76d91b](https://github.com/CommunityTechaid/techaid-server/commit/b76d91bffb0f1175157e12afae93adeb3055ff14)), closes [#153](https://github.com/CommunityTechaid/techaid-server/issues/153)

## [2.5.2](https://github.com/CommunityTechaid/techaid-server/compare/v2.5.1...v2.5.2) (2026-08-12)


### Bug Fixes

* **gdpr:** grant the api role SELECT on gdpr.donors_to_archive ([#142](https://github.com/CommunityTechaid/techaid-server/issues/142)) ([f25c1fe](https://github.com/CommunityTechaid/techaid-server/commit/f25c1fe2b6e81e2703016dbc39850049074cecdf))
* **gdpr:** supersede the function body carrying stale TEMP-REVERT comments ([#145](https://github.com/CommunityTechaid/techaid-server/issues/145)) ([09e1b68](https://github.com/CommunityTechaid/techaid-server/commit/09e1b68c808c710eb8f4a028903a6118162ac486))

## [2.5.1](https://github.com/CommunityTechaid/techaid-server/compare/v2.5.0...v2.5.1) (2026-08-12)


### Bug Fixes

* **gdpr:** correct retention policy to the team spreadsheet, close the audit-trail gap, and prepare the production cutover ([#138](https://github.com/CommunityTechaid/techaid-server/issues/138)) ([92b78e1](https://github.com/CommunityTechaid/techaid-server/commit/92b78e1ce59860fa323734ed8e3c0613437a76f4))

## [2.5.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.4.3...v2.5.0) (2026-08-11)


### Features

* **delivery:** arrange device request status on booking completion ([1d518f7](https://github.com/CommunityTechaid/techaid-server/commit/1d518f705d158f1fe0a2f432e0f274057b06a2a5))
* **delivery:** arrange device request status on booking completion ([6ea3095](https://github.com/CommunityTechaid/techaid-server/commit/6ea30959b562bea1dd7f3a8b8861238e97fba90b))
* **gdpr:** add startup catch-up trigger and structured per-run stats ([735ab9f](https://github.com/CommunityTechaid/techaid-server/commit/735ab9f153c60978eb5e02b57dffa9ac7e4854b1))
* **gdpr:** add startup catch-up trigger and structured per-run stats ([0ed5221](https://github.com/CommunityTechaid/techaid-server/commit/0ed522151297f771fff295c5f421f850c13ee28d))
* **gdpr:** extend in-app retention job to audit trails, contact name, notes, referring-org contacts ([1f679fe](https://github.com/CommunityTechaid/techaid-server/commit/1f679fe4488dfc6c02c21e2a5be9feea8becebf4))
* **gdpr:** extend in-app retention job to audit trails, contact name, notes, referring-org contacts ([c6fa92f](https://github.com/CommunityTechaid/techaid-server/commit/c6fa92f788e39730589b88947da85d334e8ad786))


### Bug Fixes

* **requests:** count failed collections and deliveries as open everywhere ([#120](https://github.com/CommunityTechaid/techaid-server/issues/120)) ([427bd07](https://github.com/CommunityTechaid/techaid-server/commit/427bd072618870b8aacba49eef83ac3bcd928469))

## [2.4.3](https://github.com/CommunityTechaid/techaid-server/compare/v2.4.2...v2.4.3) (2026-07-29)


### Reverts

* "fix(requests): stop counting failed deliveries against the 3-request limit ([#118](https://github.com/CommunityTechaid/techaid-server/issues/118))" ([#121](https://github.com/CommunityTechaid/techaid-server/issues/121)) ([375acf6](https://github.com/CommunityTechaid/techaid-server/commit/375acf6e76d0de6b90b6a33eb7d244fc7227c4f2))

  #118 and its revert both fall inside this release, so 2.4.3 contains no net change to application behaviour from 2.4.2. Whether a failed collection/delivery counts as an open device request is being decided in [#120](https://github.com/CommunityTechaid/techaid-server/issues/120); the generated "Bug Fixes" entry for #118 has been removed from this section because that fix is not in the release.

## [2.4.2](https://github.com/CommunityTechaid/techaid-server/compare/v2.4.1...v2.4.2) (2026-07-29)


### Bug Fixes

* **organisations:** count open requests, not the transient NEW state ([#113](https://github.com/CommunityTechaid/techaid-server/issues/113)) ([d6ac1a2](https://github.com/CommunityTechaid/techaid-server/commit/d6ac1a2753136dbe376364ef140d920eb2d662a7))


### Performance Improvements

* **telemetry:** stop double-ingesting the access log into App Insights ([#115](https://github.com/CommunityTechaid/techaid-server/issues/115)) ([aa960b6](https://github.com/CommunityTechaid/techaid-server/commit/aa960b67fe1bf06cd1f203f581c72388a2550375))

## [2.4.1](https://github.com/CommunityTechaid/techaid-server/compare/v2.4.0...v2.4.1) (2026-07-28)


### Bug Fixes

* **geocoding:** encode the query string so addresses with spaces reach Google ([#106](https://github.com/CommunityTechaid/techaid-server/issues/106)) ([cbf1641](https://github.com/CommunityTechaid/techaid-server/commit/cbf164156db2d0fe13edb9960116e692da762201))
* **test:** stop the test config shadowing the main application.yml ([#108](https://github.com/CommunityTechaid/techaid-server/issues/108)) ([0ff0fa5](https://github.com/CommunityTechaid/techaid-server/commit/0ff0fa527ddd5cdc2c88b9fd0a46113ed8d50534)), closes [#105](https://github.com/CommunityTechaid/techaid-server/issues/105)


### Performance Improvements

* **device-requests:** join-fetch the eager contact instead of one select per row ([#107](https://github.com/CommunityTechaid/techaid-server/issues/107)) ([f3c444e](https://github.com/CommunityTechaid/techaid-server/commit/f3c444e5fb65c835c481560da8be3d274dcf3879))

## [2.4.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.3.0...v2.4.0) (2026-07-22)


### Features

* **kits:** guard blocking-flag sub-statuses server-side in shadow mode ([#90](https://github.com/CommunityTechaid/techaid-server/issues/90)) ([#94](https://github.com/CommunityTechaid/techaid-server/issues/94)) ([09613b5](https://github.com/CommunityTechaid/techaid-server/commit/09613b590a20eb7b3431a73533db46b23308ff34))


### Bug Fixes

* **db:** bring gdpr.performgdprcleanup() under version control ([#87](https://github.com/CommunityTechaid/techaid-server/issues/87)) ([1a90d08](https://github.com/CommunityTechaid/techaid-server/commit/1a90d0877987605ec3044a3e1b2cbc250ab483e8))
* **db:** converge five UAT/production schema divergences ([#91](https://github.com/CommunityTechaid/techaid-server/issues/91)) ([#99](https://github.com/CommunityTechaid/techaid-server/issues/99)) ([12e3f84](https://github.com/CommunityTechaid/techaid-server/commit/12e3f84fa42fbaa24b471c71465bbd597cdd442f))
* **gdpr:** include parentless donors in retention ([#93](https://github.com/CommunityTechaid/techaid-server/issues/93)) ([#97](https://github.com/CommunityTechaid/techaid-server/issues/97)) ([939f457](https://github.com/CommunityTechaid/techaid-server/commit/939f4579deab06a84bb02ca48dfb1b074826cca3))

## [2.3.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.2.0...v2.3.0) (2026-07-21)


### Features

* wipe-cert status-progression guard ([#68](https://github.com/CommunityTechaid/techaid-server/issues/68)) ([4dd1078](https://github.com/CommunityTechaid/techaid-server/commit/4dd10781c9d8d3b6347d6971d6e43e8d4c6ae817))
* wipe-cert status-progression guard ([#68](https://github.com/CommunityTechaid/techaid-server/issues/68)) ([#81](https://github.com/CommunityTechaid/techaid-server/issues/81)) ([4dd1078](https://github.com/CommunityTechaid/techaid-server/commit/4dd10781c9d8d3b6347d6971d6e43e8d4c6ae817))


### Bug Fixes

* **db:** converge the gdpr schema in fresh databases with the live one ([7ef5ee5](https://github.com/CommunityTechaid/techaid-server/commit/7ef5ee5ddd288529a60a956b7e34b2a0c9604dec))
* stop the stale-intake sweeper re-emailing declined device requests ([63495cf](https://github.com/CommunityTechaid/techaid-server/commit/63495cfeaab7d42ee3a8b18d17131627f7a72d78))
* stop the stale-intake sweeper re-emailing declined device requests ([5b6e6cf](https://github.com/CommunityTechaid/techaid-server/commit/5b6e6cf4f5d728350208d4e4a2fb44ff07624c2a))
* stop the stale-intake sweeper re-emailing declined device requests ([#83](https://github.com/CommunityTechaid/techaid-server/issues/83)) ([63495cf](https://github.com/CommunityTechaid/techaid-server/commit/63495cfeaab7d42ee3a8b18d17131627f7a72d78))

## [2.2.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.1.0...v2.2.0) (2026-07-21)


### Features

* add notes support to ReferringOrganisationContact (referees) ([#47](https://github.com/CommunityTechaid/techaid-server/issues/47)) ([b74061e](https://github.com/CommunityTechaid/techaid-server/commit/b74061e8fadf1cb3db4f4f420e2b614e103b143f))
* admin mutation to delete a delivery booking ([b866bd7](https://github.com/CommunityTechaid/techaid-server/commit/b866bd72d852586d64fd659e0c252399491f41f7))
* admin mutation to delete a delivery booking ([d603c1a](https://github.com/CommunityTechaid/techaid-server/commit/d603c1ad91de04bde8c35afd2e62ec3af16703e3))
* attach calendar invite (.ics) to the booking confirmation email ([2fc668f](https://github.com/CommunityTechaid/techaid-server/commit/2fc668f9c93f68b489e5d6a04636e4b6ad7da541))
* attach calendar invite (.ics) to the booking confirmation email ([9a7b35f](https://github.com/CommunityTechaid/techaid-server/commit/9a7b35f1bae9cbfc96d84ee07e1b235b33ad0b5e))
* feature flags, delivery-slots admin API, and booking confirmation email ([#52](https://github.com/CommunityTechaid/techaid-server/issues/52)) ([b322131](https://github.com/CommunityTechaid/techaid-server/commit/b3221311ab71a253982cdf566cf369731342115b))
* harden the public delivery-booking mutation ([a3a8d07](https://github.com/CommunityTechaid/techaid-server/commit/a3a8d070267b642e8388cba7199107b74d8f2dfe))
* harden the public delivery-booking mutation ([#55](https://github.com/CommunityTechaid/techaid-server/issues/55)) ([40ac6aa](https://github.com/CommunityTechaid/techaid-server/commit/40ac6aa470b498e798c59e7690ae3c76dcd73333))
* match delivery bookings to device requests in the admin view ([99b750a](https://github.com/CommunityTechaid/techaid-server/commit/99b750ab88d15bf7251a643215eec506090805ad))
* match delivery bookings to device requests in the admin view ([26d2da0](https://github.com/CommunityTechaid/techaid-server/commit/26d2da0f8dd4d37c826a9120c6a479a116a9b915))
* one upcoming delivery booking per CTA reference ([e3933e5](https://github.com/CommunityTechaid/techaid-server/commit/e3933e514d3620845d60ac79a4465751aef3f080))
* public delivery-booking GraphQL API (availability + submit) ([#51](https://github.com/CommunityTechaid/techaid-server/issues/51)) ([e3cc829](https://github.com/CommunityTechaid/techaid-server/commit/e3cc829abf17b088960ce42f28fda8a616f7901e))
* seed the update-scanner feature flag ([#82](https://github.com/CommunityTechaid/techaid-server/issues/82)) ([b6831f1](https://github.com/CommunityTechaid/techaid-server/commit/b6831f1e893f3a25572a95d85ad675658b4b0b97))
* turnstile verification, per-IP throttle and flag enforcement on submitDeliveryBookingPublic ([a4a4100](https://github.com/CommunityTechaid/techaid-server/commit/a4a4100a38171e578685ed322f7fb2c874f0de82))
* turnstile verification, per-IP throttle and flag enforcement on submitDeliveryBookingPublic ([7b11db7](https://github.com/CommunityTechaid/techaid-server/commit/7b11db72cf5ca2d3941a09f8a7fb17dba09762f5))


### Bug Fixes

* clear device request collectionDate on explicit null ([#45](https://github.com/CommunityTechaid/techaid-server/issues/45)) ([9f5c5f4](https://github.com/CommunityTechaid/techaid-server/commit/9f5c5f424adcfa2eb7d5c2b43e8538e552ad972c))
* Flyway baseline for unmanaged schema; default ddl-auto to validate ([#49](https://github.com/CommunityTechaid/techaid-server/issues/49)) ([76b092f](https://github.com/CommunityTechaid/techaid-server/commit/76b092f8e237226c56a4c254e63bbdbad1ca286c))
* ktlint violations in delivery admin resolvers ([#53](https://github.com/CommunityTechaid/techaid-server/issues/53)) ([84bb89f](https://github.com/CommunityTechaid/techaid-server/commit/84bb89f674d791588485742179d4a65d192ae5b0))

## [2.1.0](https://github.com/CommunityTechaid/techaid-server/compare/v2.0.0...v2.1.0) (2026-06-04)


### Features

* log GraphQL errors with input variables ([49a0a3c](https://github.com/CommunityTechaid/techaid-server/commit/49a0a3c4fef5f755dea6c9757bdb054ef567e368))


### Bug Fixes

* accept numeric lotId/locationCode/serialNo on kit inputs ([ba8b276](https://github.com/CommunityTechaid/techaid-server/commit/ba8b2768c1d75a6930eb2b45256b5f8e366bc7a5))
* accept numeric values in String input fields ([dde5891](https://github.com/CommunityTechaid/techaid-server/commit/dde58914c34cee0d3bb78eddd761e2777a457754))
