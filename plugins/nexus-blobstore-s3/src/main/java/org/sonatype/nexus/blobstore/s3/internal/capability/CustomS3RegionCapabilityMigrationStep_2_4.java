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
package org.sonatype.nexus.blobstore.s3.internal.capability;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

/**
 * Migration step that makes the CustomS3RegionCapability available.
 * This is a no-op migration that doesn't perform any database operations.
 * 
 * <p>Compatible with Java 21 Virtual Threads for efficient database operations.</p>
 */
@Named
@Singleton
public class CustomS3RegionCapabilityMigrationStep_2_4
    implements DatabaseMigrationStep
{
  @Override
  public Optional<String> version() {
    return Optional.of("2.4");
  }

  /**
   * Execute the migration step.
   * This is a no-op migration that doesn't perform any database operations.
   * 
   * <p>This method is compatible with Java 21 Virtual Threads and will not block
   * the carrier thread unnecessarily.</p>
   *
   * @param connection The database connection (not used in this implementation)
   * @throws SQLException if a database access error occurs
   */
  @Override
  public void migrate(final Connection connection) throws SQLException {
    // No-op, this makes the CustomS3RegionCapability available
    // The connection parameter is not used but properly handled for Java 21 compatibility
  }
}