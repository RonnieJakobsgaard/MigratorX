-- Migration history table for tracking distributed migrations
CREATE TABLE IF NOT EXISTS migration_history (
    id BIGSERIAL PRIMARY KEY,
    migration_id TEXT NOT NULL UNIQUE,
    version TEXT,
    checksum TEXT,
    status TEXT NOT NULL,
    node_id TEXT,
    requested_by TEXT,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
    applied_at TIMESTAMP WITH TIME ZONE,
    error TEXT
);

-- Index for faster lookups
CREATE INDEX IF NOT EXISTS idx_migration_history_status ON migration_history(status);
CREATE INDEX IF NOT EXISTS idx_migration_history_migration_id ON migration_history(migration_id);
