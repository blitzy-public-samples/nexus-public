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
package org.sonatype.nexus.testsuite.testsupport.cleanup;

import java.util.concurrent.CompletableFuture;

/**
 * A test helper for the component cleanup feature. This is used to abstract the differences between Orient and SQL
 * databases, primarily that Orient uses the elastic search index while SQL uses the component/asset tables.
 * <p>
 * This interface has been updated for Java 21 to support virtual threads for more efficient waiting operations.
 * Implementations should leverage virtual threads where appropriate to improve performance and resource utilization.
 */
public interface CleanupTestHelper
{
  /**
   * Wait for mixed search to complete.
   * <p>
   * This method may leverage virtual threads for efficient waiting without blocking platform threads.
   */
  void waitForMixedSearch();

  /**
   * Wait for the specified number of components to be present in the index used by Cleanup.
   * <p>
   * This method may leverage virtual threads for efficient waiting without blocking platform threads.
   *
   * @param count the expected number of components
   */
  void waitForComponentsIndexed(int count);

  /**
   * Wait for the specified number of components to have the last download property set in the index used by Cleanup.
   * <p>
   * This method may leverage virtual threads for efficient waiting without blocking platform threads.
   *
   * @param count the expected number of components with last download property set
   */
  void waitForLastDownloadSet(int count);

  /**
   * Wait until the blobs indexed are at least older than the provided number of seconds.
   * <p>
   * This method may leverage virtual threads for efficient waiting without blocking platform threads.
   *
   * @param time the minimum age in seconds
   */
  void awaitLastBlobUpdatedTimePassed(final int time);

  /**
   * Wait until any active indexing operations have completed.
   * <p>
   * This method may leverage virtual threads for efficient waiting without blocking platform threads.
   */
  void waitForIndex();
  
  /**
   * Asynchronously wait for mixed search to complete.
   * <p>
   * This method leverages virtual threads for non-blocking waiting operations.
   *
   * @return a CompletableFuture that completes when mixed search is ready
   */
  default CompletableFuture<Void> waitForMixedSearchAsync() {
    return CompletableFuture.runAsync(this::waitForMixedSearch, Thread.ofVirtual().factory());
  }

  /**
   * Asynchronously wait for the specified number of components to be present in the index used by Cleanup.
   * <p>
   * This method leverages virtual threads for non-blocking waiting operations.
   *
   * @param count the expected number of components
   * @return a CompletableFuture that completes when the expected number of components are indexed
   */
  default CompletableFuture<Void> waitForComponentsIndexedAsync(int count) {
    return CompletableFuture.runAsync(() -> waitForComponentsIndexed(count), Thread.ofVirtual().factory());
  }

  /**
   * Asynchronously wait for the specified number of components to have the last download property set.
   * <p>
   * This method leverages virtual threads for non-blocking waiting operations.
   *
   * @param count the expected number of components with last download property set
   * @return a CompletableFuture that completes when the expected number of components have last download set
   */
  default CompletableFuture<Void> waitForLastDownloadSetAsync(int count) {
    return CompletableFuture.runAsync(() -> waitForLastDownloadSet(count), Thread.ofVirtual().factory());
  }

  /**
   * Asynchronously wait until the blobs indexed are at least older than the provided number of seconds.
   * <p>
   * This method leverages virtual threads for non-blocking waiting operations.
   *
   * @param time the minimum age in seconds
   * @return a CompletableFuture that completes when blobs are older than the specified time
   */
  default CompletableFuture<Void> awaitLastBlobUpdatedTimePassedAsync(final int time) {
    return CompletableFuture.runAsync(() -> awaitLastBlobUpdatedTimePassed(time), Thread.ofVirtual().factory());
  }

  /**
   * Asynchronously wait until any active indexing operations have completed.
   * <p>
   * This method leverages virtual threads for non-blocking waiting operations.
   *
   * @return a CompletableFuture that completes when indexing is complete
   */
  default CompletableFuture<Void> waitForIndexAsync() {
    return CompletableFuture.runAsync(this::waitForIndex, Thread.ofVirtual().factory());
  }
}