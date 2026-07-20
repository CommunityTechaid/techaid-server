-- Wipe-cert status-progression guard (#68): record erasure-certificate linkage on kits.
-- TaDa records the linkage only; where certs are stored and how references get
-- populated is upstream. wipe_cert_exemption is enum-valued (start: NO_DRIVE) with no
-- check constraint so new reasons don't need DDL.
-- kits is @Audited, so both columns must be mirrored on kit_audit_trail or
-- ddl-auto=validate (UAT, tests) fails at boot.

alter table kits add column if not exists wipe_cert_reference varchar(255);
alter table kits add column if not exists wipe_cert_exemption varchar(255);

alter table kit_audit_trail add column if not exists wipe_cert_reference varchar(255);
alter table kit_audit_trail add column if not exists wipe_cert_exemption varchar(255);

-- Enforcement flag, seeded OFF = shadow mode: the guard always evaluates but only
-- WARN-logs would-blocks. ON = reject. Toggled from the dashboard admin page, no deploy.
insert into feature_flags (flag_key, enabled, updated_at)
values ('wipe-cert-enforcement', false, now())
on conflict (flag_key) do nothing;
