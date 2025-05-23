-- PostgreSQL schema for Virtual Thread testing with Nexus Repository
-- This schema is designed to test various database access patterns with Virtual Threads
-- to verify compatibility and performance with PostgreSQL database operations.

-- Drop tables if they exist to ensure clean test environment
DROP TABLE IF EXISTS vt_test_relationship CASCADE;
DROP TABLE IF EXISTS vt_test_entity CASCADE;
DROP TABLE IF EXISTS vt_test_blob_data CASCADE;
DROP TABLE IF EXISTS vt_test_transaction CASCADE;
DROP TABLE IF EXISTS vt_test_batch CASCADE;
DROP TABLE IF EXISTS vt_test_concurrent CASCADE;
DROP TABLE IF EXISTS vt_test_lock CASCADE;

-- Create sequence for ID generation
DROP SEQUENCE IF EXISTS vt_test_seq;
CREATE SEQUENCE vt_test_seq START WITH 1 INCREMENT BY 1;

-- Main test entity table for basic CRUD operations
CREATE TABLE vt_test_entity (
    id BIGINT PRIMARY KEY DEFAULT nextval('vt_test_seq'),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN DEFAULT TRUE,
    priority INTEGER,
    tags VARCHAR(255)[]
);

-- Create indexes to test query performance with Virtual Threads
CREATE INDEX idx_vt_test_entity_name ON vt_test_entity(name);
CREATE INDEX idx_vt_test_entity_status ON vt_test_entity(status);
CREATE INDEX idx_vt_test_entity_created_at ON vt_test_entity(created_at);
CREATE INDEX idx_vt_test_entity_active_priority ON vt_test_entity(active, priority);

-- Table for testing relationship operations
CREATE TABLE vt_test_relationship (
    id BIGINT PRIMARY KEY DEFAULT nextval('vt_test_seq'),
    entity_id BIGINT NOT NULL,
    relation_type VARCHAR(50) NOT NULL,
    target_id BIGINT NOT NULL,
    properties JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vt_test_relationship_entity FOREIGN KEY (entity_id) REFERENCES vt_test_entity(id) ON DELETE CASCADE
);

-- Create indexes for relationship table
CREATE INDEX idx_vt_test_relationship_entity_id ON vt_test_relationship(entity_id);
CREATE INDEX idx_vt_test_relationship_target_id ON vt_test_relationship(target_id);
CREATE INDEX idx_vt_test_relationship_type ON vt_test_relationship(relation_type);

-- Table for testing BLOB operations with Virtual Threads
-- This tests large object handling which can cause thread pinning in traditional implementations
CREATE TABLE vt_test_blob_data (
    id BIGINT PRIMARY KEY DEFAULT nextval('vt_test_seq'),
    entity_id BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_name VARCHAR(255),
    data BYTEA,
    metadata JSONB,
    size_bytes BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vt_test_blob_data_entity FOREIGN KEY (entity_id) REFERENCES vt_test_entity(id) ON DELETE CASCADE
);

-- Create index for blob data table
CREATE INDEX idx_vt_test_blob_data_entity_id ON vt_test_blob_data(entity_id);
CREATE INDEX idx_vt_test_blob_data_content_type ON vt_test_blob_data(content_type);

-- Table for testing transaction isolation with Virtual Threads
CREATE TABLE vt_test_transaction (
    id BIGINT PRIMARY KEY DEFAULT nextval('vt_test_seq'),
    account_id VARCHAR(50) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    balance DECIMAL(19, 2) NOT NULL,
    transaction_time TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',
    metadata JSONB,
    version INTEGER DEFAULT 0
);

-- Create indexes for transaction table
CREATE INDEX idx_vt_test_transaction_account_id ON vt_test_transaction(account_id);
CREATE INDEX idx_vt_test_transaction_status ON vt_test_transaction(status);
CREATE INDEX idx_vt_test_transaction_time ON vt_test_transaction(transaction_time);

-- Table for testing batch operations with Virtual Threads
CREATE TABLE vt_test_batch (
    id BIGINT PRIMARY KEY DEFAULT nextval('vt_test_seq'),
    batch_id VARCHAR(50) NOT NULL,
    sequence_num INTEGER NOT NULL,
    payload TEXT,
    processed BOOLEAN DEFAULT FALSE,
    process_time TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for batch table
CREATE INDEX idx_vt_test_batch_batch_id ON vt_test_batch(batch_id);
CREATE INDEX idx_vt_test_batch_processed ON vt_test_batch(processed);
CREATE INDEX idx_vt_test_batch_sequence ON vt_test_batch(batch_id, sequence_num);

-- Table for testing concurrent operations with Virtual Threads
CREATE TABLE vt_test_concurrent (
    id BIGINT PRIMARY KEY DEFAULT nextval('vt_test_seq'),
    resource_key VARCHAR(100) NOT NULL,
    owner_id VARCHAR(50),
    lock_count INTEGER DEFAULT 0,
    last_updated TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    data JSONB,
    CONSTRAINT uk_vt_test_concurrent_resource UNIQUE (resource_key)
);

-- Create index for concurrent operations table
CREATE INDEX idx_vt_test_concurrent_owner ON vt_test_concurrent(owner_id);

-- Table for testing lock contention with Virtual Threads
CREATE TABLE vt_test_lock (
    id BIGINT PRIMARY KEY DEFAULT nextval('vt_test_seq'),
    lock_key VARCHAR(100) NOT NULL,
    lock_owner VARCHAR(50),
    acquired_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_vt_test_lock_key UNIQUE (lock_key)
);

-- Create index for lock table
CREATE INDEX idx_vt_test_lock_expires ON vt_test_lock(expires_at);

-- Create a GIN index to test more complex index operations with Virtual Threads
CREATE INDEX idx_vt_test_blob_data_metadata ON vt_test_blob_data USING GIN (metadata);
CREATE INDEX idx_vt_test_transaction_metadata ON vt_test_transaction USING GIN (metadata);

-- Create a view to test view operations with Virtual Threads
CREATE OR REPLACE VIEW vt_test_entity_view AS
SELECT e.id, e.name, e.status, e.created_at, e.active, e.priority,
       COUNT(r.id) AS relationship_count
FROM vt_test_entity e
LEFT JOIN vt_test_relationship r ON e.id = r.entity_id
GROUP BY e.id, e.name, e.status, e.created_at, e.active, e.priority;

-- Create a function to test stored procedure calls with Virtual Threads
CREATE OR REPLACE FUNCTION vt_test_update_entity_status(
    p_entity_id BIGINT,
    p_new_status VARCHAR(50)
) RETURNS VOID AS $$
BEGIN
    UPDATE vt_test_entity
    SET status = p_new_status,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_entity_id;
END;
$$ LANGUAGE plpgsql;

-- Create a function that returns a result set to test result set handling with Virtual Threads
CREATE OR REPLACE FUNCTION vt_test_find_entities_by_status(
    p_status VARCHAR(50)
) RETURNS TABLE (
    id BIGINT,
    name VARCHAR(255),
    status VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE
) AS $$
BEGIN
    RETURN QUERY
    SELECT e.id, e.name, e.status, e.created_at
    FROM vt_test_entity e
    WHERE e.status = p_status;
END;
$$ LANGUAGE plpgsql;

-- Create a trigger to test trigger handling with Virtual Threads
CREATE OR REPLACE FUNCTION vt_test_update_timestamp() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER vt_test_entity_update_timestamp
BEFORE UPDATE ON vt_test_entity
FOR EACH ROW
EXECUTE FUNCTION vt_test_update_timestamp();

-- Comments to explain the purpose of this schema
COMMENT ON TABLE vt_test_entity IS 'Main entity table for testing basic CRUD operations with Virtual Threads';
COMMENT ON TABLE vt_test_relationship IS 'Table for testing relationship operations with Virtual Threads';
COMMENT ON TABLE vt_test_blob_data IS 'Table for testing BLOB operations that might cause thread pinning';
COMMENT ON TABLE vt_test_transaction IS 'Table for testing transaction isolation with Virtual Threads';
COMMENT ON TABLE vt_test_batch IS 'Table for testing batch operations with Virtual Threads';
COMMENT ON TABLE vt_test_concurrent IS 'Table for testing concurrent operations with Virtual Threads';
COMMENT ON TABLE vt_test_lock IS 'Table for testing lock contention scenarios with Virtual Threads';