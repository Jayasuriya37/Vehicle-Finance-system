-- MySQL 8+: preserve the complete application snapshot (TEXT has a 64 KB limit).
-- Connection timestamps use UTC through the JDBC connection configuration.
CREATE TABLE IF NOT EXISTS vfs_state_snapshot (
    snapshot_key VARCHAR(100) PRIMARY KEY,
    payload LONGTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;