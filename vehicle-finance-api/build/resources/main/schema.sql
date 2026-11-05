-- The API currently persists its domain state as one JSON snapshot row.
-- Customers, dealers, vehicles, loans, KYC cases, audit events, and funding
-- requests are fields within this snapshot; they are not separate SQL tables.
-- StateSnapshotStore inserts the first row when the API saves state, so no
-- seed/initial INSERT statements are required here.
CREATE TABLE IF NOT EXISTS vfs_state_snapshot (
    snapshot_key VARCHAR(100) PRIMARY KEY,
    payload TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
