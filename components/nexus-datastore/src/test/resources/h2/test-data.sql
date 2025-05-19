-- H2 database test data for Virtual Thread testing
-- This file populates the test schema with sample data needed for database operation testing with Java 21 Virtual Threads
-- The data is designed to test various scenarios including:
-- 1. Read operations with Virtual Threads
-- 2. Join operations across related tables
-- 3. Transaction isolation with concurrent operations
-- 4. Performance benchmarking with consistent datasets

-- Clear existing data to ensure clean state
DELETE FROM vt_child_table;
DELETE FROM vt_parent_table;
DELETE FROM vt_large_object_test;
DELETE FROM vt_batch_test;
DELETE FROM vt_index_test;
DELETE FROM vt_transaction_test;
DELETE FROM vt_concurrent_test;

-- Insert data into vt_parent_table for basic CRUD testing
INSERT INTO vt_parent_table (name, description, created_at, updated_at, active, priority, data)
VALUES
('Parent 1', 'Description for parent record 1', DATEADD('DAY', -10, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 1, '{"key": "value1", "count": 10}'),
('Parent 2', 'Description for parent record 2', DATEADD('DAY', -9, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 2, '{"key": "value2", "count": 20}'),
('Parent 3', 'Description for parent record 3', DATEADD('DAY', -8, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 3, '{"key": "value3", "count": 30}'),
('Parent 4', 'Description for parent record 4', DATEADD('DAY', -7, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), FALSE, 4, '{"key": "value4", "count": 40}'),
('Parent 5', 'Description for parent record 5', DATEADD('DAY', -6, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 5, '{"key": "value5", "count": 50}'),
('Parent 6', 'Description for parent record 6', DATEADD('DAY', -5, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 1, '{"key": "value6", "count": 60}'),
('Parent 7', 'Description for parent record 7', DATEADD('DAY', -4, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), FALSE, 2, '{"key": "value7", "count": 70}'),
('Parent 8', 'Description for parent record 8', DATEADD('DAY', -3, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 3, '{"key": "value8", "count": 80}'),
('Parent 9', 'Description for parent record 9', DATEADD('DAY', -2, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 4, '{"key": "value9", "count": 90}'),
('Parent 10', 'Description for parent record 10', DATEADD('DAY', -1, CURRENT_TIMESTAMP()), CURRENT_TIMESTAMP(), TRUE, 5, '{"key": "value10", "count": 100}');

-- Insert data into vt_child_table to test join operations
INSERT INTO vt_child_table (parent_id, name, value, created_at)
VALUES
(1, 'Child 1-1', 10.50, DATEADD('DAY', -10, CURRENT_TIMESTAMP())),
(1, 'Child 1-2', 20.75, DATEADD('DAY', -9, CURRENT_TIMESTAMP())),
(1, 'Child 1-3', 30.25, DATEADD('DAY', -8, CURRENT_TIMESTAMP())),
(2, 'Child 2-1', 15.30, DATEADD('DAY', -7, CURRENT_TIMESTAMP())),
(2, 'Child 2-2', 25.45, DATEADD('DAY', -6, CURRENT_TIMESTAMP())),
(3, 'Child 3-1', 35.60, DATEADD('DAY', -5, CURRENT_TIMESTAMP())),
(3, 'Child 3-2', 45.75, DATEADD('DAY', -4, CURRENT_TIMESTAMP())),
(3, 'Child 3-3', 55.90, DATEADD('DAY', -3, CURRENT_TIMESTAMP())),
(4, 'Child 4-1', 65.10, DATEADD('DAY', -2, CURRENT_TIMESTAMP())),
(5, 'Child 5-1', 75.25, DATEADD('DAY', -1, CURRENT_TIMESTAMP())),
(5, 'Child 5-2', 85.40, CURRENT_TIMESTAMP()),
(6, 'Child 6-1', 95.55, CURRENT_TIMESTAMP()),
(7, 'Child 7-1', 105.70, CURRENT_TIMESTAMP()),
(8, 'Child 8-1', 115.85, CURRENT_TIMESTAMP()),
(9, 'Child 9-1', 125.00, CURRENT_TIMESTAMP()),
(10, 'Child 10-1', 135.15, CURRENT_TIMESTAMP()),
(10, 'Child 10-2', 145.30, CURRENT_TIMESTAMP()),
(10, 'Child 10-3', 155.45, CURRENT_TIMESTAMP()),
(10, 'Child 10-4', 165.60, CURRENT_TIMESTAMP()),
(10, 'Child 10-5', 175.75, CURRENT_TIMESTAMP());

-- Generate sample binary data for BLOB testing
CALL RANDOM_BYTES(1024, 'sample_binary_data');

-- Insert data into vt_large_object_test to test large object operations
INSERT INTO vt_large_object_test (binary_data, text_data, description, created_at)
VALUES
(@sample_binary_data, REPEAT('This is a sample CLOB text for testing large object operations with Virtual Threads. ', 100), 'Small BLOB/CLOB test', DATEADD('DAY', -5, CURRENT_TIMESTAMP())),
(@sample_binary_data, REPEAT('This is a medium-sized CLOB text for testing large object operations with Virtual Threads. ', 500), 'Medium BLOB/CLOB test', DATEADD('DAY', -4, CURRENT_TIMESTAMP())),
(@sample_binary_data, REPEAT('This is a large CLOB text for testing large object operations with Virtual Threads. ', 1000), 'Large BLOB/CLOB test', DATEADD('DAY', -3, CURRENT_TIMESTAMP())),
(@sample_binary_data, REPEAT('This is a very large CLOB text for testing large object operations with Virtual Threads. ', 2000), 'Very large BLOB/CLOB test', DATEADD('DAY', -2, CURRENT_TIMESTAMP())),
(@sample_binary_data, REPEAT('This is an extremely large CLOB text for testing large object operations with Virtual Threads. ', 5000), 'Extremely large BLOB/CLOB test', DATEADD('DAY', -1, CURRENT_TIMESTAMP()));

-- Insert data into vt_batch_test to test batch operations
-- Create 5 batches with 20 records each
INSERT INTO vt_batch_test (batch_id, sequence_num, data, processed, processed_at, created_at)
SELECT 
    FLOOR((X-1)/20) + 1 AS batch_id,
    MOD(X-1, 20) + 1 AS sequence_num,
    'Batch data for batch ' || (FLOOR((X-1)/20) + 1) || ', sequence ' || (MOD(X-1, 20) + 1),
    CASE WHEN MOD(X, 4) = 0 THEN TRUE ELSE FALSE END AS processed,
    CASE WHEN MOD(X, 4) = 0 THEN DATEADD('MINUTE', -X, CURRENT_TIMESTAMP()) ELSE NULL END AS processed_at,
    DATEADD('HOUR', -X, CURRENT_TIMESTAMP()) AS created_at
FROM SYSTEM_RANGE(1, 100);

-- Insert data into vt_index_test to test index operations
INSERT INTO vt_index_test (tags, keywords, document, full_text, created_at)
VALUES
('java,virtual-thread,jdbc', 'java21,concurrency,jdbc,database', '{"type": "test", "category": "java", "tags": ["java", "virtual-thread", "jdbc"]}', 'This document describes testing Java 21 Virtual Threads with JDBC operations. Virtual Threads are a lightweight implementation of threads that make it practical to represent a unit of concurrency as a thread.', DATEADD('DAY', -10, CURRENT_TIMESTAMP())),
('database,h2,performance', 'database,h2,performance,benchmark', '{"type": "test", "category": "database", "tags": ["database", "h2", "performance"]}', 'H2 is a relational database management system written in Java. It can be embedded in Java applications or run in client-server mode. This document describes performance testing with H2 database.', DATEADD('DAY', -9, CURRENT_TIMESTAMP())),
('concurrency,thread-pinning,jdbc', 'concurrency,thread-pinning,jdbc,java21', '{"type": "test", "category": "concurrency", "tags": ["concurrency", "thread-pinning", "jdbc"]}', 'Thread pinning occurs when a virtual thread is forced to execute on its carrier thread without the ability to yield. This document describes testing scenarios that might cause thread pinning with JDBC operations.', DATEADD('DAY', -8, CURRENT_TIMESTAMP())),
('transaction,isolation,concurrency', 'transaction,isolation,concurrency,database', '{"type": "test", "category": "transaction", "tags": ["transaction", "isolation", "concurrency"]}', 'Transaction isolation is an important property of database transactions. This document describes testing transaction isolation levels with Virtual Threads to ensure data consistency.', DATEADD('DAY', -7, CURRENT_TIMESTAMP())),
('blob,clob,large-object', 'blob,clob,large-object,jdbc', '{"type": "test", "category": "blob", "tags": ["blob", "clob", "large-object"]}', 'BLOB (Binary Large Object) and CLOB (Character Large Object) are database types used to store large data objects. This document describes testing BLOB and CLOB operations with Virtual Threads.', DATEADD('DAY', -6, CURRENT_TIMESTAMP())),
('batch,processing,performance', 'batch,processing,performance,jdbc', '{"type": "test", "category": "batch", "tags": ["batch", "processing", "performance"]}', 'Batch processing is a technique used to process large volumes of data efficiently. This document describes testing batch processing operations with Virtual Threads.', DATEADD('DAY', -5, CURRENT_TIMESTAMP())),
('index,query,optimization', 'index,query,optimization,database', '{"type": "test", "category": "index", "tags": ["index", "query", "optimization"]}', 'Database indexes are used to speed up the retrieval of records. This document describes testing index operations with Virtual Threads to ensure optimal query performance.', DATEADD('DAY', -4, CURRENT_TIMESTAMP())),
('connection,pool,hikaricp', 'connection,pool,hikaricp,jdbc', '{"type": "test", "category": "connection", "tags": ["connection", "pool", "hikaricp"]}', 'Connection pooling is a technique used to improve the performance of database operations. This document describes testing connection pool behavior with Virtual Threads.', DATEADD('DAY', -3, CURRENT_TIMESTAMP())),
('lock,concurrent,access', 'lock,concurrent,access,database', '{"type": "test", "category": "lock", "tags": ["lock", "concurrent", "access"]}', 'Database locks are used to control concurrent access to data. This document describes testing locking mechanisms with Virtual Threads to ensure data integrity.', DATEADD('DAY', -2, CURRENT_TIMESTAMP())),
('performance,benchmark,comparison', 'performance,benchmark,comparison,java21', '{"type": "test", "category": "performance", "tags": ["performance", "benchmark", "comparison"]}', 'Performance benchmarking is used to measure and compare the performance of different implementations. This document describes comparing the performance of Virtual Threads with platform threads for database operations.', DATEADD('DAY', -1, CURRENT_TIMESTAMP()));

-- Insert data into vt_transaction_test to test transaction operations
INSERT INTO vt_transaction_test (account_id, transaction_type, amount, balance, description, transaction_date, status)
VALUES
('ACC001', 'deposit', 1000.00, 1000.00, 'Initial deposit', DATEADD('DAY', -30, CURRENT_TIMESTAMP()), 'completed'),
('ACC001', 'withdrawal', 200.00, 800.00, 'ATM withdrawal', DATEADD('DAY', -25, CURRENT_TIMESTAMP()), 'completed'),
('ACC001', 'deposit', 500.00, 1300.00, 'Salary deposit', DATEADD('DAY', -20, CURRENT_TIMESTAMP()), 'completed'),
('ACC001', 'withdrawal', 300.00, 1000.00, 'Online payment', DATEADD('DAY', -15, CURRENT_TIMESTAMP()), 'completed'),
('ACC001', 'transfer', 200.00, 800.00, 'Transfer to ACC002', DATEADD('DAY', -10, CURRENT_TIMESTAMP()), 'completed'),
('ACC002', 'deposit', 500.00, 500.00, 'Initial deposit', DATEADD('DAY', -28, CURRENT_TIMESTAMP()), 'completed'),
('ACC002', 'transfer', 200.00, 700.00, 'Transfer from ACC001', DATEADD('DAY', -10, CURRENT_TIMESTAMP()), 'completed'),
('ACC002', 'withdrawal', 100.00, 600.00, 'Online purchase', DATEADD('DAY', -5, CURRENT_TIMESTAMP()), 'completed'),
('ACC003', 'deposit', 2000.00, 2000.00, 'Initial deposit', DATEADD('DAY', -15, CURRENT_TIMESTAMP()), 'completed'),
('ACC003', 'withdrawal', 500.00, 1500.00, 'Cash withdrawal', DATEADD('DAY', -10, CURRENT_TIMESTAMP()), 'completed'),
('ACC003', 'deposit', 1000.00, 2500.00, 'Bonus deposit', DATEADD('DAY', -5, CURRENT_TIMESTAMP()), 'completed'),
('ACC004', 'deposit', 5000.00, 5000.00, 'Initial deposit', DATEADD('DAY', -20, CURRENT_TIMESTAMP()), 'completed'),
('ACC004', 'withdrawal', 1000.00, 4000.00, 'Rent payment', DATEADD('DAY', -15, CURRENT_TIMESTAMP()), 'completed'),
('ACC004', 'withdrawal', 500.00, 3500.00, 'Utility bills', DATEADD('DAY', -10, CURRENT_TIMESTAMP()), 'completed'),
('ACC004', 'transfer', 1000.00, 2500.00, 'Transfer to ACC005', DATEADD('DAY', -5, CURRENT_TIMESTAMP()), 'completed'),
('ACC005', 'deposit', 3000.00, 3000.00, 'Initial deposit', DATEADD('DAY', -25, CURRENT_TIMESTAMP()), 'completed'),
('ACC005', 'transfer', 1000.00, 4000.00, 'Transfer from ACC004', DATEADD('DAY', -5, CURRENT_TIMESTAMP()), 'completed'),
('ACC005', 'withdrawal', 200.00, 3800.00, 'Online subscription', DATEADD('DAY', -2, CURRENT_TIMESTAMP()), 'completed'),
('ACC006', 'deposit', 10000.00, 10000.00, 'Initial deposit', DATEADD('DAY', -30, CURRENT_TIMESTAMP()), 'completed'),
('ACC006', 'withdrawal', 2000.00, 8000.00, 'Investment purchase', DATEADD('DAY', -20, CURRENT_TIMESTAMP()), 'completed');

-- Insert data into vt_concurrent_test to test concurrent operations
INSERT INTO vt_concurrent_test (resource_id, lock_owner, lock_acquired_at, lock_expires_at, data, version, created_at, updated_at)
VALUES
('resource1', NULL, NULL, NULL, '{"status": "available", "priority": 1}', 1, DATEADD('DAY', -10, CURRENT_TIMESTAMP()), DATEADD('DAY', -10, CURRENT_TIMESTAMP())),
('resource2', NULL, NULL, NULL, '{"status": "available", "priority": 2}', 1, DATEADD('DAY', -9, CURRENT_TIMESTAMP()), DATEADD('DAY', -9, CURRENT_TIMESTAMP())),
('resource3', NULL, NULL, NULL, '{"status": "available", "priority": 3}', 1, DATEADD('DAY', -8, CURRENT_TIMESTAMP()), DATEADD('DAY', -8, CURRENT_TIMESTAMP())),
('resource4', 'owner1', DATEADD('MINUTE', -30, CURRENT_TIMESTAMP()), DATEADD('MINUTE', 30, CURRENT_TIMESTAMP()), '{"status": "locked", "priority": 1}', 2, DATEADD('DAY', -7, CURRENT_TIMESTAMP()), DATEADD('MINUTE', -30, CURRENT_TIMESTAMP())),
('resource5', 'owner2', DATEADD('MINUTE', -20, CURRENT_TIMESTAMP()), DATEADD('MINUTE', 40, CURRENT_TIMESTAMP()), '{"status": "locked", "priority": 2}', 2, DATEADD('DAY', -6, CURRENT_TIMESTAMP()), DATEADD('MINUTE', -20, CURRENT_TIMESTAMP())),
('resource6', 'owner3', DATEADD('MINUTE', -10, CURRENT_TIMESTAMP()), DATEADD('MINUTE', -5, CURRENT_TIMESTAMP()), '{"status": "locked", "priority": 3}', 2, DATEADD('DAY', -5, CURRENT_TIMESTAMP()), DATEADD('MINUTE', -10, CURRENT_TIMESTAMP())),
('resource7', NULL, NULL, NULL, '{"status": "available", "priority": 1}', 1, DATEADD('DAY', -4, CURRENT_TIMESTAMP()), DATEADD('DAY', -4, CURRENT_TIMESTAMP())),
('resource8', NULL, NULL, NULL, '{"status": "available", "priority": 2}', 1, DATEADD('DAY', -3, CURRENT_TIMESTAMP()), DATEADD('DAY', -3, CURRENT_TIMESTAMP())),
('resource9', NULL, NULL, NULL, '{"status": "available", "priority": 3}', 1, DATEADD('DAY', -2, CURRENT_TIMESTAMP()), DATEADD('DAY', -2, CURRENT_TIMESTAMP())),
('resource10', NULL, NULL, NULL, '{"status": "available", "priority": 1}', 1, DATEADD('DAY', -1, CURRENT_TIMESTAMP()), DATEADD('DAY', -1, CURRENT_TIMESTAMP()));

-- Add additional test data for performance benchmarking
-- Create a procedure to generate a large number of test records
CREATE ALIAS IF NOT EXISTS generate_benchmark_data AS $$
    void generateBenchmarkData(Connection conn, int parentCount, int childrenPerParent) throws SQLException {
        // Generate parent records
        PreparedStatement parentStmt = conn.prepareStatement(
            "INSERT INTO vt_parent_table (name, description, created_at, updated_at, active, priority, data) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)");
            
        for (int i = 1; i <= parentCount; i++) {
            parentStmt.setString(1, "Benchmark Parent " + i);
            parentStmt.setString(2, "Description for benchmark parent record " + i);
            parentStmt.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis() - (i * 3600000)));
            parentStmt.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis()));
            parentStmt.setBoolean(5, i % 5 != 0); // 80% active
            parentStmt.setInt(6, (i % 5) + 1);
            parentStmt.setString(7, "{\"key\": \"benchmark" + i + "\", \"count\": " + (i * 10) + "}");
            parentStmt.addBatch();
            
            if (i % 100 == 0) {
                parentStmt.executeBatch();
            }
        }
        parentStmt.executeBatch();
        
        // Get the IDs of the inserted parent records
        PreparedStatement idStmt = conn.prepareStatement("SELECT id FROM vt_parent_table WHERE name LIKE 'Benchmark Parent %' ORDER BY id");
        ResultSet rs = idStmt.executeQuery();
        
        // Generate child records for each parent
        PreparedStatement childStmt = conn.prepareStatement(
            "INSERT INTO vt_child_table (parent_id, name, value, created_at) " +
            "VALUES (?, ?, ?, ?)");
            
        int count = 0;
        while (rs.next()) {
            int parentId = rs.getInt(1);
            
            for (int j = 1; j <= childrenPerParent; j++) {
                childStmt.setInt(1, parentId);
                childStmt.setString(2, "Benchmark Child " + parentId + "-" + j);
                childStmt.setDouble(3, j * 10.5);
                childStmt.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis() - (j * 60000)));
                childStmt.addBatch();
                count++;
                
                if (count % 100 == 0) {
                    childStmt.executeBatch();
                }
            }
        }
        childStmt.executeBatch();
        rs.close();
    }
$$;

-- Generate benchmark data with 100 parent records and 10 children each
CALL generate_benchmark_data(100, 10);

-- Create a procedure to generate transaction test data for benchmarking
CREATE ALIAS IF NOT EXISTS generate_transaction_benchmark_data AS $$
    void generateTransactionBenchmarkData(Connection conn, int accountCount, int transactionsPerAccount) throws SQLException {
        String[] types = {"deposit", "withdrawal", "transfer"};
        String[] statuses = {"completed", "pending", "failed"};
        
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO vt_transaction_test (account_id, transaction_type, amount, balance, description, transaction_date, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)");
            
        for (int i = 1; i <= accountCount; i++) {
            String accountId = String.format("BENCH%04d", i);
            double balance = 10000.0; // Starting balance
            
            for (int j = 1; j <= transactionsPerAccount; j++) {
                String type = types[j % 3];
                double amount = (j * 100) + (Math.random() * 100);
                
                if (type.equals("withdrawal") || type.equals("transfer")) {
                    balance -= amount;
                } else {
                    balance += amount;
                }
                
                stmt.setString(1, accountId);
                stmt.setString(2, type);
                stmt.setDouble(3, amount);
                stmt.setDouble(4, balance);
                stmt.setString(5, "Benchmark " + type + " transaction " + j + " for account " + accountId);
                stmt.setTimestamp(6, new java.sql.Timestamp(System.currentTimeMillis() - (j * 3600000)));
                stmt.setString(7, statuses[j % 3]);
                stmt.addBatch();
                
                if (j % 100 == 0) {
                    stmt.executeBatch();
                }
            }
        }
        stmt.executeBatch();
    }
$$;

-- Generate transaction benchmark data with 50 accounts and 20 transactions each
CALL generate_transaction_benchmark_data(50, 20);

-- Create a procedure to generate concurrent test data for benchmarking
CREATE ALIAS IF NOT EXISTS generate_concurrent_benchmark_data AS $$
    void generateConcurrentBenchmarkData(Connection conn, int resourceCount) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO vt_concurrent_test (resource_id, lock_owner, lock_acquired_at, lock_expires_at, data, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            
        for (int i = 1; i <= resourceCount; i++) {
            String resourceId = "benchmark-resource-" + i;
            boolean isLocked = i % 10 == 0; // 10% locked
            
            stmt.setString(1, resourceId);
            
            if (isLocked) {
                stmt.setString(2, "benchmark-owner-" + i);
                stmt.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis() - (i * 60000)));
                stmt.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis() + (i * 60000)));
            } else {
                stmt.setNull(2, java.sql.Types.VARCHAR);
                stmt.setNull(3, java.sql.Types.TIMESTAMP);
                stmt.setNull(4, java.sql.Types.TIMESTAMP);
            }
            
            stmt.setString(5, "{\"status\": \"" + (isLocked ? "locked" : "available") + "\", \"priority\": " + (i % 5 + 1) + "}");
            stmt.setInt(6, isLocked ? 2 : 1);
            stmt.setTimestamp(7, new java.sql.Timestamp(System.currentTimeMillis() - (i * 3600000)));
            stmt.setTimestamp(8, isLocked ? new java.sql.Timestamp(System.currentTimeMillis() - (i * 60000)) : new java.sql.Timestamp(System.currentTimeMillis() - (i * 3600000)));
            stmt.addBatch();
            
            if (i % 100 == 0) {
                stmt.executeBatch();
            }
        }
        stmt.executeBatch();
    }
$$;

-- Generate concurrent benchmark data with 100 resources
CALL generate_concurrent_benchmark_data(100);

-- Add comments to explain the purpose of this test data
COMMENT ON TABLE vt_parent_table IS 'Table populated with test data for basic CRUD operations with Virtual Threads';
COMMENT ON TABLE vt_child_table IS 'Table populated with test data for testing join operations with Virtual Threads';
COMMENT ON TABLE vt_large_object_test IS 'Table populated with test data for testing large object operations with Virtual Threads';
COMMENT ON TABLE vt_batch_test IS 'Table populated with test data for testing batch operations with Virtual Threads';
COMMENT ON TABLE vt_index_test IS 'Table populated with test data for testing index operations with Virtual Threads';
COMMENT ON TABLE vt_transaction_test IS 'Table populated with test data for testing transaction operations with Virtual Threads';
COMMENT ON TABLE vt_concurrent_test IS 'Table populated with test data for testing concurrent operations with Virtual Threads';