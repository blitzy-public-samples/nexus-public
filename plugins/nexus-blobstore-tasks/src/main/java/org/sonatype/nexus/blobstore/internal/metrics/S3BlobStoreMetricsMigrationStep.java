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
package org.sonatype.nexus.blobstore.internal.metrics;

import java.io.IOException;
import java.sql.Connection;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsEntity;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.cooperation2.Cooperation2;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.kv.ValueType;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.UPGRADE;

/**
 * Migration step to move metrics from properties files in the blob store to the DB.
 *
 * For historical reasons we need to verify whether it was previously migrated.
 */
@ManagedLifecycle(phase = UPGRADE)
@Named
@Singleton
public class S3BlobStoreMetricsMigrationStep
    extends BlobStoreMetricsDatabaseMigrationStepSupport
{
  private static final String BLOB_STORE_TYPE = "S3";

  private static final String NAME = S3BlobStoreMetricsMigrationStep.class.getSimpleName();

  private final ObjectMapper objectMapper;

  private final GlobalKeyValueStore kv;

  private final Cooperation2 cooperation;

  private Set<String> namesToMigrate;

  @Inject
  public S3BlobStoreMetricsMigrationStep(
      final GlobalKeyValueStore kv,
      final ObjectMapper objectMapper,
      final Cooperation2Factory cooperationFactory)
  {
    super(BLOB_STORE_TYPE);
    this.kv = checkNotNull(kv);
    this.objectMapper = checkNotNull(objectMapper);
    this.cooperation = checkNotNull(cooperationFactory).configure()
        .build(NAME);
  }

  @Override
  protected void doStart() throws IOException {
    // On clustered systems upgrades may not run at startup, so we need to determine what needs migrating before the
    // system might write to the DB.
    // Cooperation is used to avoid a race where a secondary node overwrites the first values
    // Use Virtual Thread for startup operations
    try (var executor = newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Set<String>> startupTask = CompletableFuture.supplyAsync(() -> {
        return cooperation.on(() -> {
            namesToMigrate = load();

            if (namesToMigrate == null) {
              Collection<String> s3BlobStores = compute();

              kv.setKey(new NexusKeyValue(NAME, ValueType.OBJECT, s3BlobStores));
            }

            return namesToMigrate;
          })
          // Always check when the remote did the work just in case
          .checkFunction(() -> Optional.ofNullable(load()))
          .cooperate(NAME);
      }, executor);
      
      // Wait for the startup task to complete
      startupTask.join();
    }
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Use Virtual Thread for database migration operations
    try (var executor = newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> migrationTask = CompletableFuture.runAsync(() -> {
        try {
          super.migrate(connection);
          kv.removeKey(NAME);
        } catch (Exception e) {
          throw new RuntimeException("Error during S3 metrics migration", e);
        }
      }, executor);
      
      // Wait for the migration to complete
      migrationTask.join();
    }
  }

  @Override
  public Integer getChecksum() {
    return 0;
  }

  @Override
  protected boolean shouldSaveMetricsToDatabase(final String blobStoreName) {
    if (namesToMigrate == null) {
      namesToMigrate = compute();
    }
    return namesToMigrate.contains(blobStoreName);
  }

  private Set<String> load() {
    // Use enhanced Optional handling with pattern matching in Java 21
    return kv.getKey(NAME)
      .map(val -> val.getAsObjectList(objectMapper, String.class))
      .map(list -> new HashSet<>(list))
      .orElse(null);
  }

  private Set<String> compute() {
    // Use parallel stream for potentially better performance with Virtual Threads
    return getBlobStoreConfigurations()
      .parallel()
      .filter(this::emptyOrWrong)
      .collect(Collectors.toSet());
  }

  private boolean emptyOrWrong(final String blobStoreName) {
    BlobStoreMetricsEntity metricsFromDb = metricsStore.get(blobStoreName);
    // Use pattern matching for instanceof check with Java 21
    if (metricsFromDb instanceof BlobStoreMetricsEntity metrics) {
      return metrics.getBlobCount() <= 0L;
    }
    return true; // No metrics found, needs migration
  }
}