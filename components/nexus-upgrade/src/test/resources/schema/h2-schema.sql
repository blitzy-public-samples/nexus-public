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

-- H2 Database Schema for testing Nexus upgrade framework with Java 21 compatibility
-- This schema is designed to work with H2 database version 2.2.224+ which is certified for Java 21
-- The tables defined here are used in various database migration step tests

-- Table used by TestMigrationStep for basic migration testing
-- This table is created to verify JDBC operations with Java 21 virtual threads
CREATE TABLE IF NOT EXISTS example (
    name VARCHAR(50)
);

-- Table used in UpgradeManagerImplTest for testing skipped migration steps
-- Tests verify that thread pinning is minimized with compatible JDBC drivers
CREATE TABLE IF NOT EXISTS skipped (
    name VARCHAR(50) NOT NULL
);

-- Table used in DistributedAuthTicketMigrationStep_1_30Test for testing auth ticket migrations
-- Connection pool behavior is tested under virtual thread scheduling
CREATE TABLE IF NOT EXISTS distributed_auth_ticket_cache (
    user_name VARCHAR(200) NOT NULL
);

-- Table used in DatabaseMigrationStepTest for testing index operations
-- Tests verify that JDBC Driver is compatible with Java 21
CREATE TABLE IF NOT EXISTS test (
    domain VARCHAR(200) NOT NULL,
    token VARCHAR(200) NOT NULL,
    CONSTRAINT pk_domain PRIMARY KEY (domain)
);