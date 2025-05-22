-- Virtual Thread Test Data for Database Migration Testing
-- This script creates test tables and data to validate database operations with Java 21 Virtual Threads
-- It simulates real-world upgrade scenarios to test JDBC driver compatibility and thread pinning issues

-- Create schema for virtual thread testing
CREATE SCHEMA IF NOT EXISTS vt_test;

-- ==========================================
-- Basic tables for testing simple operations
-- ==========================================

-- Configuration table - simulates system configuration storage
CREATE TABLE vt_test.configuration (
    id VARCHAR(64) PRIMARY KEY,
    value TEXT NOT NULL,
    data_type VARCHAR(32) NOT NULL,
    description TEXT,
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1
);

-- Insert sample configuration data
INSERT INTO vt_test.configuration (id, value, data_type, description, created_by) VALUES
('db.pool.size', '50', 'INTEGER', 'Database connection pool size', 'system'),
('storage.path', '/data/nexus/storage', 'STRING', 'Path to blob storage', 'system'),
('cleanup.enabled', 'true', 'BOOLEAN', 'Enable automatic cleanup', 'system'),
('search.analyzer', 'standard', 'STRING', 'Default search analyzer', 'system'),
('metrics.enabled', 'true', 'BOOLEAN', 'Enable metrics collection', 'system');

-- User table - simulates user data
CREATE TABLE vt_test.users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(128) NOT NULL,
    first_name VARCHAR(64),
    last_name VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

-- Insert sample user data
INSERT INTO vt_test.users (username, email, first_name, last_name) VALUES
('admin', 'admin@example.com', 'Admin', 'User'),
('jdoe', 'john.doe@example.com', 'John', 'Doe'),
('asmith', 'alice.smith@example.com', 'Alice', 'Smith'),
('bjohnson', 'bob.johnson@example.com', 'Bob', 'Johnson'),
('clee', 'carol.lee@example.com', 'Carol', 'Lee');

-- ==========================================
-- Complex tables for testing migrations
-- ==========================================

-- Repository table - simulates repository configuration
CREATE TABLE vt_test.repositories (
    id SERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    format VARCHAR(32) NOT NULL,
    type VARCHAR(32) NOT NULL,
    url VARCHAR(255),
    online BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by INTEGER REFERENCES vt_test.users(id),
    last_modified TIMESTAMP,
    modified_by INTEGER REFERENCES vt_test.users(id),
    attributes JSONB
);

-- Insert sample repository data
INSERT INTO vt_test.repositories (name, format, type, url, created_by, attributes) VALUES
('maven-central', 'maven2', 'proxy', 'https://repo1.maven.org/maven2/', 1, '{"proxy": {"remoteUrl": "https://repo1.maven.org/maven2/", "contentMaxAge": 1440, "metadataMaxAge": 1440}}'),
('npm-hosted', 'npm', 'hosted', NULL, 1, '{"storage": {"blobStoreName": "default", "strictContentTypeValidation": true}}'),
('docker-hub', 'docker', 'proxy', 'https://registry-1.docker.io', 1, '{"docker": {"v1Enabled": false, "forceBasicAuth": true}}'),
('nuget-group', 'nuget', 'group', NULL, 1, '{"group": {"memberNames": ["nuget-hosted", "nuget-proxy"]}}'),
('raw-hosted', 'raw', 'hosted', NULL, 1, '{"raw": {"contentDisposition": "attachment"}}');

-- Component table - simulates stored components
CREATE TABLE vt_test.components (
    id SERIAL PRIMARY KEY,
    repository_id INTEGER NOT NULL REFERENCES vt_test.repositories(id),
    group_id VARCHAR(255),
    artifact_id VARCHAR(255),
    version VARCHAR(64),
    format VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_updated TIMESTAMP,
    last_downloaded TIMESTAMP,
    download_count INTEGER NOT NULL DEFAULT 0
);

-- Create index on components
CREATE INDEX idx_components_repo_gav ON vt_test.components(repository_id, group_id, artifact_id, version);

-- Asset table - simulates stored assets
CREATE TABLE vt_test.assets (
    id SERIAL PRIMARY KEY,
    component_id INTEGER REFERENCES vt_test.components(id),
    repository_id INTEGER NOT NULL REFERENCES vt_test.repositories(id),
    path VARCHAR(512) NOT NULL,
    content_type VARCHAR(64),
    size_bytes BIGINT NOT NULL DEFAULT 0,
    sha1 CHAR(40),
    md5 CHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_updated TIMESTAMP,
    last_downloaded TIMESTAMP,
    download_count INTEGER NOT NULL DEFAULT 0
);

-- Create index on assets
CREATE UNIQUE INDEX idx_assets_repo_path ON vt_test.assets(repository_id, path);

-- Insert sample component and asset data (larger dataset to test performance)
DO $$
DECLARE
    i INTEGER;
    comp_id INTEGER;
    repo_id INTEGER;
    group_names VARCHAR[] := ARRAY['org.apache', 'com.google', 'io.quarkus', 'org.springframework', 'io.netty'];
    artifact_names VARCHAR[] := ARRAY['commons-lang3', 'guava', 'quarkus-core', 'spring-boot', 'netty-core'];
    versions VARCHAR[] := ARRAY['1.0.0', '1.1.0', '2.0.0', '2.1.0', '3.0.0'];
BEGIN
    FOR i IN 1..100 LOOP
        -- Select random repository (1-5)
        repo_id := floor(random() * 5) + 1;
        
        -- Insert component
        INSERT INTO vt_test.components (
            repository_id, 
            group_id, 
            artifact_id, 
            version, 
            format,
            last_updated,
            last_downloaded,
            download_count
        ) VALUES (
            repo_id,
            group_names[floor(random() * 5) + 1],
            artifact_names[floor(random() * 5) + 1],
            versions[floor(random() * 5) + 1],
            (SELECT format FROM vt_test.repositories WHERE id = repo_id),
            CURRENT_TIMESTAMP - (random() * INTERVAL '30 days'),
            CASE WHEN random() > 0.3 THEN CURRENT_TIMESTAMP - (random() * INTERVAL '5 days') ELSE NULL END,
            floor(random() * 1000)
        ) RETURNING id INTO comp_id;
        
        -- Insert 1-3 assets for this component
        FOR j IN 1..floor(random() * 3) + 1 LOOP
            INSERT INTO vt_test.assets (
                component_id,
                repository_id,
                path,
                content_type,
                size_bytes,
                sha1,
                md5,
                last_updated,
                last_downloaded,
                download_count
            ) VALUES (
                comp_id,
                repo_id,
                'path/to/asset/' || comp_id || '/' || j,
                CASE 
                    WHEN repo_id = 1 THEN 'application/java-archive'
                    WHEN repo_id = 2 THEN 'application/json'
                    WHEN repo_id = 3 THEN 'application/octet-stream'
                    WHEN repo_id = 4 THEN 'application/xml'
                    ELSE 'text/plain'
                END,
                floor(random() * 10000000),
                md5(random()::text)::text,
                md5(random()::text)::text,
                CURRENT_TIMESTAMP - (random() * INTERVAL '30 days'),
                CASE WHEN random() > 0.3 THEN CURRENT_TIMESTAMP - (random() * INTERVAL '5 days') ELSE NULL END,
                floor(random() * 500)
            );
        END LOOP;
    END LOOP;
END;
$$;

-- ==========================================
-- Tables for testing migration operations
-- ==========================================

-- Create a table with an old schema (to be migrated)
CREATE TABLE vt_test.tasks_v1 (
    id SERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_run TIMESTAMP,
    schedule VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT true,
    properties TEXT -- Stored as serialized properties
);

-- Insert sample task data
INSERT INTO vt_test.tasks_v1 (name, type, status, last_run, schedule, enabled, properties) VALUES
('cleanup-old-snapshots', 'cleanup', 'WAITING', NULL, '0 0 1 * * ?', true, 'repository=maven-snapshots\nminimumRetained=3\nremoveIfReleased=true'),
('rebuild-maven-metadata', 'rebuild-metadata', 'WAITING', NULL, '0 0 4 * * ?', true, 'repository=maven-central'),
('compact-blobstore', 'blobstore-compact', 'WAITING', NULL, '0 0 2 ? * SUN', true, 'blobstore=default'),
('vacuum-db', 'db-vacuum', 'WAITING', NULL, '0 0 3 ? * SUN', false, 'analyze=true\nvacuum=true');

-- ==========================================
-- Views and procedures for testing complex operations
-- ==========================================

-- Create a view for component statistics
CREATE VIEW vt_test.component_stats AS
SELECT 
    r.name AS repository_name,
    r.format,
    COUNT(DISTINCT c.id) AS component_count,
    COUNT(a.id) AS asset_count,
    SUM(a.size_bytes) AS total_size_bytes,
    MAX(a.last_downloaded) AS last_download,
    SUM(a.download_count) AS total_downloads
FROM vt_test.repositories r
LEFT JOIN vt_test.components c ON r.id = c.repository_id
LEFT JOIN vt_test.assets a ON c.id = a.component_id
GROUP BY r.name, r.format;

-- Create a stored procedure for testing long-running operations
CREATE OR REPLACE PROCEDURE vt_test.simulate_long_operation(
    p_duration_ms INTEGER DEFAULT 1000
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_start TIMESTAMP;
    v_elapsed INTERVAL;
BEGIN
    v_start := clock_timestamp();
    LOOP
        v_elapsed := clock_timestamp() - v_start;
        EXIT WHEN extract(milliseconds from v_elapsed) >= p_duration_ms;
        -- Perform some work to avoid tight loop
        PERFORM pg_sleep(0.01);
    END LOOP;
    
    -- Return some data to make the procedure useful
    CREATE TEMPORARY TABLE IF NOT EXISTS temp_result AS
    SELECT 
        r.name AS repository_name,
        COUNT(c.id) AS component_count,
        SUM(a.size_bytes) AS total_size
    FROM vt_test.repositories r
    LEFT JOIN vt_test.components c ON r.id = c.repository_id
    LEFT JOIN vt_test.assets a ON c.id = a.component_id
    GROUP BY r.name;
    
    -- Clean up
    DROP TABLE IF EXISTS temp_result;
END;
$$;

-- ==========================================
-- Migration test cases
-- ==========================================

-- 1. Create a new version of the tasks table (migration target)
CREATE TABLE vt_test.tasks_v2 (
    id SERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_run TIMESTAMP,
    next_run TIMESTAMP, -- New field
    schedule VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT true,
    properties JSONB, -- Changed from TEXT to JSONB
    alert_on_failure BOOLEAN NOT NULL DEFAULT false, -- New field
    owner_id INTEGER REFERENCES vt_test.users(id), -- New field
    last_run_duration_ms INTEGER -- New field
);

-- 2. Create a function to migrate data from v1 to v2 (tests complex transaction)
CREATE OR REPLACE FUNCTION vt_test.migrate_tasks_v1_to_v2() 
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_count INTEGER := 0;
    v_task RECORD;
    v_properties JSONB;
    v_next_run TIMESTAMP;
BEGIN
    -- Process each task from v1
    FOR v_task IN SELECT * FROM vt_test.tasks_v1 LOOP
        -- Convert properties from text to JSON
        BEGIN
            -- Parse the properties string into a JSONB object
            WITH props AS (
                SELECT 
                    array_agg(SUBSTRING(p FROM 1 FOR position('=' IN p) - 1)) AS keys,
                    array_agg(SUBSTRING(p FROM position('=' IN p) + 1)) AS values
                FROM unnest(string_to_array(v_task.properties, E'\n')) AS p
                WHERE p LIKE '%=%'
            )
            SELECT jsonb_object(keys, values) INTO v_properties FROM props;
        EXCEPTION WHEN OTHERS THEN
            -- If parsing fails, store as a single JSON property
            v_properties := jsonb_build_object('rawProperties', v_task.properties);
        END;
        
        -- Calculate next run time based on schedule (simplified)
        IF v_task.schedule IS NOT NULL AND v_task.enabled THEN
            v_next_run := CURRENT_TIMESTAMP + INTERVAL '1 day';
        ELSE
            v_next_run := NULL;
        END IF;
        
        -- Insert into v2 table
        INSERT INTO vt_test.tasks_v2 (
            id,
            name,
            type,
            status,
            created,
            last_run,
            next_run,
            schedule,
            enabled,
            properties,
            alert_on_failure,
            owner_id,
            last_run_duration_ms
        ) VALUES (
            v_task.id,
            v_task.name,
            v_task.type,
            v_task.status,
            v_task.created,
            v_task.last_run,
            v_next_run,
            v_task.schedule,
            v_task.enabled,
            v_properties,
            false, -- Default for alert_on_failure
            1, -- Default owner (admin)
            NULL -- No duration data available
        );
        
        v_count := v_count + 1;
        
        -- Simulate some work to test long-running transactions
        PERFORM pg_sleep(0.1);
    END LOOP;
    
    RETURN v_count;
END;
$$;

-- ==========================================
-- Test queries that might cause thread pinning
-- ==========================================

-- 1. Complex join query with potential for long execution
CREATE OR REPLACE FUNCTION vt_test.find_unused_components(p_days INTEGER) 
RETURNS TABLE (
    component_id INTEGER,
    repository_name VARCHAR,
    group_id VARCHAR,
    artifact_id VARCHAR,
    version VARCHAR,
    created_at TIMESTAMP,
    days_since_last_download INTEGER
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    WITH last_downloads AS (
        SELECT 
            c.id AS component_id,
            MAX(a.last_downloaded) AS last_download
        FROM vt_test.components c
        LEFT JOIN vt_test.assets a ON c.id = a.component_id
        GROUP BY c.id
    )
    SELECT 
        c.id,
        r.name,
        c.group_id,
        c.artifact_id,
        c.version,
        c.created_at,
        CASE 
            WHEN ld.last_download IS NULL THEN 
                EXTRACT(DAY FROM (CURRENT_TIMESTAMP - c.created_at))::INTEGER
            ELSE 
                EXTRACT(DAY FROM (CURRENT_TIMESTAMP - ld.last_download))::INTEGER
        END AS days_since_last_download
    FROM vt_test.components c
    JOIN vt_test.repositories r ON c.repository_id = r.id
    LEFT JOIN last_downloads ld ON c.id = ld.component_id
    WHERE 
        (ld.last_download IS NULL AND c.created_at < (CURRENT_TIMESTAMP - (p_days || ' days')::INTERVAL)) OR
        (ld.last_download < (CURRENT_TIMESTAMP - (p_days || ' days')::INTERVAL))
    ORDER BY days_since_last_download DESC;
    
    -- Simulate some work
    PERFORM pg_sleep(0.5);
END;
$$;

-- 2. Function that performs a batch update (tests transaction handling)
CREATE OR REPLACE FUNCTION vt_test.update_download_counts() 
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_count INTEGER := 0;
    v_batch_size INTEGER := 10;
    v_last_id INTEGER := 0;
    v_max_id INTEGER;
    v_current_batch INTEGER;
BEGIN
    -- Get the maximum asset ID
    SELECT MAX(id) INTO v_max_id FROM vt_test.assets;
    
    -- Process in batches
    WHILE v_last_id < v_max_id LOOP
        -- Update a batch of records
        WITH updated AS (
            UPDATE vt_test.assets
            SET 
                download_count = download_count + floor(random() * 10)::INTEGER,
                last_downloaded = CASE 
                                    WHEN random() > 0.5 THEN CURRENT_TIMESTAMP 
                                    ELSE last_downloaded 
                                  END
            WHERE id > v_last_id
            ORDER BY id
            LIMIT v_batch_size
            RETURNING id
        )
        SELECT COUNT(*), MAX(id) INTO v_current_batch, v_last_id FROM updated;
        
        v_count := v_count + v_current_batch;
        
        -- Simulate some work between batches
        PERFORM pg_sleep(0.05);
    END LOOP;
    
    RETURN v_count;
END;
$$;

-- ==========================================
-- Test concurrent operations
-- ==========================================

-- Create a table for testing concurrent inserts
CREATE TABLE vt_test.concurrent_test (
    id SERIAL PRIMARY KEY,
    thread_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    random_data TEXT
);

-- Create a function that inserts data with some delay (to test concurrent execution)
CREATE OR REPLACE FUNCTION vt_test.insert_with_thread_name(p_thread_name VARCHAR, p_delay_ms INTEGER DEFAULT 100) 
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_id INTEGER;
BEGIN
    -- Simulate some work
    PERFORM pg_sleep(p_delay_ms / 1000.0);
    
    -- Insert a record
    INSERT INTO vt_test.concurrent_test (thread_name, random_data)
    VALUES (p_thread_name, md5(random()::text))
    RETURNING id INTO v_id;
    
    RETURN v_id;
END;
$$;

-- ==========================================
-- Test locking scenarios
-- ==========================================

-- Create a table for testing row-level locks
CREATE TABLE vt_test.lock_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    value INTEGER NOT NULL,
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 1
);

-- Insert initial data
INSERT INTO vt_test.lock_test (name, value) VALUES
('counter1', 0),
('counter2', 0),
('counter3', 0),
('counter4', 0),
('counter5', 0);

-- Create a function that updates a counter with explicit locking
CREATE OR REPLACE FUNCTION vt_test.increment_counter_with_lock(p_name VARCHAR, p_increment INTEGER DEFAULT 1, p_delay_ms INTEGER DEFAULT 500) 
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_current_value INTEGER;
    v_new_value INTEGER;
    v_version INTEGER;
BEGIN
    -- Lock the row for update
    SELECT value, version INTO v_current_value, v_version
    FROM vt_test.lock_test
    WHERE name = p_name
    FOR UPDATE;
    
    -- Simulate some processing time while holding the lock
    PERFORM pg_sleep(p_delay_ms / 1000.0);
    
    -- Update the value
    v_new_value := v_current_value + p_increment;
    
    UPDATE vt_test.lock_test
    SET 
        value = v_new_value,
        version = version + 1,
        last_updated = CURRENT_TIMESTAMP
    WHERE name = p_name;
    
    RETURN v_new_value;
END;
$$;

-- ==========================================
-- Test data for validating JDBC driver compatibility
-- ==========================================

-- Create a table with various data types to test type handling
CREATE TABLE vt_test.data_types_test (
    id SERIAL PRIMARY KEY,
    col_varchar VARCHAR(255),
    col_text TEXT,
    col_int INTEGER,
    col_bigint BIGINT,
    col_decimal DECIMAL(10,2),
    col_timestamp TIMESTAMP,
    col_date DATE,
    col_time TIME,
    col_boolean BOOLEAN,
    col_bytea BYTEA,
    col_json JSON,
    col_jsonb JSONB,
    col_array INTEGER[],
    col_uuid UUID
);

-- Insert sample data with various types
INSERT INTO vt_test.data_types_test (
    col_varchar, col_text, col_int, col_bigint, col_decimal, 
    col_timestamp, col_date, col_time, col_boolean, 
    col_bytea, col_json, col_jsonb, col_array, col_uuid
) VALUES (
    'Test String', 'Longer text content for testing', 42, 9223372036854775807, 123.45,
    CURRENT_TIMESTAMP, CURRENT_DATE, CURRENT_TIME, true,
    '\xDEADBEEF', '{"key": "value"}', '{"nested": {"data": true}}', ARRAY[1,2,3,4,5],
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'
);

-- ==========================================
-- Cleanup function (for test teardown)
-- ==========================================

CREATE OR REPLACE FUNCTION vt_test.cleanup_all() 
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    -- Drop all tables, views, and functions in the vt_test schema
    DROP SCHEMA IF EXISTS vt_test CASCADE;
END;
$$;

-- ==========================================
-- Comment explaining the purpose of this script
-- ==========================================

COMMENT ON SCHEMA vt_test IS 'Schema for testing database operations with Java 21 Virtual Threads';