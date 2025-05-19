-- H2 database schema for Virtual Thread testing
-- This schema is designed to test JDBC operations with Java 21 Virtual Threads
-- focusing on scenarios that might cause thread pinning in traditional implementations
--
-- This schema is specifically created to test the following scenarios with Virtual Threads:
-- 1. Basic CRUD operations with various column types
-- 2. Foreign key relationships and referential integrity
-- 3. Large object (BLOB/CLOB) operations that traditionally cause thread pinning
-- 4. Batch processing operations
-- 5. Index operations and query performance
-- 6. Transaction processing with concurrent operations
-- 7. Optimistic and pessimistic locking patterns
--
-- The schema is designed to be compatible with H2 database while maintaining
-- functional equivalence with the PostgreSQL schema for the same tests.

-- Drop tables if they exist to ensure clean setup
DROP TABLE IF EXISTS vt_child_table;
DROP TABLE IF EXISTS vt_parent_table;
DROP TABLE IF EXISTS vt_large_object_test;
DROP TABLE IF EXISTS vt_batch_test;
DROP TABLE IF EXISTS vt_index_test;
DROP TABLE IF EXISTS vt_transaction_test;
DROP TABLE IF EXISTS vt_concurrent_test;

-- Create domain for JSON compatibility with PostgreSQL
CREATE DOMAIN IF NOT EXISTS JSONB AS JSON;

-- Table for testing basic CRUD operations with Virtual Threads
CREATE TABLE vt_parent_table (
    id IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description CLOB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    active BOOLEAN DEFAULT TRUE,
    priority INTEGER DEFAULT 0,
    data JSONB
);

-- Table with foreign key relationship to test transaction integrity with Virtual Threads
CREATE TABLE vt_child_table (
    id IDENTITY PRIMARY KEY,
    parent_id INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    value NUMERIC(10, 2),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    CONSTRAINT fk_parent
        FOREIGN KEY (parent_id)
        REFERENCES vt_parent_table (id)
        ON DELETE CASCADE
);

-- Create index on foreign key to test index operations with Virtual Threads
CREATE INDEX idx_child_parent_id ON vt_child_table (parent_id);

-- Table for testing large object operations with Virtual Threads
-- BLOB and CLOB columns can cause thread pinning in traditional implementations
CREATE TABLE vt_large_object_test (
    id IDENTITY PRIMARY KEY,
    binary_data BLOB,
    text_data CLOB,
    description VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
);

-- Create indexes on large object columns to test different index operations
CREATE INDEX idx_large_object_description ON vt_large_object_test (description);
-- Hash index equivalent in H2
CREATE HASH INDEX idx_large_object_id_hash ON vt_large_object_test (id);

-- Table for testing batch operations with Virtual Threads
CREATE TABLE vt_batch_test (
    id IDENTITY PRIMARY KEY,
    batch_id INTEGER NOT NULL,
    sequence_num INTEGER NOT NULL,
    data VARCHAR(500),
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
);

-- Create indexes for batch processing
CREATE INDEX idx_batch_test_batch_id ON vt_batch_test (batch_id);
CREATE INDEX idx_batch_test_processed ON vt_batch_test (processed);
CREATE INDEX idx_batch_test_batch_seq ON vt_batch_test (batch_id, sequence_num);

-- Table for testing index operations with Virtual Threads
-- H2 doesn't support GIN indexes, so we use standard indexes instead
CREATE TABLE vt_index_test (
    id IDENTITY PRIMARY KEY,
    tags VARCHAR(1000), -- Storing as comma-separated values instead of array
    keywords VARCHAR(1000), -- Storing as comma-separated values instead of array
    document JSONB,
    full_text CLOB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
);

-- Create indexes for text search operations
CREATE INDEX idx_index_test_tags ON vt_index_test (tags);
CREATE INDEX idx_index_test_full_text ON vt_index_test (full_text(255)); -- Index first 255 chars

-- Table for testing transaction operations with Virtual Threads
CREATE TABLE vt_transaction_test (
    id IDENTITY PRIMARY KEY,
    account_id VARCHAR(50) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    balance NUMERIC(15, 2) NOT NULL,
    description CLOB,
    transaction_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    status VARCHAR(20) DEFAULT 'pending',
    CONSTRAINT chk_transaction_type CHECK (transaction_type IN ('deposit', 'withdrawal', 'transfer'))
);

-- Create indexes for transaction processing
CREATE INDEX idx_transaction_test_account_id ON vt_transaction_test (account_id);
CREATE INDEX idx_transaction_test_status ON vt_transaction_test (status);
CREATE INDEX idx_transaction_test_date ON vt_transaction_test (transaction_date);

-- Table for testing concurrent operations with Virtual Threads
CREATE TABLE vt_concurrent_test (
    id IDENTITY PRIMARY KEY,
    resource_id VARCHAR(50) NOT NULL,
    lock_owner VARCHAR(100),
    lock_acquired_at TIMESTAMP WITH TIME ZONE,
    lock_expires_at TIMESTAMP WITH TIME ZONE,
    data JSONB,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
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
COMMENT ON TABLE vt_index_test IS 'Table for testing index operations with Virtual Threads';
COMMENT ON TABLE vt_transaction_test IS 'Table for testing transaction operations with Virtual Threads';
COMMENT ON TABLE vt_concurrent_test IS 'Table for testing concurrent operations with Virtual Threads';

-- Add function to test procedural operations with Virtual Threads
-- H2 doesn't support pg_sleep, so we use SLEEP instead
CREATE ALIAS vt_test_function FOR """
    @CODE
    import java.sql.*;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static ResultSet testFunction(Connection conn, int id) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT id, name, description FROM vt_parent_table WHERE id = ?");
        stmt.setInt(1, id);
        return stmt.executeQuery();
    }
""";

-- Add function to test batch operations with Virtual Threads
CREATE ALIAS vt_batch_process FOR """
    @CODE
    import java.sql.*;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static int processBatch(Connection conn, int batchId) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        PreparedStatement updateStmt = conn.prepareStatement(
            "UPDATE vt_batch_test SET processed = TRUE, processed_at = CURRENT_TIMESTAMP() " +
            "WHERE batch_id = ? AND processed = FALSE");
        updateStmt.setInt(1, batchId);
        return updateStmt.executeUpdate();
    }
""";

-- Add function to test BLOB/CLOB operations with Virtual Threads
CREATE ALIAS vt_large_object_test FOR """
    @CODE
    import java.sql.*;
    import java.io.*;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static boolean testLargeObject(Connection conn, int id, byte[] data, String text) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        PreparedStatement stmt = conn.prepareStatement(
            "UPDATE vt_large_object_test SET binary_data = ?, text_data = ? WHERE id = ?");
        
        // Set BLOB data
        stmt.setBytes(1, data);
        
        // Set CLOB data
        stmt.setString(2, text);
        
        stmt.setInt(3, id);
        return stmt.executeUpdate() > 0;
    }
""";

-- Add function to test concurrent operations with Virtual Threads
CREATE ALIAS vt_try_acquire_lock FOR """
    @CODE
    import java.sql.*;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static boolean tryAcquireLock(Connection conn, String resourceId, String owner, int timeoutSeconds) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // First check if lock is available
        PreparedStatement checkStmt = conn.prepareStatement(
            "SELECT id FROM vt_concurrent_test " +
            "WHERE resource_id = ? AND (lock_owner IS NULL OR lock_expires_at < CURRENT_TIMESTAMP())");
        checkStmt.setString(1, resourceId);
        ResultSet rs = checkStmt.executeQuery();
        
        if (rs.next()) {
            int id = rs.getInt(1);
            rs.close();
            
            // Try to acquire the lock with optimistic locking using version
            PreparedStatement updateStmt = conn.prepareStatement(
                "UPDATE vt_concurrent_test " +
                "SET lock_owner = ?, lock_acquired_at = CURRENT_TIMESTAMP(), " +
                "lock_expires_at = DATEADD('SECOND', ?, CURRENT_TIMESTAMP()), " +
                "version = version + 1, updated_at = CURRENT_TIMESTAMP() " +
                "WHERE id = ? AND (lock_owner IS NULL OR lock_expires_at < CURRENT_TIMESTAMP())");
            updateStmt.setString(1, owner);
            updateStmt.setInt(2, timeoutSeconds);
            updateStmt.setInt(3, id);
            
            return updateStmt.executeUpdate() > 0;
        }
        
        return false;
    }
""";