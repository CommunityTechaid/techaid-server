UPDATE device_requests SET is_sales = false WHERE is_sales IS NULL;
ALTER TABLE device_requests ALTER COLUMN is_sales SET NOT NULL;
ALTER TABLE device_requests ALTER COLUMN is_sales SET DEFAULT false;
