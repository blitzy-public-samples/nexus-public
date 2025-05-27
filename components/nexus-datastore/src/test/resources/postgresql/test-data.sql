-- PostgreSQL Test Data for Virtual Thread Testing
-- This script populates test tables with data designed to validate database operations with Virtual Threads
-- Compatible with PostgreSQL JDBC driver 42.6.0+ which has improved Virtual Thread support
-- For use with Java 21 Virtual Thread testing to validate non-blocking I/O operations

-- Clear existing test data if any
TRUNCATE TABLE vt_test_entity CASCADE;
TRUNCATE TABLE vt_test_relationship CASCADE;
TRUNCATE TABLE vt_test_blob CASCADE;
TRUNCATE TABLE vt_test_transaction CASCADE;
TRUNCATE TABLE vt_test_batch CASCADE;
TRUNCATE TABLE vt_test_connection_pool CASCADE;
TRUNCATE TABLE vt_test_thread_pinning CASCADE;

-- ============================================================================
-- Basic entity data for read operations with Virtual Threads
-- These records are used to test basic CRUD operations with Virtual Threads
-- ============================================================================

INSERT INTO vt_test_entity (id, name, description, created, last_updated, active) VALUES
(1, 'Entity-1', 'Test entity for basic read operations with Virtual Threads', NOW(), NOW(), true),
(2, 'Entity-2', 'Test entity with medium-length description to test string handling with Virtual Threads', NOW(), NOW(), true),
(3, 'Entity-3', 'Test entity with a longer description to ensure that Virtual Threads properly handle larger text fields without pinning. This description is intentionally verbose to test buffer handling.', NOW(), NOW(), true),
(4, 'Entity-4', 'Inactive test entity', NOW(), NOW(), false),
(5, 'Entity-5', 'Another active test entity', NOW(), NOW(), true),
(6, 'Entity-6', 'Test entity for update operations', NOW(), NOW(), true),
(7, 'Entity-7', 'Test entity for delete operations', NOW(), NOW(), true),
(8, 'Entity-8', 'Test entity for transaction testing', NOW(), NOW(), true),
(9, 'Entity-9', 'Test entity for batch operations', NOW(), NOW(), true),
(10, 'Entity-10', 'Test entity for complex queries', NOW(), NOW(), true);

-- Add more entities for volume testing (useful for benchmarking Virtual Thread performance)
INSERT INTO vt_test_entity (id, name, description, created, last_updated, active)
SELECT i, 
       'Volume-Entity-' || i, 
       'Volume test entity ' || i || ' for benchmarking Virtual Thread performance with larger datasets', 
       NOW(), 
       NOW(), 
       (i % 2 = 0) -- alternating active status
FROM generate_series(11, 1000) AS i;

-- ============================================================================
-- Relationship data for testing join operations with Virtual Threads
-- These records establish relationships between entities to test join performance
-- ============================================================================

INSERT INTO vt_test_relationship (id, parent_id, child_id, relationship_type, created) VALUES
(1, 1, 2, 'PARENT_CHILD', NOW()),
(2, 1, 3, 'PARENT_CHILD', NOW()),
(3, 2, 4, 'PARENT_CHILD', NOW()),
(4, 2, 5, 'PARENT_CHILD', NOW()),
(5, 3, 6, 'PARENT_CHILD', NOW()),
(6, 3, 7, 'PARENT_CHILD', NOW()),
(7, 8, 9, 'REFERENCE', NOW()),
(8, 8, 10, 'REFERENCE', NOW()),
(9, 9, 10, 'DEPENDENCY', NOW()),
(10, 1, 10, 'COMPLEX', NOW());

-- Add more relationships for complex join testing
INSERT INTO vt_test_relationship (id, parent_id, child_id, relationship_type, created)
SELECT 10 + i, 
       (i % 990) + 11, -- parent from volume entities
       ((i + 100) % 990) + 11, -- child from volume entities (different from parent)
       CASE (i % 3) 
           WHEN 0 THEN 'PARENT_CHILD' 
           WHEN 1 THEN 'REFERENCE' 
           ELSE 'DEPENDENCY' 
       END, -- mix of relationship types
       NOW()
FROM generate_series(1, 2000) AS i;

-- ============================================================================
-- Blob data for testing I/O operations with Virtual Threads
-- These records simulate binary content to test I/O performance with Virtual Threads
-- ============================================================================

INSERT INTO vt_test_blob (id, entity_id, content_type, size_bytes, blob_data, created) VALUES
(1, 1, 'application/json', 256, repeat('a', 256), NOW()),
(2, 2, 'application/xml', 512, repeat('b', 512), NOW()),
(3, 3, 'text/plain', 1024, repeat('c', 1024), NOW()),
(4, 4, 'application/octet-stream', 2048, repeat('d', 2048), NOW()),
(5, 5, 'image/png', 4096, repeat('e', 4096), NOW());

-- Add larger blobs to test Virtual Thread I/O performance with bigger payloads
INSERT INTO vt_test_blob (id, entity_id, content_type, size_bytes, blob_data, created) VALUES
(6, 6, 'application/pdf', 8192, repeat('f', 8192), NOW()),
(7, 7, 'application/zip', 16384, repeat('g', 16384), NOW()),
(8, 8, 'video/mp4', 32768, repeat('h', 32768), NOW()),
(9, 9, 'application/java-archive', 65536, repeat('i', 65536), NOW()),
(10, 10, 'application/x-executable', 131072, repeat('j', 131072), NOW());

-- ============================================================================
-- Transaction test data for testing isolation levels with Virtual Threads
-- These records are used to test transaction behavior with Virtual Threads
-- ============================================================================

INSERT INTO vt_test_transaction (id, entity_id, operation_type, isolation_level, status, started, completed) VALUES
(1, 1, 'READ', 'READ_COMMITTED', 'COMPLETED', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '59 minutes'),
(2, 2, 'WRITE', 'READ_COMMITTED', 'COMPLETED', NOW() - INTERVAL '50 minutes', NOW() - INTERVAL '49 minutes'),
(3, 3, 'READ', 'REPEATABLE_READ', 'COMPLETED', NOW() - INTERVAL '40 minutes', NOW() - INTERVAL '39 minutes'),
(4, 4, 'WRITE', 'REPEATABLE_READ', 'COMPLETED', NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '29 minutes'),
(5, 5, 'READ', 'SERIALIZABLE', 'COMPLETED', NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '19 minutes'),
(6, 6, 'WRITE', 'SERIALIZABLE', 'COMPLETED', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '9 minutes'),
(7, 7, 'READ', 'READ_UNCOMMITTED', 'FAILED', NOW() - INTERVAL '5 minutes', NOW() - INTERVAL '4 minutes'),
(8, 8, 'WRITE', 'READ_UNCOMMITTED', 'FAILED', NOW() - INTERVAL '3 minutes', NOW() - INTERVAL '2 minutes'),
(9, 9, 'READ', 'READ_COMMITTED', 'IN_PROGRESS', NOW() - INTERVAL '1 minute', NULL),
(10, 10, 'WRITE', 'SERIALIZABLE', 'IN_PROGRESS', NOW(), NULL);

-- ============================================================================
-- Batch operation test data for testing batch processing with Virtual Threads
-- These records are used to test batch processing performance with Virtual Threads
-- ============================================================================

INSERT INTO vt_test_batch (id, batch_id, sequence_num, payload, status, created) VALUES
(1, 'batch-1', 1, '{"operation": "create", "entityId": 1}', 'PENDING', NOW()),
(2, 'batch-1', 2, '{"operation": "create", "entityId": 2}', 'PENDING', NOW()),
(3, 'batch-1', 3, '{"operation": "create", "entityId": 3}', 'PENDING', NOW()),
(4, 'batch-1', 4, '{"operation": "create", "entityId": 4}', 'PENDING', NOW()),
(5, 'batch-1', 5, '{"operation": "create", "entityId": 5}', 'PENDING', NOW());

-- Add more batch records for volume testing
INSERT INTO vt_test_batch (id, batch_id, sequence_num, payload, status, created)
SELECT 5 + i, 
       'batch-' || (i / 100 + 2), -- group into batches of 100
       (i % 100) + 1, -- sequence within batch
       '{"operation": "' || 
           CASE (i % 4) 
               WHEN 0 THEN 'create' 
               WHEN 1 THEN 'read' 
               WHEN 2 THEN 'update' 
               ELSE 'delete' 
           END || 
       '", "entityId": ' || ((i % 990) + 11) || '}', -- reference volume entities
       CASE (i % 5) 
           WHEN 0 THEN 'PENDING' 
           WHEN 1 THEN 'IN_PROGRESS' 
           WHEN 2 THEN 'COMPLETED' 
           WHEN 3 THEN 'FAILED' 
           ELSE 'RETRYING' 
       END, -- mix of statuses
       NOW() - (INTERVAL '1 second' * (i % 3600)) -- spread over last hour
FROM generate_series(1, 10000) AS i;

-- ============================================================================
-- Add test data for specific Virtual Thread testing scenarios
-- ============================================================================

-- Test data for thread pinning detection
-- These records have large payloads that might cause thread pinning if not handled properly
-- Used to validate that PostgreSQL JDBC driver 42.6.0+ properly avoids thread pinning
INSERT INTO vt_test_blob (id, entity_id, content_type, size_bytes, blob_data, created) VALUES
(11, 1, 'application/octet-stream', 1048576, repeat('x', 1048576), NOW()), -- 1MB
(12, 2, 'application/octet-stream', 2097152, repeat('y', 2097152), NOW()), -- 2MB
(13, 3, 'application/octet-stream', 4194304, repeat('z', 4194304), NOW()); -- 4MB

-- Test data for thread pinning scenarios with synchronized blocks
-- These records are used to test operations that might cause thread pinning in older JDBC drivers
INSERT INTO vt_test_thread_pinning (id, operation_name, payload_size, expected_pinning, description) VALUES
(1, 'SYNCHRONIZED_READ', 1024, false, 'Read operation that used to cause pinning in older JDBC drivers'),
(2, 'SYNCHRONIZED_WRITE', 2048, false, 'Write operation that used to cause pinning in older JDBC drivers'),
(3, 'NATIVE_METHOD_CALL', 512, true, 'Operation involving native method calls that still causes pinning'),
(4, 'LOCK_CONTENTION', 256, false, 'Operation with lock contention that should not cause pinning with ReentrantLock'),
(5, 'LARGE_RESULT_SET', 1048576, false, 'Large result set processing that should not cause pinning with proper JDBC driver');

-- Test data for transaction timeout testing
-- These records simulate long-running transactions to test timeout handling with Virtual Threads
INSERT INTO vt_test_transaction (id, entity_id, operation_type, isolation_level, status, started, completed) VALUES
(11, 1, 'LONG_READ', 'READ_COMMITTED', 'TIMEOUT', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour'),
(12, 2, 'LONG_WRITE', 'SERIALIZABLE', 'TIMEOUT', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours');

-- Test data for concurrent operation testing
-- These records are used to test concurrent operations with Virtual Threads
INSERT INTO vt_test_entity (id, name, description, created, last_updated, active) VALUES
(1001, 'Concurrent-1', 'Test entity for concurrent read operations', NOW(), NOW(), true),
(1002, 'Concurrent-2', 'Test entity for concurrent write operations', NOW(), NOW(), true),
(1003, 'Concurrent-3', 'Test entity for concurrent read/write operations', NOW(), NOW(), true);

-- Add relationships for concurrent operation testing
INSERT INTO vt_test_relationship (id, parent_id, child_id, relationship_type, created) VALUES
(2001, 1001, 1002, 'CONCURRENT_TEST', NOW()),
(2002, 1002, 1003, 'CONCURRENT_TEST', NOW()),
(2003, 1003, 1001, 'CONCURRENT_TEST', NOW());

-- Test data for connection pooling validation with Virtual Threads
-- These records help test connection reuse patterns when using Virtual Threads
INSERT INTO vt_test_connection_pool (id, pool_name, min_size, max_size, idle_timeout_ms, connection_timeout_ms, validation_query) VALUES
(1, 'hikari-pool', 10, 100, 30000, 5000, 'SELECT 1'),
(2, 'tomcat-pool', 5, 50, 60000, 10000, 'SELECT 1'),
(3, 'virtual-thread-optimized-pool', 20, 200, 15000, 3000, 'SELECT 1');

-- Add connection usage patterns for testing ThreadLocal behavior with Virtual Threads
INSERT INTO vt_test_connection_pool (id, pool_name, min_size, max_size, idle_timeout_ms, connection_timeout_ms, validation_query) VALUES
(4, 'threadlocal-test-pool', 5, 50, 10000, 2000, 'SELECT 1'),
(5, 'connection-reuse-test-pool', 10, 100, 20000, 4000, 'SELECT 1');

-- ============================================================================
-- Additional test data for Virtual Thread performance comparison
-- These records are used to benchmark Virtual Threads vs Platform Threads
-- ============================================================================

-- Create test data for thread model comparison benchmarks
INSERT INTO vt_test_entity (id, name, description, created, last_updated, active)
SELECT 2000 + i, 
       'Benchmark-' || i, 
       'Entity for thread model performance comparison benchmarking', 
       NOW(), 
       NOW(), 
       true
FROM generate_series(1, 1000) AS i;

-- Create complex relationship structure for join performance testing
INSERT INTO vt_test_relationship (id, parent_id, child_id, relationship_type, created)
SELECT 3000 + i, 
       2000 + (i % 1000) + 1, 
       2000 + ((i + 1) % 1000) + 1, 
       'BENCHMARK', 
       NOW()
FROM generate_series(1, 5000) AS i;

-- Create test data for I/O-bound operation benchmarking
INSERT INTO vt_test_blob (id, entity_id, content_type, size_bytes, blob_data, created)
SELECT 100 + i, 
       2000 + (i % 1000) + 1, 
       'application/octet-stream', 
       1024 * (i % 10 + 1), -- Varying sizes from 1KB to 10KB
       repeat('b', 1024 * (i % 10 + 1)), 
       NOW()
FROM generate_series(1, 200) AS i;

-- Create test data for transaction isolation testing with Virtual Threads
INSERT INTO vt_test_transaction (id, entity_id, operation_type, isolation_level, status, started, completed)
SELECT 100 + i, 
       2000 + (i % 1000) + 1, 
       CASE (i % 4) 
           WHEN 0 THEN 'READ' 
           WHEN 1 THEN 'WRITE' 
           WHEN 2 THEN 'UPDATE' 
           ELSE 'DELETE' 
       END, 
       CASE (i % 3) 
           WHEN 0 THEN 'READ_COMMITTED' 
           WHEN 1 THEN 'REPEATABLE_READ' 
           ELSE 'SERIALIZABLE' 
       END, 
       'COMPLETED', 
       NOW() - INTERVAL '1 hour', 
       NOW() - INTERVAL '59 minutes'
FROM generate_series(1, 100) AS i;

-- ============================================================================
-- Commit the transaction to ensure all test data is saved
-- ============================================================================

COMMIT;

-- Note: This test data is designed for use with Java 21 Virtual Thread testing
-- It validates PostgreSQL JDBC driver 42.6.0+ compatibility with Virtual Threads
-- Use with -Djdk.tracePinnedThreads=full to detect any thread pinning issues