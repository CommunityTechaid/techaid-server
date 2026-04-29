DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'device_requests') THEN
        UPDATE device_requests SET is_sales = false WHERE is_sales IS NULL;
        ALTER TABLE device_requests ALTER COLUMN is_sales SET NOT NULL;
        ALTER TABLE device_requests ALTER COLUMN is_sales SET DEFAULT false;
    END IF;
END $$;
