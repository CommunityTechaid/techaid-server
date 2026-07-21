# Changelog

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
