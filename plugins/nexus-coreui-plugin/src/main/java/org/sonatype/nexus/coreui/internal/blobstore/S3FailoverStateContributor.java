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
package org.sonatype.nexus.coreui.internal.blobstore;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.db.DatabaseCheck;
import org.sonatype.nexus.rapture.StateContributor;

import com.google.common.collect.ImmutableMap;

import static org.sonatype.nexus.blobstore.s3.internal.upgrade.S3FailoverMigrationStep_2_6.S3_FAILOVER_MIGRATION_VERSION;
import static org.sonatype.nexus.common.app.FeatureFlags.CLUSTERED_ZERO_DOWNTIME_ENABLED_NAMED;

/**
 * State contributor to enable regional failover configuration for S3 blob stores.
 * The failover configuration will be available on ZDU if schema version is at least on version 2.6.
 * 
 * Updated for Java 21 compatibility with Virtual Threads and String Templates.
 */
@Named
@Singleton
public class S3FailoverStateContributor
    extends ComponentSupport
    implements StateContributor
{
  private final DatabaseCheck databaseCheck;

  private final boolean zduEnabled;

  @Inject
  public S3FailoverStateContributor(
      final DatabaseCheck databaseCheck,
      @Named(CLUSTERED_ZERO_DOWNTIME_ENABLED_NAMED) final boolean zduEnabled)
  {
    this.databaseCheck = databaseCheck;
    this.zduEnabled = zduEnabled;
  }

  @Nullable
  @Override
  public Map<String, Object> getState() {
    boolean available = isAvailable();
    log.debug(STR."S3 Failover state requested, returning: \{available}");
    return ImmutableMap.of("S3FailoverEnabled", available);
  }

  private boolean isAvailable() {
    // Use CompletableFuture with Virtual Thread for potentially I/O-bound database check
    if (!zduEnabled) {
      log.trace(STR."ZDU is not enabled, S3 Failover is available");
      return true;
    }
    
    try {
      // Run the database check in a virtual thread to avoid blocking platform threads
      return CompletableFuture.supplyAsync(
          () -> {
            boolean result = databaseCheck.isAtLeast(S3_FAILOVER_MIGRATION_VERSION);
            log.trace(STR."Database check for S3 Failover migration version \{S3_FAILOVER_MIGRATION_VERSION} returned: \{result}");
            return result;
          },
          CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS, Thread.ofVirtual().factory())
      ).join();
    } catch (Exception e) {
      log.warn(STR."Error checking database version for S3 Failover: \{e.getMessage()}");
      return false;
    }
  }
}