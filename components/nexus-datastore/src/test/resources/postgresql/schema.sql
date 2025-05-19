-- PostgreSQL schema for Virtual Thread testing with Java 21
-- This schema is designed to test JDBC operations with Java 21 Virtual Threads
-- focusing on scenarios that might cause thread pinning in traditional implementations
--
-- This schema is specifically created to test the following scenarios with Virtual Threads:
-- 1. Basic CRUD operations with various column types
-- 2. Foreign key relationships and referential integrity
-- 3. Large object (bytea/text) operations that traditionally cause thread pinning
-- 4. Batch processing operations
-- 5. GIN index operations and query performance
-- 6. Transaction processing with concurrent operations
-- 7. Optimistic and pessimistic locking patterns
-- 8. PostgreSQL-specific features like array types, JSON operations, and full-text search
--
-- The schema is designed to be compatible with PostgreSQL 9.6+ while leveraging
-- PostgreSQL-specific features to thoroughly test Virtual Thread behavior.

-- Drop tables if they exist to ensure clean setup
DROP TABLE IF EXISTS vt_child_table CASCADE;
DROP TABLE IF EXISTS vt_parent_table CASCADE;
DROP TABLE IF EXISTS vt_large_object_test CASCADE;
DROP TABLE IF EXISTS vt_batch_test CASCADE;
DROP TABLE IF EXISTS vt_index_test CASCADE;
DROP TABLE IF EXISTS vt_transaction_test CASCADE;
DROP TABLE IF EXISTS vt_concurrent_test CASCADE;
DROP TABLE IF EXISTS vt_array_test CASCADE;
DROP TABLE IF EXISTS vt_notification_test CASCADE;

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

-- Table for testing PostgreSQL array operations with Virtual Threads
-- Array operations can be complex and might cause thread pinning
CREATE TABLE vt_array_test (
    id SERIAL PRIMARY KEY,
    int_array INTEGER[],
    text_array TEXT[],
    nested_array TEXT[][],
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create GIN index for array operations
CREATE INDEX idx_array_test_int_array ON vt_array_test USING GIN (int_array);
CREATE INDEX idx_array_test_text_array ON vt_array_test USING GIN (text_array);

-- Table for testing PostgreSQL notification system with Virtual Threads
-- LISTEN/NOTIFY can interact with thread scheduling
CREATE TABLE vt_notification_test (
    id SERIAL PRIMARY KEY,
    channel VARCHAR(100) NOT NULL,
    payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create index on notification channel
CREATE INDEX idx_notification_test_channel ON vt_notification_test (channel);

-- Add comments to explain the purpose of this schema
COMMENT ON TABLE vt_parent_table IS 'Table for testing basic CRUD operations with Virtual Threads';
COMMENT ON TABLE vt_child_table IS 'Table for testing foreign key relationships with Virtual Threads';
COMMENT ON TABLE vt_large_object_test IS 'Table for testing large object operations that might cause thread pinning';
COMMENT ON TABLE vt_batch_test IS 'Table for testing batch operations with Virtual Threads';
COMMENT ON TABLE vt_index_test IS 'Table for testing GIN index operations with Virtual Threads';
COMMENT ON TABLE vt_transaction_test IS 'Table for testing transaction operations with Virtual Threads';
COMMENT ON TABLE vt_concurrent_test IS 'Table for testing concurrent operations with Virtual Threads';
COMMENT ON TABLE vt_array_test IS 'Table for testing PostgreSQL array operations with Virtual Threads';
COMMENT ON TABLE vt_notification_test IS 'Table for testing PostgreSQL notification system with Virtual Threads';

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

-- Add function to test batch operations with Virtual Threads
CREATE OR REPLACE FUNCTION vt_batch_process(p_batch_id INTEGER)
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    -- Simulate some processing time that might cause thread pinning
    PERFORM pg_sleep(0.005);
    
    UPDATE vt_batch_test
    SET processed = TRUE, processed_at = CURRENT_TIMESTAMP
    WHERE batch_id = p_batch_id AND processed = FALSE;
    
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;

-- Add function to test JSONB operations with Virtual Threads
CREATE OR REPLACE FUNCTION vt_jsonb_test(p_id INTEGER, p_data JSONB)
RETURNS JSONB AS $$
DECLARE
    v_result JSONB;
BEGIN
    -- Simulate some processing time that might cause thread pinning
    PERFORM pg_sleep(0.008);
    
    -- Update the document and return the merged result
    UPDATE vt_index_test
    SET document = document || p_data
    WHERE id = p_id
    RETURNING document INTO v_result;
    
    RETURN v_result;
END;
$$ LANGUAGE plpgsql;

-- Add function to test concurrent operations with Virtual Threads
CREATE OR REPLACE FUNCTION vt_try_acquire_lock(
    p_resource_id VARCHAR,
    p_owner VARCHAR,
    p_timeout_seconds INTEGER
) RETURNS BOOLEAN AS $$
DECLARE
    v_id INTEGER;
    v_updated BOOLEAN;
BEGIN
    -- Simulate some processing time that might cause thread pinning
    PERFORM pg_sleep(0.005);
    
    -- First check if lock is available
    SELECT id INTO v_id
    FROM vt_concurrent_test
    WHERE resource_id = p_resource_id
      AND (lock_owner IS NULL OR lock_expires_at < CURRENT_TIMESTAMP)
    FOR UPDATE SKIP LOCKED;
    
    IF v_id IS NOT NULL THEN
        -- Try to acquire the lock with optimistic locking using version
        UPDATE vt_concurrent_test
        SET lock_owner = p_owner,
            lock_acquired_at = CURRENT_TIMESTAMP,
            lock_expires_at = CURRENT_TIMESTAMP + (p_timeout_seconds || ' seconds')::INTERVAL,
            version = version + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = v_id
          AND (lock_owner IS NULL OR lock_expires_at < CURRENT_TIMESTAMP);
        
        GET DIAGNOSTICS v_updated = ROW_COUNT;
        RETURN v_updated > 0;
    END IF;
    
    RETURN FALSE;
END;
$$ LANGUAGE plpgsql;

-- Add function to test array operations with Virtual Threads
CREATE OR REPLACE FUNCTION vt_array_test_function(
    p_id INTEGER,
    p_values INTEGER[]
) RETURNS INTEGER[] AS $$
DECLARE
    v_result INTEGER[];
BEGIN
    -- Simulate some processing time that might cause thread pinning
    PERFORM pg_sleep(0.007);
    
    -- Update the array and return the result
    UPDATE vt_array_test
    SET int_array = array_cat(int_array, p_values)
    WHERE id = p_id
    RETURNING int_array INTO v_result;
    
    RETURN v_result;
END;
$$ LANGUAGE plpgsql;

-- Add function to test notification with Virtual Threads
CREATE OR REPLACE FUNCTION vt_send_notification(
    p_channel VARCHAR,
    p_payload TEXT
) RETURNS INTEGER AS $$
DECLARE
    v_id INTEGER;
BEGIN
    -- Insert notification record
    INSERT INTO vt_notification_test(channel, payload)
    VALUES (p_channel, p_payload)
    RETURNING id INTO v_id;
    
    -- Send notification
    PERFORM pg_notify(p_channel, p_payload);
    
    RETURN v_id;
END;
$$ LANGUAGE plpgsql;

-- Add function to test transaction isolation with Virtual Threads
CREATE OR REPLACE FUNCTION vt_test_transaction_isolation(
    p_account_id VARCHAR,
    p_amount NUMERIC
) RETURNS BOOLEAN AS $$
DECLARE
    v_balance NUMERIC;
    v_new_balance NUMERIC;
    v_transaction_id INTEGER;
BEGIN
    -- Simulate some processing time that might cause thread pinning
    PERFORM pg_sleep(0.01);
    
    -- Get current balance with row lock
    SELECT balance INTO v_balance
    FROM vt_transaction_test
    WHERE account_id = p_account_id
    ORDER BY transaction_date DESC
    LIMIT 1
    FOR UPDATE;
    
    -- Calculate new balance
    v_new_balance := v_balance + p_amount;
    
    -- Insert new transaction
    INSERT INTO vt_transaction_test(
        account_id,
        transaction_type,
        amount,
        balance,
        description,
        status
    ) VALUES (
        p_account_id,
        CASE WHEN p_amount >= 0 THEN 'deposit' ELSE 'withdrawal' END,
        ABS(p_amount),
        v_new_balance,
        'Transaction via vt_test_transaction_isolation',
        'completed'
    ) RETURNING id INTO v_transaction_id;
    
    RETURN v_transaction_id IS NOT NULL;
END;
$$ LANGUAGE plpgsql;

-- Add function to test full text search with Virtual Threads
CREATE OR REPLACE FUNCTION vt_full_text_search(
    p_query TEXT
) RETURNS TABLE (id INTEGER, full_text TEXT, rank REAL) AS $$
BEGIN
    -- Simulate some processing time that might cause thread pinning
    PERFORM pg_sleep(0.015);
    
    RETURN QUERY
    SELECT vt.id, vt.full_text, ts_rank(to_tsvector('english', vt.full_text), to_tsquery('english', p_query)) AS rank
    FROM vt_index_test vt
    WHERE to_tsvector('english', vt.full_text) @@ to_tsquery('english', p_query)
    ORDER BY rank DESC;
END;
$$ LANGUAGE plpgsql;

-- Add function to test cursor operations with Virtual Threads
-- Cursors can cause thread pinning if not handled properly
CREATE OR REPLACE FUNCTION vt_cursor_test(
    p_batch_size INTEGER
) RETURNS SETOF vt_parent_table AS $$
DECLARE
    v_cursor CURSOR FOR SELECT * FROM vt_parent_table ORDER BY id;
    v_row vt_parent_table%ROWTYPE;
    v_counter INTEGER := 0;
BEGIN
    OPEN v_cursor;
    
    LOOP
        FETCH v_cursor INTO v_row;
        EXIT WHEN NOT FOUND OR v_counter >= p_batch_size;
        
        -- Simulate some processing time that might cause thread pinning
        PERFORM pg_sleep(0.002);
        
        v_counter := v_counter + 1;
        RETURN NEXT v_row;
    END LOOP;
    
    CLOSE v_cursor;
    RETURN;
END;
$$ LANGUAGE plpgsql;

-- Add function to test advisory locks with Virtual Threads
-- Advisory locks can interact with thread scheduling
CREATE OR REPLACE FUNCTION vt_advisory_lock_test(
    p_key BIGINT,
    p_timeout_seconds INTEGER
) RETURNS BOOLEAN AS $$
DECLARE
    v_acquired BOOLEAN;
BEGIN
    -- Try to acquire advisory lock with timeout
    SELECT pg_try_advisory_lock(p_key) INTO v_acquired;
    
    IF v_acquired THEN
        -- Simulate some processing time that might cause thread pinning
        PERFORM pg_sleep(LEAST(p_timeout_seconds, 5) * 0.1);
        
        -- Release the lock
        PERFORM pg_advisory_unlock(p_key);
    END IF;
    
    RETURN v_acquired;
END;
$$ LANGUAGE plpgsql;