-- Add statusUpdatedAt field to kits table
alter table kits add column if not exists status_updated_at timestamp;
