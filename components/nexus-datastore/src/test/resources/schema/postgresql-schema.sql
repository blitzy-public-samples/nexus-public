/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */

-- PostgreSQL schema for Virtual Thread testing
-- This schema creates tables with PostgreSQL-specific syntax to test JDBC operations
-- with Java 21 Virtual Threads, focusing on scenarios that might cause thread pinning

-- Basic test table for transaction tests
CREATE TABLE IF NOT EXISTS virtual_thread_test (
  id INTEGER PRIMARY KEY,
  value VARCHAR(255)
);

-- Performance test table
CREATE TABLE IF NOT EXISTS test_data (
  id INTEGER PRIMARY KEY,
  name VARCHAR(255)
);

-- Table with various PostgreSQL-specific column types for comprehensive testing
CREATE TABLE IF NOT EXISTS vt_data_types (
  id SERIAL PRIMARY KEY,
  int_col INTEGER NOT NULL,
  bigint_col BIGINT,
  decimal_col DECIMAL(19, 2),
  numeric_col NUMERIC(10, 4),
  real_col REAL,
  double_col DOUBLE PRECISION,
  char_col CHAR(10),
  varchar_col VARCHAR(255),
  text_col TEXT,
  bytea_col BYTEA,
  boolean_col BOOLEAN,
  timestamp_col TIMESTAMP,
  timestamptz_col TIMESTAMP WITH TIME ZONE,
  date_col DATE,
  time_col TIME,
  timetz_col TIME WITH TIME ZONE,
  interval_col INTERVAL,
  uuid_col UUID,
  json_col JSON,
  jsonb_col JSONB,
  point_col POINT,
  inet_col INET,
  cidr_col CIDR,
  macaddr_col MACADDR,
  xml_col XML,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table for testing large object operations with Virtual Threads
-- Large object operations are particularly important to test with Virtual Threads
-- as they traditionally cause thread pinning in older Java versions
CREATE TABLE IF NOT EXISTS vt_large_objects (
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  binary_data BYTEA,
  large_text TEXT,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Parent table for testing foreign key relationships and transaction integrity
CREATE TABLE IF NOT EXISTS vt_parent (
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  status VARCHAR(50) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Child table with foreign key to test transaction integrity across related tables
CREATE TABLE IF NOT EXISTS vt_child (
  id SERIAL PRIMARY KEY,
  parent_id INTEGER NOT NULL,
  name VARCHAR(255) NOT NULL,
  value TEXT,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_vt_parent FOREIGN KEY (parent_id) REFERENCES vt_parent (id) ON DELETE CASCADE
);

-- Table for testing batch operations with Virtual Threads
CREATE TABLE IF NOT EXISTS vt_batch_test (
  id SERIAL PRIMARY KEY,
  batch_id INTEGER NOT NULL,
  sequence_num INTEGER NOT NULL,
  data VARCHAR(255),
  processed BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table for testing concurrent operations with Virtual Threads
CREATE TABLE IF NOT EXISTS vt_concurrent_test (
  id SERIAL PRIMARY KEY,
  thread_id VARCHAR(100) NOT NULL,
  counter INTEGER DEFAULT 0,
  last_updated TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_vt_concurrent_test_thread_id UNIQUE (thread_id)
);

-- Table for testing JSON operations with Virtual Threads
CREATE TABLE IF NOT EXISTS vt_json_test (
  id SERIAL PRIMARY KEY,
  data JSONB NOT NULL,
  tags TEXT[],
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table for testing full text search with Virtual Threads
CREATE TABLE IF NOT EXISTS vt_search_test (
  id SERIAL PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  content TEXT,
  metadata JSONB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes to test different index operations with Virtual Threads

-- B-tree index (standard index type)
CREATE INDEX idx_vt_data_types_varchar_col ON vt_data_types (varchar_col);
CREATE INDEX idx_vt_data_types_created_at ON vt_data_types (created_at);

-- Multi-column B-tree index
CREATE INDEX idx_vt_child_parent_name ON vt_child (parent_id, name);

-- Unique index
CREATE UNIQUE INDEX idx_vt_batch_test_batch_seq ON vt_batch_test (batch_id, sequence_num);

-- GIN index for JSONB data
CREATE INDEX idx_vt_json_test_data ON vt_json_test USING GIN (data);

-- GIN index for full text search
CREATE INDEX idx_vt_search_test_content ON vt_search_test USING GIN (to_tsvector('english', content));

-- GIN index for array data
CREATE INDEX idx_vt_json_test_tags ON vt_json_test USING GIN (tags);

-- Partial index
CREATE INDEX idx_vt_batch_test_unprocessed ON vt_batch_test (batch_id) WHERE processed = FALSE;

-- Function-based index
CREATE INDEX idx_vt_parent_name_lower ON vt_parent (LOWER(name));

-- Create a view to test view operations with Virtual Threads
CREATE OR REPLACE VIEW vt_parent_child_view AS
SELECT p.id AS parent_id, p.name AS parent_name, p.status,
       c.id AS child_id, c.name AS child_name, c.value
FROM vt_parent p
JOIN vt_child c ON p.id = c.parent_id;

-- Create a materialized view to test materialized view operations with Virtual Threads
CREATE MATERIALIZED VIEW vt_batch_summary AS
SELECT batch_id, COUNT(*) AS total_records, SUM(CASE WHEN processed THEN 1 ELSE 0 END) AS processed_records
FROM vt_batch_test
GROUP BY batch_id
WITH NO DATA;