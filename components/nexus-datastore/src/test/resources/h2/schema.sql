-- H2 database schema for Virtual Thread testing with Java 21
-- This schema is designed to test JDBC operations with Java 21 Virtual Threads
-- focusing on scenarios that might cause thread pinning in traditional implementations

-- Drop tables if they exist to ensure clean setup
DROP TABLE IF EXISTS test_relationship;
DROP TABLE IF EXISTS test_entity;
DROP TABLE IF EXISTS test_large_object;
DROP TABLE IF EXISTS test_batch;
DROP TABLE IF EXISTS test_index;
DROP TABLE IF EXISTS test_transaction;
DROP TABLE IF EXISTS test_concurrent;

-- Table for testing basic CRUD operations with Virtual Threads
CREATE TABLE test_entity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description CLOB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    active BOOLEAN DEFAULT TRUE,
    priority INTEGER DEFAULT 0,
    data JSON
);

-- Create index on name to test index operations with Virtual Threads
CREATE INDEX idx_test_entity_name ON test_entity(name);

-- Table with foreign key relationship to test transaction integrity with Virtual Threads
CREATE TABLE test_relationship (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    value NUMERIC(10, 2),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    CONSTRAINT fk_entity
        FOREIGN KEY (entity_id)
        REFERENCES test_entity (id)
        ON DELETE CASCADE
);

-- Create index on foreign key to test index operations with Virtual Threads
CREATE INDEX idx_test_relationship_entity_id ON test_relationship(entity_id);

-- Table for testing large object operations with Virtual Threads
-- BLOB and CLOB columns can cause thread pinning in traditional implementations
CREATE TABLE test_large_object (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    binary_data BLOB,
    text_data CLOB,
    description VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
);

-- Create index on description to test index operations with large objects
CREATE INDEX idx_test_large_object_description ON test_large_object(description);

-- Table for testing batch operations with Virtual Threads
CREATE TABLE test_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    sequence_num INTEGER NOT NULL,
    data VARCHAR(500),
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
);

-- Create indexes for batch processing
CREATE INDEX idx_test_batch_batch_id ON test_batch(batch_id);
CREATE INDEX idx_test_batch_processed ON test_batch(processed);
CREATE INDEX idx_test_batch_batch_seq ON test_batch(batch_id, sequence_num);

-- Table for testing index operations with Virtual Threads
CREATE TABLE test_index (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tags VARCHAR(1000), -- Storing as comma-separated values
    keywords VARCHAR(1000), -- Storing as comma-separated values
    document JSON,
    full_text CLOB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
);

-- Create indexes for text search operations
CREATE INDEX idx_test_index_tags ON test_index(tags);
CREATE INDEX idx_test_index_full_text ON test_index(full_text(255)); -- Index first 255 chars

-- Table for testing transaction operations with Virtual Threads
CREATE TABLE test_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
CREATE INDEX idx_test_transaction_account_id ON test_transaction(account_id);
CREATE INDEX idx_test_transaction_status ON test_transaction(status);
CREATE INDEX idx_test_transaction_date ON test_transaction(transaction_date);

-- Table for testing concurrent operations with Virtual Threads
CREATE TABLE test_concurrent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(50) NOT NULL,
    lock_owner VARCHAR(100),
    lock_acquired_at TIMESTAMP WITH TIME ZONE,
    lock_expires_at TIMESTAMP WITH TIME ZONE,
    data JSON,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP()
);

-- Create unique index on resource_id to test concurrent access patterns
CREATE UNIQUE INDEX idx_test_concurrent_resource_id ON test_concurrent(resource_id);
-- Create index on lock expiration to test time-based operations
CREATE INDEX idx_test_concurrent_lock_expires ON test_concurrent(lock_expires_at);

-- Add comments to explain the purpose of this schema
COMMENT ON TABLE test_entity IS 'Table for testing basic CRUD operations with Virtual Threads';
COMMENT ON TABLE test_relationship IS 'Table for testing foreign key relationships with Virtual Threads';
COMMENT ON TABLE test_large_object IS 'Table for testing large object operations that might cause thread pinning';
COMMENT ON TABLE test_batch IS 'Table for testing batch operations with Virtual Threads';
COMMENT ON TABLE test_index IS 'Table for testing index operations with Virtual Threads';
COMMENT ON TABLE test_transaction IS 'Table for testing transaction operations with Virtual Threads';
COMMENT ON TABLE test_concurrent IS 'Table for testing concurrent operations with Virtual Threads';

-- Add function to test procedural operations with Virtual Threads
-- H2 doesn't support pg_sleep, so we use SLEEP instead
CREATE ALIAS test_function AS $$
    @CODE
    import java.sql.*;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static ResultSet testFunction(Connection conn, long id) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT id, name, description FROM test_entity WHERE id = ?");
        stmt.setLong(1, id);
        return stmt.executeQuery();
    }
$$;

-- Add function to test batch operations with Virtual Threads
CREATE ALIAS batch_process AS $$
    @CODE
    import java.sql.*;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static int processBatch(Connection conn, long batchId) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        PreparedStatement updateStmt = conn.prepareStatement(
            "UPDATE test_batch SET processed = TRUE, processed_at = CURRENT_TIMESTAMP() " +
            "WHERE batch_id = ? AND processed = FALSE");
        updateStmt.setLong(1, batchId);
        return updateStmt.executeUpdate();
    }
$$;

-- Add function to test BLOB/CLOB operations with Virtual Threads
CREATE ALIAS large_object_test AS $$
    @CODE
    import java.sql.*;
    import java.io.*;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static boolean testLargeObject(Connection conn, long id, byte[] data, String text) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        PreparedStatement stmt = conn.prepareStatement(
            "UPDATE test_large_object SET binary_data = ?, text_data = ? WHERE id = ?");
        
        // Set BLOB data
        stmt.setBytes(1, data);
        
        // Set CLOB data
        stmt.setString(2, text);
        
        stmt.setLong(3, id);
        return stmt.executeUpdate() > 0;
    }
$$;

-- Add function to test concurrent operations with Virtual Threads
CREATE ALIAS try_acquire_lock AS $$
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
            "SELECT id FROM test_concurrent " +
            "WHERE resource_id = ? AND (lock_owner IS NULL OR lock_expires_at < CURRENT_TIMESTAMP())");
        checkStmt.setString(1, resourceId);
        ResultSet rs = checkStmt.executeQuery();
        
        if (rs.next()) {
            long id = rs.getLong(1);
            rs.close();
            
            // Try to acquire the lock with optimistic locking using version
            PreparedStatement updateStmt = conn.prepareStatement(
                "UPDATE test_concurrent " +
                "SET lock_owner = ?, lock_acquired_at = CURRENT_TIMESTAMP(), " +
                "lock_expires_at = DATEADD('SECOND', ?, CURRENT_TIMESTAMP()), " +
                "version = version + 1, updated_at = CURRENT_TIMESTAMP() " +
                "WHERE id = ? AND (lock_owner IS NULL OR lock_expires_at < CURRENT_TIMESTAMP())");
            updateStmt.setString(1, owner);
            updateStmt.setInt(2, timeoutSeconds);
            updateStmt.setLong(3, id);
            
            return updateStmt.executeUpdate() > 0;
        }
        
        return false;
    }
$$;

-- Add function to test transaction isolation with Virtual Threads
CREATE ALIAS test_transaction_isolation AS $$
    @CODE
    import java.sql.*;
    import java.math.BigDecimal;
    import java.util.concurrent.TimeUnit;
    
    @SuppressWarnings("unchecked")
    public static boolean testTransactionIsolation(Connection conn, String accountId, BigDecimal amount) throws SQLException {
        // Simulate some processing time that might cause thread pinning
        try {
            TimeUnit.MILLISECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Get current balance with row lock
        PreparedStatement balanceStmt = conn.prepareStatement(
            "SELECT balance FROM test_transaction " +
            "WHERE account_id = ? " +
            "ORDER BY transaction_date DESC LIMIT 1 " +
            "FOR UPDATE");
        balanceStmt.setString(1, accountId);
        ResultSet rs = balanceStmt.executeQuery();
        
        BigDecimal balance;
        if (rs.next()) {
            balance = rs.getBigDecimal(1);
        } else {
            // No existing balance, start with zero
            balance = BigDecimal.ZERO;
        }
        rs.close();
        
        // Calculate new balance
        BigDecimal newBalance = balance.add(amount);
        
        // Insert new transaction
        PreparedStatement insertStmt = conn.prepareStatement(
            "INSERT INTO test_transaction(" +
            "account_id, transaction_type, amount, balance, description, status" +
            ") VALUES (?, ?, ?, ?, ?, ?)");
        insertStmt.setString(1, accountId);
        insertStmt.setString(2, amount.compareTo(BigDecimal.ZERO) >= 0 ? "deposit" : "withdrawal");
        insertStmt.setBigDecimal(3, amount.abs());
        insertStmt.setBigDecimal(4, newBalance);
        insertStmt.setString(5, "Transaction via test_transaction_isolation");
        insertStmt.setString(6, "completed");
        
        return insertStmt.executeUpdate() > 0;
    }
$$;