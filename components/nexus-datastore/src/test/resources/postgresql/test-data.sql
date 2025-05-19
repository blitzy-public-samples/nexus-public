-- PostgreSQL test data for Virtual Thread testing
-- This script populates test tables with data for validating database operations with Virtual Threads

-- Clear existing test data if present
TRUNCATE TABLE IF EXISTS vt_test_entity CASCADE;
TRUNCATE TABLE IF EXISTS vt_test_relation CASCADE;
TRUNCATE TABLE IF EXISTS vt_test_blob CASCADE;
TRUNCATE TABLE IF EXISTS vt_test_transaction CASCADE;
TRUNCATE TABLE IF EXISTS vt_test_performance CASCADE;

-- Basic entity table for read operation testing
CREATE TABLE IF NOT EXISTS vt_test_entity (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0,
    attributes JSONB
);

-- Relation table for testing join operations
CREATE TABLE IF NOT EXISTS vt_test_relation (
    id SERIAL PRIMARY KEY,
    entity_id INTEGER NOT NULL REFERENCES vt_test_entity(id),
    relation_type VARCHAR(50) NOT NULL,
    target_id INTEGER NOT NULL,
    properties JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Blob table for testing large data operations
CREATE TABLE IF NOT EXISTS vt_test_blob (
    id SERIAL PRIMARY KEY,
    entity_id INTEGER NOT NULL REFERENCES vt_test_entity(id),
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha1 VARCHAR(40),
    data BYTEA,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Transaction table for testing isolation levels
CREATE TABLE IF NOT EXISTS vt_test_transaction (
    id SERIAL PRIMARY KEY,
    entity_id INTEGER NOT NULL REFERENCES vt_test_entity(id),
    operation_type VARCHAR(20) NOT NULL,
    value_before INTEGER,
    value_after INTEGER,
    transaction_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Performance table for benchmarking
CREATE TABLE IF NOT EXISTS vt_test_performance (
    id SERIAL PRIMARY KEY,
    test_case VARCHAR(100) NOT NULL,
    thread_type VARCHAR(20) NOT NULL,  -- 'PLATFORM' or 'VIRTUAL'
    operation_count INTEGER NOT NULL,
    concurrency_level INTEGER NOT NULL,
    total_duration_ms BIGINT,
    avg_response_time_ms DOUBLE PRECISION,
    p95_response_time_ms DOUBLE PRECISION,
    p99_response_time_ms DOUBLE PRECISION,
    error_count INTEGER DEFAULT 0,
    test_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB
);

-- Insert test entities
INSERT INTO vt_test_entity (name, description, status, attributes) VALUES
('entity-1', 'Test entity for basic read operations', 'ACTIVE', '{"key1": "value1", "key2": 123}'::jsonb),
('entity-2', 'Test entity for update operations', 'ACTIVE', '{"key1": "value2", "key2": 456}'::jsonb),
('entity-3', 'Test entity for delete operations', 'INACTIVE', '{"key1": "value3", "key2": 789}'::jsonb),
('entity-4', 'Test entity for transaction operations', 'ACTIVE', '{"key1": "value4", "key2": 101112}'::jsonb),
('entity-5', 'Test entity for concurrent read operations', 'ACTIVE', '{"key1": "value5", "key2": 131415}'::jsonb),
('entity-6', 'Test entity for concurrent write operations', 'ACTIVE', '{"key1": "value6", "key2": 161718}'::jsonb),
('entity-7', 'Test entity for blob operations', 'ACTIVE', '{"key1": "value7", "key2": 192021}'::jsonb),
('entity-8', 'Test entity for complex query operations', 'ACTIVE', '{"key1": "value8", "key2": 222324}'::jsonb),
('entity-9', 'Test entity for transaction isolation', 'ACTIVE', '{"key1": "value9", "key2": 252627}'::jsonb),
('entity-10', 'Test entity for deadlock scenarios', 'ACTIVE', '{"key1": "value10", "key2": 282930}'::jsonb);

-- Insert test relations
INSERT INTO vt_test_relation (entity_id, relation_type, target_id, properties) VALUES
(1, 'PARENT', 2, '{"relation_attr": "parent-child"}'::jsonb),
(1, 'PARENT', 3, '{"relation_attr": "parent-child"}'::jsonb),
(2, 'REFERENCE', 4, '{"relation_attr": "reference"}'::jsonb),
(3, 'REFERENCE', 5, '{"relation_attr": "reference"}'::jsonb),
(4, 'PARENT', 6, '{"relation_attr": "parent-child"}'::jsonb),
(5, 'PARENT', 7, '{"relation_attr": "parent-child"}'::jsonb),
(6, 'REFERENCE', 8, '{"relation_attr": "reference"}'::jsonb),
(7, 'REFERENCE', 9, '{"relation_attr": "reference"}'::jsonb),
(8, 'PARENT', 10, '{"relation_attr": "parent-child"}'::jsonb),
(9, 'REFERENCE', 1, '{"relation_attr": "circular-reference"}'::jsonb);

-- Insert test blobs (with minimal binary data for testing)
INSERT INTO vt_test_blob (entity_id, content_type, size_bytes, sha1, data) VALUES
(7, 'application/octet-stream', 10, 'da39a3ee5e6b4b0d3255bfef95601890afd80709', '\x0102030405060708090A'),
(7, 'text/plain', 13, '2fd4e1c67a2d28fced849ee1bb76e7391b93eb12', '\x48656C6C6F2C20576F726C6421'),  -- 'Hello, World!'
(8, 'application/json', 15, '7b52009b64fd0a2a49e6d8a939753077792b0554', '\x7B226B6579223A2276616C7565227D'),  -- '{"key":"value"}'
(9, 'image/png', 8, 'f7ff9e8b7bb2e09b70935a5d785e0cc5d9d0abf0', '\x89504E470D0A1A0A');

-- Insert test transaction records
INSERT INTO vt_test_transaction (entity_id, operation_type, value_before, value_after, transaction_id) VALUES
(4, 'UPDATE', 100, 200, 'tx-001'),
(4, 'UPDATE', 200, 300, 'tx-002'),
(9, 'UPDATE', 500, 600, 'tx-003'),
(9, 'UPDATE', 600, 700, 'tx-004'),
(10, 'UPDATE', 800, 900, 'tx-005'),
(10, 'UPDATE', 900, 1000, 'tx-006');

-- Insert baseline performance metrics
INSERT INTO vt_test_performance (test_case, thread_type, operation_count, concurrency_level, total_duration_ms, avg_response_time_ms, p95_response_time_ms, p99_response_time_ms, error_count, metadata) VALUES
('read-single-entity', 'PLATFORM', 1000, 10, 5000, 5.0, 10.0, 15.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('read-single-entity', 'VIRTUAL', 1000, 10, 4800, 4.8, 9.5, 14.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('read-with-joins', 'PLATFORM', 1000, 10, 8000, 8.0, 15.0, 25.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('read-with-joins', 'VIRTUAL', 1000, 10, 7500, 7.5, 14.0, 22.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('write-single-entity', 'PLATFORM', 1000, 10, 12000, 12.0, 20.0, 30.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('write-single-entity', 'VIRTUAL', 1000, 10, 11000, 11.0, 18.0, 27.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('transaction-commit', 'PLATFORM', 1000, 10, 15000, 15.0, 25.0, 40.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('transaction-commit', 'VIRTUAL', 1000, 10, 14000, 14.0, 23.0, 35.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('blob-read', 'PLATFORM', 100, 5, 20000, 200.0, 350.0, 500.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb),
('blob-read', 'VIRTUAL', 100, 5, 18000, 180.0, 320.0, 450.0, 0, '{"cpu_cores": 4, "memory_gb": 16}'::jsonb);

-- Create high-concurrency test data (100 entities for scalability testing)
DO $$
BEGIN
    FOR i IN 1..100 LOOP
        INSERT INTO vt_test_entity (name, description, status, attributes) VALUES
        ('concurrent-entity-' || i, 'Entity for high concurrency testing', 'ACTIVE', 
         jsonb_build_object('index', i, 'group', (i % 10), 'value', (i * 10)));
    END LOOP;
END $$;

-- Create transaction isolation test data
DO $$
BEGIN
    FOR i IN 1..5 LOOP
        -- Create parent entity
        INSERT INTO vt_test_entity (name, description, status, attributes) VALUES
        ('isolation-parent-' || i, 'Parent entity for isolation testing', 'ACTIVE',
         jsonb_build_object('type', 'parent', 'index', i, 'counter', 0));
        
        -- Get the ID of the inserted parent
        DECLARE parent_id INTEGER;
        BEGIN
            SELECT currval('vt_test_entity_id_seq') INTO parent_id;
            
            -- Create child entities
            FOR j IN 1..3 LOOP
                INSERT INTO vt_test_entity (name, description, status, attributes) VALUES
                ('isolation-child-' || i || '-' || j, 'Child entity for isolation testing', 'ACTIVE',
                 jsonb_build_object('type', 'child', 'parent_index', i, 'child_index', j, 'counter', 0));
                
                -- Get the ID of the inserted child
                DECLARE child_id INTEGER;
                BEGIN
                    SELECT currval('vt_test_entity_id_seq') INTO child_id;
                    
                    -- Create relation between parent and child
                    INSERT INTO vt_test_relation (entity_id, relation_type, target_id, properties) VALUES
                    (parent_id, 'PARENT', child_id, jsonb_build_object('isolation_group', i));
                END;
            END LOOP;
        END;
    END LOOP;
END $$;

-- Create indexes to support efficient querying
CREATE INDEX IF NOT EXISTS idx_vt_test_entity_name ON vt_test_entity(name);
CREATE INDEX IF NOT EXISTS idx_vt_test_entity_status ON vt_test_entity(status);
CREATE INDEX IF NOT EXISTS idx_vt_test_relation_entity_id ON vt_test_relation(entity_id);
CREATE INDEX IF NOT EXISTS idx_vt_test_relation_target_id ON vt_test_relation(target_id);
CREATE INDEX IF NOT EXISTS idx_vt_test_blob_entity_id ON vt_test_blob(entity_id);
CREATE INDEX IF NOT EXISTS idx_vt_test_transaction_entity_id ON vt_test_transaction(entity_id);
CREATE INDEX IF NOT EXISTS idx_vt_test_performance_test_case ON vt_test_performance(test_case, thread_type);

-- Add GIN index for JSONB attributes to support efficient JSON querying
CREATE INDEX IF NOT EXISTS idx_vt_test_entity_attributes ON vt_test_entity USING GIN (attributes);
CREATE INDEX IF NOT EXISTS idx_vt_test_relation_properties ON vt_test_relation USING GIN (properties);
CREATE INDEX IF NOT EXISTS idx_vt_test_performance_metadata ON vt_test_performance USING GIN (metadata);