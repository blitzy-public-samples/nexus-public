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
package org.sonatype.nexus.internal.security.apikey.upgrade;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.internal.security.apikey.store.ApiKeyData;
import org.sonatype.nexus.internal.security.apikey.store.ApiKeyStoreImpl;
import org.sonatype.nexus.internal.security.apikey.store.ApiKeyStoreV2Impl;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.scheduling.TaskSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.internal.security.apikey.ApiKeyServiceImpl.MIGRATION_COMPLETE;

/**
 * A task which is used to migrate from {@code api_key} to {@code api_key_v2} and encrypt using the
 * {@link SecretsService}
 */
@SuppressWarnings("deprecation")
@Named
public class ApiKeyToSecretsTask
    extends TaskSupport
{
  static final String MESSAGE = "Upgrade - moving api keys to v2 table";

  static final String TYPE_ID = "nexus.apikey.secrets";

  private final ApiKeyStoreImpl apiKeyStoreV1;

  private final ApiKeyStoreV2Impl apiKeyStoreV2;

  private final GlobalKeyValueStore kv;

  private final int synchronizationDelayMs;

  @Inject
  public ApiKeyToSecretsTask(
      @Named("v1") final ApiKeyStoreImpl apiKeyStoreV1,
      @Named("v2") final ApiKeyStoreV2Impl apiKeyStoreV2,
      @Named("${nexus.distributed.events.fetch.interval.seconds:-5}") final int interval,
      final GlobalKeyValueStore kv)
  {
    this.apiKeyStoreV1 = checkNotNull(apiKeyStoreV1);
    this.apiKeyStoreV2 = checkNotNull(apiKeyStoreV2);
    this.kv = checkNotNull(kv);
    // We convert this to millis and double it to allow for a good window
    this.synchronizationDelayMs = interval * 2_000;
  }

  @Override
  public String getMessage() {
    return MESSAGE;
  }

  @Override
  protected Object execute() throws Exception {
    final int pageSize = 100;

    // Initial migration phase using structured concurrency for better error handling
    try (ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
      OffsetDateTime last = OffsetDateTime.now().withYear(2000); // 2000 for the beginning of time
      Collection<ApiKeyData> data;
      
      do {
        data = apiKeyStoreV1.browseAllSince(last, pageSize);
        
        // Process each batch of records concurrently using virtual threads
        AtomicReference<OffsetDateTime> latestTimestamp = new AtomicReference<>(last);
        
        for (ApiKeyData key : data) {
          CancelableHelper.checkCancellation();
          
          // Fork a virtual thread for each record migration
          scope.fork(() -> {
            migrateRecord(key);
            // Update the latest timestamp atomically if this record is newer
            updateLatestTimestamp(latestTimestamp, key.getCreated());
            return null;
          });
        }
        
        // Wait for all migrations in this batch to complete
        scope.join();
        // Check for any exceptions and propagate if needed
        scope.throwIfFailed(e -> new RuntimeException("Migration failed", e));
        
        // Update the last timestamp for the next batch
        last = latestTimestamp.get();
      }
      while (data.size() == pageSize);
    }

    // Mark migration as complete
    kv.setBoolean(MIGRATION_COMPLETE, true);

    // Final synchronization phase to catch any records added during migration
    // Using structured concurrency for better coordination
    try (ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
      OffsetDateTime last = OffsetDateTime.now().withYear(2000);
      Collection<ApiKeyData> data;
      final long endTime = System.currentTimeMillis() + synchronizationDelayMs;
      
      while (System.currentTimeMillis() < endTime) {
        data = apiKeyStoreV1.browseAllSince(last, pageSize);
        AtomicReference<OffsetDateTime> latestTimestamp = new AtomicReference<>(last);
        
        for (ApiKeyData key : data) {
          // Fork a virtual thread for each record
          scope.fork(() -> {
            migrateRecord(key);
            updateLatestTimestamp(latestTimestamp, key.getCreated());
            return null;
          });
        }
        
        // Wait for all migrations in this batch to complete
        scope.join();
        // Check for any exceptions and propagate if needed
        scope.throwIfFailed(e -> new RuntimeException("Final synchronization failed", e));
        
        // Update the last timestamp for the next batch
        last = latestTimestamp.get();
        
        // Use virtual thread optimized waiting instead of Thread.sleep
        Thread.sleep(Duration.ofMillis(100));
      }
    }

    return null;
  }

  /**
   * Atomically updates the latest timestamp reference if the provided timestamp is newer.
   */
  private void updateLatestTimestamp(AtomicReference<OffsetDateTime> latestTimestamp, OffsetDateTime timestamp) {
    latestTimestamp.accumulateAndGet(timestamp, (current, update) -> 
        current.isBefore(update) ? update : current);
  }

  private void migrateRecord(final ApiKeyData key) {
    String primary = key.getPrimaryPrincipal();
    String domain = key.getDomain();
    try {
      log.trace("Migrating {} in {}", primary, domain);
      apiKeyStoreV2.persistApiKey(domain, key.getPrincipals(), key.getApiKey(), key.getCreated());
    }
    catch (DuplicateKeyException e) {
      log.debug("ApiKey for {} in {} appears to have been migrated.", primary, domain, e);
      apiKeyStoreV2.getApiKey(domain, key.getPrincipals())
          .filter(existingKey -> existingKey.getCreated().isBefore(key.getCreated()))
          .ifPresent(__ -> {
            log.debug("Replacing older key");
            apiKeyStoreV2.deleteApiKey(domain, key.getPrincipals());
            apiKeyStoreV2.persistApiKey(domain, key.getPrincipals(), key.getApiKey(), key.getCreated());
          });
    }
    catch (Exception e) {
      log.warn("Unable to migrate record for user {} for {}", primary, domain, e);
    }
  }
}