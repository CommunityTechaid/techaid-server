-- Add locationCode field to kits table
alter table kits add column if not exists location_code varchar(255);
