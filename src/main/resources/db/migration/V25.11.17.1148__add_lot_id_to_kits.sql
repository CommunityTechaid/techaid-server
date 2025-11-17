-- Add lotId field to kits table
alter table kits add column if not exists lot_id varchar(255);
