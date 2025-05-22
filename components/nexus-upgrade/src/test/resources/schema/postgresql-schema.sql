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

--
-- PostgreSQL schema for testing the Nexus upgrade framework with Java 21 compatibility
-- This script creates tables used by various test classes in the upgrade framework
--

--
-- Drop tables if they exist to ensure clean state
--
DROP TABLE IF EXISTS example CASCADE;
DROP TABLE IF EXISTS skipped CASCADE;
DROP TABLE IF EXISTS distributed_auth_ticket_cache CASCADE;
DROP TABLE IF EXISTS test CASCADE;

--
-- Drop custom schema if it exists
--
DROP SCHEMA IF EXISTS custom CASCADE;

--
-- Create 'example' table used by TestMigrationStep
-- This table is used to test basic migration functionality
--
CREATE TABLE example (
  name VARCHAR(50)
);

--
-- Create 'skipped' table used in UpgradeManagerImplTest
-- This table is used to test migration steps that are skipped
--
CREATE TABLE skipped (
  name VARCHAR(50)
);

--
-- Create 'distributed_auth_ticket_cache' table used in DistributedAuthTicketMigrationStep_1_30Test
-- This table is used to test auth ticket migrations
--
CREATE TABLE distributed_auth_ticket_cache (
  user_name VARCHAR(200) NOT NULL
);

--
-- Create custom schema for testing schema-scoped index detection
-- Used in DatabaseMigrationStepTest
--
CREATE SCHEMA custom AUTHORIZATION current_user;

--
-- Create 'test' table in custom schema with primary key constraint
-- This is used to test index operations and schema-scoped index detection
--
CREATE TABLE custom.test (
  domain VARCHAR(200) NOT NULL,
  token VARCHAR(200) NOT NULL,
  CONSTRAINT pk_domain PRIMARY KEY (domain)
);

--
-- Create 'test' table in public schema for comparison testing
-- This allows tests to verify behavior across different schemas
--
CREATE TABLE test (
  domain VARCHAR(200) NOT NULL,
  token VARCHAR(200) NOT NULL,
  CONSTRAINT pk_domain_public PRIMARY KEY (domain)
);

--
-- Add comments to tables for documentation
--
COMMENT ON TABLE example IS 'Table used by TestMigrationStep for basic migration testing';
COMMENT ON TABLE skipped IS 'Table used by UpgradeManagerImplTest for testing skipped migrations';
COMMENT ON TABLE distributed_auth_ticket_cache IS 'Table used for testing auth ticket cache migrations';
COMMENT ON TABLE custom.test IS 'Table in custom schema for testing schema-scoped index detection';
COMMENT ON TABLE test IS 'Table in public schema for comparison with custom schema';

--
-- Grant permissions to ensure tests can access all objects
--
GRANT ALL PRIVILEGES ON SCHEMA custom TO current_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA custom TO current_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO current_user;