-- H2 Database Schema for Java 21 Virtual Threads Testing
-- This schema provides tables and indexes for validating database operations with Java 21 Virtual Threads

-- Drop tables if they exist to ensure clean state
DROP TABLE IF EXISTS test_blob;
DROP TABLE IF EXISTS test_relationship;
DROP TABLE IF EXISTS test_transaction;
DROP TABLE IF EXISTS test_entity;

-- Main entity table for basic CRUD operations
CREATE TABLE test_entity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    value DOUBLE,
    created TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50),
    data CLOB,
    CONSTRAINT uk_test_entity_name UNIQUE (name)
);

-- Create index for name lookups to test index performance with Virtual Threads
CREATE INDEX idx_test_entity_name ON test_entity(name);

-- Relationship table to test foreign key operations with Virtual Threads
CREATE TABLE test_relationship (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    value DOUBLE,
    CONSTRAINT fk_test_relationship_entity FOREIGN KEY (entity_id) REFERENCES test_entity(id) ON DELETE CASCADE
);

-- Create index on foreign key for relationship lookups
CREATE INDEX idx_test_relationship_entity_id ON test_relationship(entity_id);

-- Transaction table for testing transaction isolation with Virtual Threads
CREATE TABLE test_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    counter INTEGER DEFAULT 0,
    version INTEGER DEFAULT 0,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_test_transaction_name UNIQUE (name)
);

-- Create index for transaction name lookups
CREATE INDEX idx_test_transaction_name ON test_transaction(name);

-- Blob table for testing large binary data operations with Virtual Threads
CREATE TABLE test_blob (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_id BIGINT NOT NULL,
    content BLOB,
    content_type VARCHAR(255),
    size BIGINT,
    CONSTRAINT fk_test_blob_entity FOREIGN KEY (entity_id) REFERENCES test_entity(id) ON DELETE CASCADE
);

-- Create index on entity_id for blob lookups
CREATE INDEX idx_test_blob_entity_id ON test_blob(entity_id);

-- Insert some initial test data for basic operations
INSERT INTO test_entity (name, value, status, data) VALUES 
    ('test1', 1.0, 'ACTIVE', 'This is test data for entity 1'),
    ('test2', 2.0, 'INACTIVE', 'This is test data for entity 2'),
    ('test3', 3.0, 'PENDING', 'This is test data for entity 3');

INSERT INTO test_relationship (entity_id, name, value) VALUES
    (1, 'relation1', 10.0),
    (1, 'relation2', 20.0),
    (2, 'relation3', 30.0);

INSERT INTO test_transaction (name, counter, version) VALUES
    ('transaction1', 0, 0),
    ('transaction2', 0, 0),
    ('transaction3', 0, 0);

-- Create a view to test complex queries with Virtual Threads
CREATE VIEW test_entity_view AS
SELECT e.id, e.name, e.value, e.status, COUNT(r.id) as relation_count
FROM test_entity e
LEFT JOIN test_relationship r ON e.id = r.entity_id
GROUP BY e.id, e.name, e.value, e.status;

-- Create a stored procedure to test callable statements with Virtual Threads
CREATE ALIAS increment_counter AS $$
int incrementCounter(Connection conn, String name) throws SQLException {
    try (PreparedStatement stmt = conn.prepareStatement(
            "UPDATE test_transaction SET counter = counter + 1, version = version + 1, " +
            "last_updated = CURRENT_TIMESTAMP WHERE name = ? RETURNING counter")) {
        stmt.setString(1, name);
        try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return -1;
        }
    }
}
$$;