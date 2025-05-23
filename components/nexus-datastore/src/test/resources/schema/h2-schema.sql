-- H2 Database Schema for Virtual Thread Testing
-- This schema is designed to test JDBC operations with Java 21 Virtual Threads
-- focusing on scenarios that might traditionally cause thread pinning
-- 
-- Key operations tested:
-- 1. Large object (BLOB/CLOB) operations
-- 2. Batch processing
-- 3. Concurrent transaction handling
-- 4. Complex joins with multiple foreign keys
-- 5. Index operations

-- Drop tables if they exist to ensure clean setup
DROP TABLE IF EXISTS vt_order_items;
DROP TABLE IF EXISTS vt_orders;
DROP TABLE IF EXISTS vt_customers;
DROP TABLE IF EXISTS vt_products;
DROP TABLE IF EXISTS vt_categories;
DROP TABLE IF EXISTS vt_blob_test;
DROP TABLE IF EXISTS vt_batch_test;
DROP TABLE IF EXISTS vt_concurrent_test;
DROP TABLE IF EXISTS vt_isolation_test;
DROP TABLE IF EXISTS vt_connection_test;
DROP TABLE IF EXISTS vt_statement_test;

-- Create category table with basic columns
CREATE TABLE vt_categories (
    category_id INT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN DEFAULT TRUE
);

-- Create index on category name for query testing
CREATE INDEX idx_category_name ON vt_categories(name);

-- Create product table with foreign key to categories
CREATE TABLE vt_products (
    product_id INT PRIMARY KEY,
    category_id INT,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    price DECIMAL(10,2) NOT NULL,
    stock_quantity INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (category_id) REFERENCES vt_categories(category_id)
);

-- Create indexes on product table for query optimization testing
CREATE INDEX idx_product_category ON vt_products(category_id);
CREATE INDEX idx_product_name ON vt_products(name);

-- Create customer table for relationship testing
CREATE TABLE vt_customers (
    customer_id INT PRIMARY KEY,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    phone VARCHAR(20),
    address VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

-- Create index on customer email for lookup testing
CREATE INDEX idx_customer_email ON vt_customers(email);

-- Create orders table with foreign key to customers
CREATE TABLE vt_orders (
    order_id INT PRIMARY KEY,
    customer_id INT NOT NULL,
    order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',
    total_amount DECIMAL(12,2),
    shipping_address VARCHAR(255),
    tracking_number VARCHAR(50),
    notes CLOB,
    FOREIGN KEY (customer_id) REFERENCES vt_customers(customer_id)
);

-- Create indexes on orders for query testing
CREATE INDEX idx_order_customer ON vt_orders(customer_id);
CREATE INDEX idx_order_date ON vt_orders(order_date);
CREATE INDEX idx_order_status ON vt_orders(status);

-- Create order items table with multiple foreign keys
-- This tests complex relationship handling with Virtual Threads
CREATE TABLE vt_order_items (
    item_id INT PRIMARY KEY,
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    discount DECIMAL(5,2) DEFAULT 0,
    FOREIGN KEY (order_id) REFERENCES vt_orders(order_id),
    FOREIGN KEY (product_id) REFERENCES vt_products(product_id)
);

-- Create index on order items for query optimization
CREATE INDEX idx_orderitem_order ON vt_order_items(order_id);
CREATE INDEX idx_orderitem_product ON vt_order_items(product_id);

-- Create a table specifically for BLOB/CLOB testing
-- Large object operations are known to potentially cause thread pinning
-- with traditional JDBC implementations in older Java versions
CREATE TABLE vt_blob_test (
    id INT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    binary_data BLOB,         -- For testing BLOB read/write operations
    text_data CLOB,           -- For testing CLOB read/write operations
    metadata VARCHAR(255),    -- For storing information about the binary data
    checksum VARCHAR(64),     -- For verifying data integrity
    file_size BIGINT,         -- For testing large object size handling
    mime_type VARCHAR(100),   -- For testing content type handling
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_accessed TIMESTAMP   -- For tracking access patterns
);

-- Create a table for batch operation testing
-- Batch operations can be optimized with Virtual Threads to improve throughput
CREATE TABLE vt_batch_test (
    id INT PRIMARY KEY AUTO_INCREMENT,
    batch_id VARCHAR(36) NOT NULL,       -- UUID for batch identification
    sequence_num INT NOT NULL,           -- Order within batch
    payload VARCHAR(1000),               -- Data to be processed
    processed BOOLEAN DEFAULT FALSE,     -- Processing status flag
    error_message VARCHAR(500),          -- For tracking processing errors
    retry_count INT DEFAULT 0,           -- For testing retry logic
    priority INT DEFAULT 5,              -- For testing prioritized processing
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,              -- When the record was processed
    UNIQUE (batch_id, sequence_num)      -- Ensure sequence integrity within batch
);

-- Create index on batch_id for batch processing tests
CREATE INDEX idx_batch_id ON vt_batch_test(batch_id);

-- Create a table for concurrent transaction testing
-- This specifically tests scenarios where multiple Virtual Threads
-- might attempt to update the same records simultaneously
CREATE TABLE vt_concurrent_test (
    id INT PRIMARY KEY,
    resource_name VARCHAR(100) NOT NULL,
    counter INT DEFAULT 0,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INT DEFAULT 0,  -- For optimistic locking tests
    lock_owner VARCHAR(36), -- For pessimistic locking tests
    UNIQUE (resource_name)
);

-- Create a table for testing transaction isolation levels with Virtual Threads
CREATE TABLE vt_isolation_test (
    id INT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INT NOT NULL,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(50)
);

-- Insert sample data for isolation testing
INSERT INTO vt_isolation_test (id, name, value, updated_by) VALUES
(1, 'counter1', 0, 'system'),
(2, 'counter2', 0, 'system');

-- Insert some sample data for categories
INSERT INTO vt_categories (category_id, name, description) VALUES 
(1, 'Electronics', 'Electronic devices and accessories'),
(2, 'Books', 'Books, e-books, and publications'),
(3, 'Clothing', 'Apparel and fashion items');

-- Insert some sample data for products
INSERT INTO vt_products (product_id, category_id, name, price, stock_quantity) VALUES 
(101, 1, 'Smartphone', 699.99, 50),
(102, 1, 'Laptop', 1299.99, 25),
(103, 2, 'Java Programming', 49.99, 100),
(104, 3, 'T-Shirt', 19.99, 200);

-- Insert sample data for customers
INSERT INTO vt_customers (customer_id, first_name, last_name, email, phone) VALUES 
(1001, 'John', 'Doe', 'john.doe@example.com', '555-123-4567'),
(1002, 'Jane', 'Smith', 'jane.smith@example.com', '555-987-6543');

-- Insert sample data for concurrent testing
INSERT INTO vt_concurrent_test (id, resource_name, counter) VALUES 
(1, 'resource1', 0),
(2, 'resource2', 0),
(3, 'resource3', 0),
(4, 'resource4', 0),
(5, 'resource5', 0);

-- Create a table for testing connection pool behavior with Virtual Threads
CREATE TABLE vt_connection_test (
    id INT PRIMARY KEY AUTO_INCREMENT,
    connection_id VARCHAR(100) NOT NULL,  -- Connection identifier
    thread_name VARCHAR(100) NOT NULL,    -- Thread that acquired the connection
    acquire_time TIMESTAMP,               -- When connection was acquired
    release_time TIMESTAMP,               -- When connection was released
    operation_type VARCHAR(50),           -- Type of operation performed
    duration_ms BIGINT,                   -- How long the connection was held
    success BOOLEAN                       -- Whether operation completed successfully
);

-- Create a table for testing prepared statement caching with Virtual Threads
CREATE TABLE vt_statement_test (
    id INT PRIMARY KEY AUTO_INCREMENT,
    statement_id VARCHAR(100) NOT NULL,   -- Statement identifier
    sql_text CLOB,                        -- The SQL text of the statement
    parameter_count INT,                  -- Number of parameters
    execution_count INT DEFAULT 0,        -- How many times executed
    avg_execution_time DOUBLE,            -- Average execution time
    last_execution_time TIMESTAMP,        -- When last executed
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);