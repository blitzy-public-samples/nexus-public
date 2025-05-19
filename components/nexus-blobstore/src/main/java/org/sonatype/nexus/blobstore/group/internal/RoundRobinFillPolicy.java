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
package org.sonatype.nexus.blobstore.group.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.group.FillPolicy;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;

import com.google.common.annotations.VisibleForTesting;

import static org.sonatype.nexus.common.app.FeatureFlags.BLOBSTORE_SKIP_ON_SOFTQUOTA_VIOLATION;

/**
 * {@link FillPolicy} that divides writes to member blob stores evenly based upon a round robin selection.
 * <p>
 * This implementation is thread-safe and optimized for high concurrency with Virtual Threads.
 * It uses an AtomicInteger for sequence tracking to ensure consistent round-robin behavior
 * even under high concurrent access from many Virtual Threads.
 *
 * @since 3.14
 */
@Named(RoundRobinFillPolicy.TYPE)
public class RoundRobinFillPolicy
    extends ComponentSupport
    implements FillPolicy
{
  public static final String TYPE = "roundRobin";

  protected static final String NAME = "Round Robin";

  @VisibleForTesting
  AtomicInteger sequence = new AtomicInteger();

  @Inject
  private BlobStoreQuotaService quotaService;

  @Inject
  @Named("${" + BLOBSTORE_SKIP_ON_SOFTQUOTA_VIOLATION + ":-false}")
  boolean skipOnSoftQuotaViolation;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  @Nullable
  public BlobStore chooseBlobStore(
      final BlobStoreGroup blobStoreGroup,
      final Map<String, String> headers)
  {
    return nextMember(blobStoreGroup.getMembers());
  }

  /**
   * Retrieves the next writable member in the group.
   * <p>
   * This method is optimized for high concurrency with Virtual Threads by avoiding unnecessary
   * object creation and using efficient sequence handling. It starts from the next index in the
   * round-robin sequence and checks each member in order until finding a writable one.
   *
   * @param members of the BlobStoreGroup
   * @return the first writable {@link BlobStore} or null if none are writable
   */
  @Nullable
  private BlobStore nextMember(final List<BlobStore> members) {
    if (members.isEmpty()) {
      return null;
    }
    
    final int size = members.size();
    final int startIndex = nextIndex() % size;
    
    log.trace("Starting search from index {}", startIndex);

    // First try from startIndex to the end of the list
    for (int i = startIndex; i < size; i++) {
      BlobStore blobStore = members.get(i);
      if (isWritable(blobStore)) {
        return blobStore;
      }
    }
    
    // If not found, try from the beginning to startIndex
    for (int i = 0; i < startIndex; i++) {
      BlobStore blobStore = members.get(i);
      if (isWritable(blobStore)) {
        return blobStore;
      }
    }
    
    return null;
  }

  /**
   * Checks if a BlobStore is writable and meets all criteria for writing.
   * Extracted as a separate method to improve readability and maintainability.
   *
   * @param blobStore the BlobStore to check
   * @return true if the BlobStore is writable and meets all criteria
   */
  private boolean isWritable(BlobStore blobStore) {
    return blobStore.isWritable() && 
           blobStore.isStorageAvailable() && 
           (skipOnSoftQuotaViolation ? hasNoQuotaViolation(blobStore) : true);
  }

  /**
   * Returns the next index in the round-robin sequence.
   * <p>
   * This method is optimized to reduce contention under high concurrency by using
   * a simple incrementAndGet operation and handling integer overflow safely.
   * 
   * @return the next index to use in the round-robin sequence
   */
  @VisibleForTesting
  int nextIndex() {
    int next = sequence.incrementAndGet();
    // Handle potential overflow by resetting to 0 if negative
    if (next < 0) {
      // Only one thread will succeed in resetting, others will get the updated value
      sequence.compareAndSet(next, 0);
      return 0;
    }
    return next;
  }

  /**
   * Checks if a BlobStore has no quota violation.
   *
   * @param blobStore the BlobStore to check
   * @return true if the BlobStore has no quota violation
   */
  private boolean hasNoQuotaViolation(final BlobStore blobStore) {
    BlobStoreQuotaResult result = quotaService.checkQuota(blobStore);
    if (result != null && result.isViolation()) {
      if (log.isTraceEnabled()) {
        log.info("Skipping blobStore {} due to soft-quota violation: {}", result.getBlobStoreName(),
            result.getMessage());
      }
      else {
        log.info("Skipping blobStore {} due to soft-quota violation", result.getBlobStoreName());
      }
      return false;
    }
    return true;
  }
}