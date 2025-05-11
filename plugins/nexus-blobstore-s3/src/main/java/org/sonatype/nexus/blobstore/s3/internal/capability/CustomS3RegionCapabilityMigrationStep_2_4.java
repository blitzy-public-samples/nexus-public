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
 *
 * This class is compatible with Java 21 and OSGi/Karaf 4.4.4.
 */
package org.sonatype.nexus.blobstore.s3.internal.capability;

import java.sql.Connection;
import java.util.Optional;

// Using javax.inject which is still valid in Java 21
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

/**
 * Migration step that makes the CustomS3RegionCapability available.
 * 
 * @since 3.38
 * @see CustomS3RegionCapability
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

  @Override
  public void migrate(final Connection connection) throws Exception {
    // No-op, this makes the CustomS3RegionCapability available
    // This method could potentially benefit from Virtual Threads in Java 21 if it performed I/O operations
  }
}