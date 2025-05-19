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
package org.sonatype.nexus.blobstore.quota;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;

/**
 * For a {@link BlobStore}, checks its usage against its quota.
 * <p>
 * Implementations of this interface must be thread-safe as they may be accessed concurrently
 * by multiple threads, including Java 21 Virtual Threads. This is particularly important as
 * quota checking operations typically involve I/O operations which are well-suited for
 * Virtual Thread execution.
 * <p>
 * When implementing this interface, consider the following thread safety guidelines:
 * <ul>
 *   <li>Ensure all shared state is properly synchronized or uses thread-safe data structures</li>
 *   <li>Avoid using ThreadLocal variables for caching as they may lead to excessive memory usage
 *       with large numbers of Virtual Threads</li>
 *   <li>Design implementations to be non-blocking where possible to maximize the benefits of
 *       Virtual Threads for I/O-bound operations</li>
 *   <li>Ensure any resources are properly managed to prevent leaks across thread boundaries</li>
 * </ul>
 *
 * @since 3.14
 */
public interface BlobStoreQuota
{
  /**
   * Ensure that the configuration has all the needed values.
   * <p>
   * This method should be thread-safe as it may be called concurrently from multiple threads,
   * including Virtual Threads. Implementations should avoid modifying the provided configuration
   * object and should only validate its contents.
   * 
   * @param config - the configuration to be validated
   * @since 3.15
   */
  void validateConfig(BlobStoreConfiguration config);

  /**
   * Checks the usage of the provided blob store against its quota.
   * <p>
   * This method is expected to be called concurrently from multiple threads, including Virtual Threads.
   * Implementations must ensure thread safety and should be designed to efficiently handle I/O operations
   * that may occur during quota calculation. When running with Virtual Threads, this method will benefit
   * from non-blocking I/O operations as Virtual Threads are optimized for I/O-bound workloads.
   * <p>
   * Implementations should avoid holding locks for extended periods and should be designed
   * to minimize contention when accessed concurrently.
   *
   * @param blobStore - a blob store whose quota needs to be evaluated
   * @return {@link BlobStoreQuotaResult} containing the quota check results
   */
  BlobStoreQuotaResult check(BlobStore blobStore);

  /**
   * Returns the human-readable display name of this quota implementation.
   * <p>
   * This method should be thread-safe and preferably return an immutable or effectively immutable result.
   *
   * @return the display name of this quota implementation
   */
  String getDisplayName();

  /**
   * Returns the unique identifier for this quota implementation.
   * <p>
   * This method should be thread-safe and preferably return an immutable or effectively immutable result.
   *
   * @return the unique identifier for this quota implementation
   */
  String getId();
}