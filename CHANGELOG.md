# Changelog

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
