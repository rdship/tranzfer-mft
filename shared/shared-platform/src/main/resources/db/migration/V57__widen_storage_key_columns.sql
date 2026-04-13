-- V57: Widen storage key columns — file paths can exceed 64 chars
ALTER TABLE flow_step_snapshots ALTER COLUMN input_storage_key TYPE varchar(512);
ALTER TABLE flow_step_snapshots ALTER COLUMN output_storage_key TYPE varchar(512);
