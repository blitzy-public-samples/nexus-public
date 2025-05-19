-- PostgreSQL schema for Virtual Thread testing
-- This schema is designed to test JDBC operations with Java 21 Virtual Threads
-- focusing on scenarios that might cause thread pinning in traditional implementations

-- Drop tables if they exist to ensure clean setup
DROP TABLE IF EXISTS vt_child_table CASCADE;
DROP TABLE IF EXISTS vt_parent_table CASCADE;
DROP TABLE IF EXISTS vt_large_object_test CASCADE;
DROP TABLE IF EXISTS vt_batch_test CASCADE;
DROP TABLE IF EXISTS vt_index_test CASCADE;
DROP TABLE IF EXISTS vt_transaction_test CASCADE;
DROP TABLE IF EXISTS vt_concurrent_test CASCADE;

-- Table for testing basic CRUD operations with Virtual Threads
CREATE TABLE vt_parent_table (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN DEFAULT TRUE,
    priority INTEGER DEFAULT 0,
    data JSONB
);

-- Table with foreign key relationship to test transaction integrity with Virtual Threads
CREATE TABLE vt_child_table (
    id SERIAL PRIMARY KEY,
    parent_id INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    value NUMERIC(10, 2),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_parent
        FOREIGN KEY (parent_id)
        REFERENCES vt_parent_table (id)
        ON DELETE CASCADE
);

-- Create index on foreign key to test index operations with Virtual Threads
CREATE INDEX idx_child_parent_id ON vt_child_table (parent_id);

-- Table for testing large object operations with Virtual Threads
-- bytea and text columns can cause thread pinning in traditional implementations
CREATE TABLE vt_large_object_test (
    id SERIAL PRIMARY KEY,
    binary_data BYTEA,
    text_data TEXT,
    description VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes on large object columns to test different index operations
CREATE INDEX idx_large_object_description ON vt_large_object_test (description);
-- Hash index for equality comparisons
CREATE INDEX idx_large_object_id_hash ON vt_large_object_test USING HASH (id);

-- Table for testing batch operations with Virtual Threads
CREATE TABLE vt_batch_test (
    id SERIAL PRIMARY KEY,
    batch_id INTEGER NOT NULL,
    sequence_num INTEGER NOT NULL,
    data VARCHAR(500),
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for batch processing
CREATE INDEX idx_batch_test_batch_id ON vt_batch_test (batch_id);
CREATE INDEX idx_batch_test_processed ON vt_batch_test (processed);
CREATE INDEX idx_batch_test_batch_seq ON vt_batch_test (batch_id, sequence_num);

-- Table for testing GIN index operations with Virtual Threads
-- GIN indexes are useful for full-text search and array operations
CREATE TABLE vt_index_test (
    id SERIAL PRIMARY KEY,
    tags TEXT[],
    keywords TEXT[],
    document JSONB,
    full_text TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create GIN indexes for array and JSONB operations
CREATE INDEX idx_index_test_tags ON vt_index_test USING GIN (tags);
CREATE INDEX idx_index_test_document ON vt_index_test USING GIN (document);
-- Create GIN index for full-text search
CREATE INDEX idx_index_test_full_text ON vt_index_test USING GIN (to_tsvector('english', full_text));

-- Table for testing transaction operations with Virtual Threads
CREATE TABLE vt_transaction_test (
    id SERIAL PRIMARY KEY,
    account_id VARCHAR(50) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    balance NUMERIC(15, 2) NOT NULL,
    description TEXT,
    transaction_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'pending',
    CONSTRAINT chk_transaction_type CHECK (transaction_type IN ('deposit', 'withdrawal', 'transfer'))
);

-- Create indexes for transaction processing
CREATE INDEX idx_transaction_test_account_id ON vt_transaction_test (account_id);
CREATE INDEX idx_transaction_test_status ON vt_transaction_test (status);
CREATE INDEX idx_transaction_test_date ON vt_transaction_test (transaction_date);

-- Table for testing concurrent operations with Virtual Threads
CREATE TABLE vt_concurrent_test (
    id SERIAL PRIMARY KEY,
    resource_id VARCHAR(50) NOT NULL,
    lock_owner VARCHAR(100),
    lock_acquired_at TIMESTAMP WITH TIME ZONE,
    lock_expires_at TIMESTAMP WITH TIME ZONE,
    data JSONB,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create unique index on resource_id to test concurrent access patterns
CREATE UNIQUE INDEX idx_concurrent_test_resource_id ON vt_concurrent_test (resource_id);
-- Create index on lock expiration to test time-based operations
CREATE INDEX idx_concurrent_test_lock_expires ON vt_concurrent_test (lock_expires_at);

-- Add comments to explain the purpose of this schema
COMMENT ON TABLE vt_parent_table IS 'Table for testing basic CRUD operations with Virtual Threads';
COMMENT ON TABLE vt_child_table IS 'Table for testing foreign key relationships with Virtual Threads';
COMMENT ON TABLE vt_large_object_test IS 'Table for testing large object operations that might cause thread pinning';
COMMENT ON TABLE vt_batch_test IS 'Table for testing batch operations with Virtual Threads';
COMMENT ON TABLE vt_index_test IS 'Table for testing GIN index operations with Virtual Threads';
COMMENT ON TABLE vt_transaction_test IS 'Table for testing transaction operations with Virtual Threads';
COMMENT ON TABLE vt_concurrent_test IS 'Table for testing concurrent operations with Virtual Threads';

-- Add function to test procedural operations with Virtual Threads
CREATE OR REPLACE FUNCTION vt_test_function(p_id INTEGER)
RETURNS TABLE (id INTEGER, name VARCHAR, description TEXT) AS $$
BEGIN
    -- Simulate some processing time that might cause thread pinning
    PERFORM pg_sleep(0.01);
    
    RETURN QUERY
    SELECT vt.id, vt.name, vt.description
    FROM vt_parent_table vt
    WHERE vt.id = p_id;
END;
$$ LANGUAGE plpgsql;