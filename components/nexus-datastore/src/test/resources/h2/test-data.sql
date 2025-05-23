-- Test data for H2 database tests with Java 21 Virtual Threads
-- This file populates test tables with data for validating JDBC operations,
-- transaction management, and query performance with Virtual Threads.

-- Clear existing data to ensure clean state
DELETE FROM test_transaction_item;
DELETE FROM test_transaction;
DELETE FROM test_blob;
DELETE FROM test_relationship;
DELETE FROM test_entity;

-- Insert basic entity records for CRUD operation testing
-- These records provide a baseline for read operations with Virtual Threads
INSERT INTO test_entity (id, name, description, status, created_date, last_updated, version, active)
VALUES 
(1, 'Entity-1', 'Test entity for basic operations', 'ACTIVE', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1, TRUE),
(2, 'Entity-2', 'Test entity with longer description for string handling tests', 'PENDING', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1, TRUE),
(3, 'Entity-3', 'Inactive entity for filtering tests', 'INACTIVE', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1, FALSE),
(4, 'Entity-4', 'Entity with multiple versions for optimistic locking tests', 'ACTIVE', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 3, TRUE),
(5, 'Entity-5', 'Entity for update operations', 'ACTIVE', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1, TRUE);

-- Add more entities for bulk operation and performance testing
-- These provide sufficient volume for Virtual Thread performance benchmarking
INSERT INTO test_entity (id, name, description, status, created_date, last_updated, version, active)
SELECT 
  id + 5, 
  'Perf-Entity-' || (id + 5), 
  'Performance test entity for Virtual Thread benchmarking ' || (id + 5),
  CASE WHEN MOD(id, 3) = 0 THEN 'ACTIVE' WHEN MOD(id, 3) = 1 THEN 'PENDING' ELSE 'INACTIVE' END,
  DATEADD('SECOND', id, CURRENT_TIMESTAMP()),
  DATEADD('SECOND', id, CURRENT_TIMESTAMP()),
  1,
  CASE WHEN MOD(id, 5) = 0 THEN FALSE ELSE TRUE END
FROM SYSTEM_RANGE(1, 95);

-- Insert relationship data to test join operations under Virtual Thread execution
-- These relationships allow testing complex queries that might cause thread pinning
INSERT INTO test_relationship (id, parent_id, child_id, relationship_type, created_date, weight)
VALUES
(1, 1, 2, 'PARENT_CHILD', CURRENT_TIMESTAMP(), 1.0),
(2, 1, 3, 'PARENT_CHILD', CURRENT_TIMESTAMP(), 2.0),
(3, 2, 4, 'PARENT_CHILD', CURRENT_TIMESTAMP(), 1.5),
(4, 3, 5, 'PARENT_CHILD', CURRENT_TIMESTAMP(), 3.0),
(5, 4, 5, 'REFERENCE', CURRENT_TIMESTAMP(), 1.0);

-- Add more relationship data for testing complex joins and query performance
INSERT INTO test_relationship (id, parent_id, child_id, relationship_type, created_date, weight)
SELECT
  id + 5,
  (MOD(id, 20) + 6), -- parent from performance entities
  (MOD(id + 7, 20) + 6), -- child from performance entities
  CASE WHEN MOD(id, 3) = 0 THEN 'PARENT_CHILD' WHEN MOD(id, 3) = 1 THEN 'REFERENCE' ELSE 'ASSOCIATION' END,
  DATEADD('SECOND', id, CURRENT_TIMESTAMP()),
  ROUND(RAND() * 5, 2)
FROM SYSTEM_RANGE(1, 45);

-- Insert blob data for testing large object operations with Virtual Threads
-- BLOB operations are particularly important for testing thread pinning
INSERT INTO test_blob (id, entity_id, blob_name, content_type, blob_data, created_date)
VALUES
(1, 1, 'small-blob-1', 'application/octet-stream', RAWTOHEX('Small test blob for entity 1'), CURRENT_TIMESTAMP()),
(2, 2, 'small-blob-2', 'application/octet-stream', RAWTOHEX('Another small test blob for entity 2'), CURRENT_TIMESTAMP()),
(3, 3, 'medium-blob-1', 'application/octet-stream', RAWTOHEX(REPEAT('Medium sized blob content for testing with Virtual Threads ', 10)), CURRENT_TIMESTAMP()),
(4, 4, 'medium-blob-2', 'application/octet-stream', RAWTOHEX(REPEAT('Another medium sized blob for testing I/O operations with Virtual Threads ', 10)), CURRENT_TIMESTAMP()),
(5, 5, 'large-blob-1', 'application/octet-stream', RAWTOHEX(REPEAT('Large blob content for testing potential thread pinning with Virtual Threads during I/O operations ', 20)), CURRENT_TIMESTAMP());

-- Add more blob data for performance testing
INSERT INTO test_blob (id, entity_id, blob_name, content_type, blob_data, created_date)
SELECT
  id + 5,
  (MOD(id, 20) + 6), -- entity_id from performance entities
  'perf-blob-' || (id + 5),
  'application/octet-stream',
  RAWTOHEX(REPEAT('Performance test blob content for Virtual Thread benchmarking ', MOD(id, 5) + 1)),
  DATEADD('SECOND', id, CURRENT_TIMESTAMP())
FROM SYSTEM_RANGE(1, 25);

-- Insert transaction data for testing transaction isolation with Virtual Threads
-- This data is structured to facilitate transaction boundary testing
INSERT INTO test_transaction (id, transaction_code, description, status, amount, created_date, last_updated, version)
VALUES
(1, 'TRX-001', 'Test transaction for basic operations', 'PENDING', 100.00, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1),
(2, 'TRX-002', 'Test transaction for commit operations', 'PENDING', 200.00, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1),
(3, 'TRX-003', 'Test transaction for rollback operations', 'PENDING', 300.00, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1),
(4, 'TRX-004', 'Test transaction for isolation level testing', 'PENDING', 400.00, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1),
(5, 'TRX-005', 'Test transaction for deadlock testing', 'PENDING', 500.00, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 1);

-- Add more transactions for performance testing
INSERT INTO test_transaction (id, transaction_code, description, status, amount, created_date, last_updated, version)
SELECT
  id + 5,
  'TRX-' || LPAD(CAST(id + 5 AS VARCHAR), 3, '0'),
  'Performance test transaction ' || (id + 5) || ' for Virtual Thread benchmarking',
  CASE WHEN MOD(id, 4) = 0 THEN 'PENDING' WHEN MOD(id, 4) = 1 THEN 'PROCESSING' WHEN MOD(id, 4) = 2 THEN 'COMPLETED' ELSE 'FAILED' END,
  (id * 100.00),
  DATEADD('SECOND', id, CURRENT_TIMESTAMP()),
  DATEADD('SECOND', id, CURRENT_TIMESTAMP()),
  1
FROM SYSTEM_RANGE(1, 45);

-- Insert transaction items for testing nested transaction operations
-- These items are used to test complex transaction scenarios across Virtual Threads
INSERT INTO test_transaction_item (id, transaction_id, item_code, description, quantity, price, created_date)
VALUES
(1, 1, 'ITEM-001', 'Transaction item 1 for transaction 1', 1, 100.00, CURRENT_TIMESTAMP()),
(2, 1, 'ITEM-002', 'Transaction item 2 for transaction 1', 2, 50.00, CURRENT_TIMESTAMP()),
(3, 2, 'ITEM-003', 'Transaction item 1 for transaction 2', 1, 200.00, CURRENT_TIMESTAMP()),
(4, 3, 'ITEM-004', 'Transaction item 1 for transaction 3', 3, 100.00, CURRENT_TIMESTAMP()),
(5, 4, 'ITEM-005', 'Transaction item 1 for transaction 4', 2, 200.00, CURRENT_TIMESTAMP()),
(6, 5, 'ITEM-006', 'Transaction item 1 for transaction 5', 5, 100.00, CURRENT_TIMESTAMP());

-- Add more transaction items for performance testing
INSERT INTO test_transaction_item (id, transaction_id, item_code, description, quantity, price, created_date)
SELECT
  id + 6,
  (MOD(id, 45) + 6), -- transaction_id from performance transactions
  'ITEM-' || LPAD(CAST(id + 6 AS VARCHAR), 3, '0'),
  'Performance test transaction item ' || (id + 6) || ' for Virtual Thread benchmarking',
  MOD(id, 10) + 1,
  (MOD(id, 10) + 1) * 50.00,
  DATEADD('SECOND', id, CURRENT_TIMESTAMP())
FROM SYSTEM_RANGE(1, 94);

-- Add comments to explain the purpose of this test data
-- This test data is specifically designed to test Java 21 Virtual Threads with database operations
-- It provides a comprehensive dataset for testing CRUD operations, transaction management,
-- and query performance when executed by Virtual Threads.
-- 
-- The data is structured to test various scenarios that might cause thread pinning in traditional
-- thread implementations, such as large object operations, complex joins, and transaction boundaries.
-- 
-- This dataset enables comprehensive testing of the following aspects:
-- 1. Basic CRUD operations with Virtual Threads
-- 2. Transaction isolation across Virtual Thread boundaries
-- 3. Performance comparison between platform threads and Virtual Threads
-- 4. Thread pinning detection during I/O-bound operations
-- 5. Scalability testing with varying concurrency levels