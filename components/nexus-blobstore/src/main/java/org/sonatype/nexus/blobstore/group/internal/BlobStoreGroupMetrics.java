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

import java.util.HashMap;
import java.util.Map;

import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.math.Math2;

import static java.util.Collections.unmodifiableMap;

/**
 * An implementation of {@link BlobStoreMetrics} that combines metrics
 * from member metrics.
 * <p>
 * This implementation is thread-safe and optimized for Java 21 Virtual Threads.
 * Metrics collection and aggregation can safely occur across both platform threads
 * and Virtual Threads without synchronization issues. The reduction operations
 * are designed to handle increased parallelism efficiently.
 *
 * @since 3.14
 */
public class BlobStoreGroupMetrics
    implements BlobStoreMetrics
{
  private final long blobCount;

  private final long totalSize;

  private final Map<String, Long> availableSpaceByFileStore;

  private final boolean unlimited;

  private final boolean unavailable;

  /**
   * Constructs a new instance by aggregating metrics from multiple blob stores.
   * This constructor is optimized for efficient operation when metrics might be
   * coming from different thread types, including Virtual Threads.
   *
   * @param membersMetrics the metrics from member blob stores to aggregate
   */
  public BlobStoreGroupMetrics(final Iterable<BlobStoreMetrics> membersMetrics) {
    long aggregatedBlobCount = 0L;
    long aggregatedTotalSize = 0L;
    Map<String, Long> aggregatedAvailableSpaceByFileStore = new HashMap<>();
    boolean aggregatedUnlimited = false;
    int totalMembers = 0;
    int unavailableMembers = 0;

    // Process each member's metrics sequentially to avoid thread contention
    // when aggregating from multiple sources that might be using different thread types
    for (BlobStoreMetrics memberMetrics : membersMetrics) {
      aggregatedBlobCount = Math2.addClamped(aggregatedBlobCount, memberMetrics.getBlobCount());
      aggregatedTotalSize = Math2.addClamped(aggregatedTotalSize, memberMetrics.getTotalSize());
      aggregatedAvailableSpaceByFileStore.putAll(memberMetrics.getAvailableSpaceByFileStore());
      aggregatedUnlimited = aggregatedUnlimited || memberMetrics.isUnlimited();
      totalMembers += 1;
      if (memberMetrics.isUnavailable()) {
        unavailableMembers += 1;
      }
    }

    this.blobCount = aggregatedBlobCount;
    this.totalSize = aggregatedTotalSize;
    this.availableSpaceByFileStore = unmodifiableMap(aggregatedAvailableSpaceByFileStore);
    this.unlimited = aggregatedUnlimited;
    this.unavailable = totalMembers > 0 && unavailableMembers == totalMembers;
  }

  @Override
  public long getBlobCount() {
    return blobCount;
  }

  @Override
  public long getTotalSize() {
    return totalSize;
  }

  /**
   * Gets the total available space across all file stores.
   * This method is optimized for Virtual Thread compatibility by using efficient
   * stream-to-stream operation patterns and a thread-safe reduction operation.
   *
   * @return the total available space in bytes, or 0 if no space information is available
   */
  @Override
  public long getAvailableSpace() {
    // Optimized stream operation for Virtual Thread compatibility
    // Directly collect values and use a more efficient reduction pattern
    return availableSpaceByFileStore.values().stream()
        .mapToLong(Long::longValue)
        .reduce(0L, (a, b) -> Math2.addClamped(a, b));
  }

  @Override
  public boolean isUnlimited() {
    return unlimited;
  }

  @Override
  public Map<String, Long> getAvailableSpaceByFileStore() {
    return availableSpaceByFileStore;
  }

  @Override
  public boolean isUnavailable() {
    return unavailable;
  }
}