-- ============================================================================
-- Sonatype Nexus Repository Manager Database Migration Test Fixture
-- Compatible with Java 21 JDBC drivers and Virtual Thread testing
-- ============================================================================

-- This SQL script provides test fixtures for validating the Nexus upgrade framework's
-- database migration capabilities. It includes table creation statements, sample data,
-- and migration scenarios to test the framework's ability to handle database schema
-- changes during upgrades.

-- The script is designed to be compatible with both H2 and PostgreSQL databases,
-- and specifically supports testing with Java 21 JDBC drivers and Virtual Threads.

-- ============================================================================
-- SECTION 1: Basic Test Tables
-- ============================================================================

-- Example table used in TestMigrationStep
-- This is the primary test table for basic migration testing
CREATE TABLE IF NOT EXISTS example (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);

-- Insert test data into example table
INSERT INTO example (id, name, description) VALUES 
(1, 'fawkes', 'Test data for basic migration scenario');

-- Skipped table used in SkippedMigrationStep
-- This table is used to test migration steps that are skipped
CREATE TABLE IF NOT EXISTS skipped (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) DEFAULT 'pending'
);

-- ============================================================================
-- SECTION 2: Virtual Thread Testing Tables
-- ============================================================================

-- Table for testing concurrent access with Virtual Threads
-- This table is designed to test database operations under high concurrency
-- with Java 21 Virtual Threads
CREATE TABLE IF NOT EXISTS vthread_test (
    id INT PRIMARY KEY,
    thread_name VARCHAR(255) NOT NULL,
    operation_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    counter BIGINT DEFAULT 0,
    status VARCHAR(50) DEFAULT 'created'
);

-- Table for tracking Virtual Thread performance metrics
-- Used to collect performance data when running with Virtual Threads vs platform threads
CREATE TABLE IF NOT EXISTS vthread_metrics (
    id INT PRIMARY KEY,
    test_name VARCHAR(255) NOT NULL,
    thread_type VARCHAR(50) NOT NULL, -- 'virtual' or 'platform'
    thread_count INT NOT NULL,
    operation_count INT NOT NULL,
    total_duration_ms BIGINT NOT NULL,
    avg_duration_ms DOUBLE PRECISION NOT NULL,
    max_duration_ms BIGINT NOT NULL,
    min_duration_ms BIGINT NOT NULL,
    test_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- SECTION 3: Migration Test Tables
-- ============================================================================

-- Table for testing schema evolution
-- This table will be modified by migration steps to test schema changes
CREATE TABLE IF NOT EXISTS evolving_schema (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    -- Additional columns will be added by migration steps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert test data into evolving_schema table
INSERT INTO evolving_schema (id, name) VALUES 
(1, 'initial_schema'),
(2, 'will_be_migrated');

-- Table for testing distributed events
-- Used in DistributedEventsUpgradeTest
CREATE TABLE IF NOT EXISTS distributed_events (
    id INT PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    node_id VARCHAR(255),
    status VARCHAR(50) DEFAULT 'pending'
);

-- Insert test data into distributed_events table
INSERT INTO distributed_events (id, event_type, payload, node_id) VALUES 
(1, 'TEST_EVENT', '{"test":"data"}', 'node1'),
(2, 'MIGRATION_EVENT', '{"migration":"test"}', 'node2');

-- ============================================================================
-- SECTION 4: JDBC Connection Testing with Java 21
-- ============================================================================

-- Table for testing JDBC connection properties with Java 21
-- This table helps validate that JDBC connections work correctly with Java 21
CREATE TABLE IF NOT EXISTS jdbc_connection_test (
    id INT PRIMARY KEY,
    driver_name VARCHAR(255) NOT NULL,
    driver_version VARCHAR(50) NOT NULL,
    java_version VARCHAR(50) NOT NULL,
    connection_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    connection_properties TEXT,
    is_virtual_thread BOOLEAN DEFAULT FALSE
);

-- ============================================================================
-- SECTION 5: Large Dataset for Performance Testing
-- ============================================================================

-- Table for testing performance with large datasets
-- This is used to validate performance differences between platform and virtual threads
CREATE TABLE IF NOT EXISTS large_dataset (
    id INT PRIMARY KEY,
    data_value VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    batch_id INT NOT NULL,
    processed BOOLEAN DEFAULT FALSE
);

-- Insert a small set of test data (more would be added programmatically in tests)
INSERT INTO large_dataset (id, data_value, batch_id) VALUES 
(1, 'Sample data value 1 for performance testing with virtual threads', 1),
(2, 'Sample data value 2 for performance testing with virtual threads', 1),
(3, 'Sample data value 3 for performance testing with virtual threads', 1);

-- ============================================================================
-- SECTION 6: Blob and Clob Testing with Java 21 JDBC
-- ============================================================================

-- Table for testing BLOB and CLOB handling with Java 21 JDBC drivers
-- This validates that large object handling works correctly with Java 21
CREATE TABLE IF NOT EXISTS blob_test (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    binary_data BLOB,
    text_data CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- SECTION 7: Transaction Testing with Virtual Threads
-- ============================================================================

-- Tables for testing transaction behavior with Virtual Threads
-- These tables help validate that transactions work correctly with Virtual Threads
CREATE TABLE IF NOT EXISTS transaction_master (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) DEFAULT 'pending'
);

CREATE TABLE IF NOT EXISTS transaction_detail (
    id INT PRIMARY KEY,
    master_id INT NOT NULL,
    detail_data VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (master_id) REFERENCES transaction_master(id)
);

-- ============================================================================
-- SECTION 8: Compatibility Functions and Procedures
-- ============================================================================

-- H2-specific compatibility function
-- This function is only created in H2 databases
-- In PostgreSQL this will be skipped due to the IF condition
CREATE ALIAS IF EXISTS get_database_type AS '
    String getDatabaseType() {
        return "H2";
    }
';

-- PostgreSQL-compatible function that works in both H2 and PostgreSQL
-- This function helps identify which database is being used
CREATE OR REPLACE FUNCTION get_db_version() RETURNS VARCHAR AS $$
BEGIN
    RETURN CAST(CURRENT_SETTING('server_version') AS VARCHAR);
EXCEPTION WHEN OTHERS THEN
    RETURN 'H2';
END;
$$ LANGUAGE PLPGSQL;

-- ============================================================================
-- SECTION 9: Test Data for Flyway Migration Testing
-- ============================================================================

-- This section is intentionally left empty as Flyway will create its own
-- flyway_schema_history table when migrations are applied.
-- The UpgradeManagerImplTest will verify the contents of this table
-- after migrations are applied.

-- ============================================================================
-- END OF SCRIPT
-- ============================================================================