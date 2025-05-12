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
package org.sonatype.nexus.testsuite.testsupport.config;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED_NAMED;

/**
 * Configuration class for database settings in test environments.
 * <p>
 * This class provides information about the current database configuration,
 * including whether the datastore is enabled and which database type is in use.
 * <p>
 * Compatible with Java 21, Guice 7.0.0, and Eclipse Sisu 0.10.0.
 *
 * @since 3.60
 */
@Named
@Singleton
public class DatabaseConfig
{
  private final boolean datastoreEnabled;

  private final String jdbcUrl;

  /**
   * Constructor with dependency injection support for Jakarta EE.
   *
   * @param datastoreEnabled whether the datastore is enabled
   * @param jdbcUrl the JDBC URL for the datastore connection, may be null
   */
  @Inject
  public DatabaseConfig(
      @Named(DATASTORE_ENABLED_NAMED) final boolean datastoreEnabled,
      @Nullable @Named("nexus.datastore.nexus.jdbcUrl") final String jdbcUrl)
  {
    this.datastoreEnabled = datastoreEnabled;
    this.jdbcUrl = jdbcUrl;
  }

  /**
   * Checks if OrientDB is being used.
   *
   * @return true if OrientDB is the active database
   */
  public boolean isOrient() {
    return !datastoreEnabled;
  }

  /**
   * Checks if H2 database is being used.
   *
   * @return true if H2 is the active database
   */
  public boolean isH2() {
    return datastoreEnabled && (!isPostgresql());
  }

  /**
   * Checks if PostgreSQL database is being used.
   * <p>
   * Uses robust JDBC URL validation compatible with Java 21 runtime semantics.
   *
   * @return true if PostgreSQL is the active database
   */
  public boolean isPostgresql() {
    return datastoreEnabled && jdbcUrl != null && 
        (jdbcUrl.startsWith("postgresql:") || jdbcUrl.startsWith("jdbc:postgresql:"));
  }
}