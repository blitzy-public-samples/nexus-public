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
package org.sonatype.nexus.repository.content.importtask;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;

/**
 * Provides post-processing capabilities for assets during import tasks.
 * <p>
 * This interface supports both single-asset processing and concurrent processing of multiple assets
 * using Java 21 Virtual Threads. Implementations should ensure thread safety when processing assets
 * concurrently, especially when accessing shared resources.
 * <p>
 * Thread Safety Considerations:
 * <ul>
 *   <li>Implementations must be thread-safe as they may be invoked concurrently from multiple threads</li>
 *   <li>Avoid using synchronized blocks or methods which can cause Virtual Thread pinning</li>
 *   <li>Prefer using concurrent collections and atomic operations over explicit synchronization</li>
 *   <li>Consider using java.util.concurrent utilities like Semaphore for resource throttling</li>
 * </ul>
 *
 * @since 3.29
 */
public interface ImportPostProcessor
{
  /**
   * Performs post-processing on a single asset.
   * <p>
   * This method is called for each asset that needs post-processing during import.
   * Implementations should ensure this method is thread-safe as it may be called
   * concurrently from multiple threads.
   *
   * @param repository the repository containing the asset
   * @param asset the asset to process
   */
  void attributePostProcessing(Repository repository, Asset asset);

  /**
   * Performs concurrent post-processing on multiple assets using Java 21 Virtual Threads.
   * <p>
   * This default implementation processes assets concurrently using Virtual Threads,
   * which are lightweight threads managed by the JVM. This approach is particularly
   * efficient for I/O-bound operations as Virtual Threads automatically yield when
   * blocked, allowing the carrier thread to process other tasks.
   * <p>
   * Resource Management:
   * <ul>
   *   <li>For large collections, consider using the overloaded method with a concurrency limit</li>
   *   <li>Virtual Threads are lightweight but each asset still consumes memory and resources</li>
   *   <li>Be mindful of external resource constraints (database connections, file handles, etc.)</li>
   * </ul>
   *
   * @param repository the repository containing the assets
   * @param assets the collection of assets to process concurrently
   * @throws RuntimeException if any asset processing fails
   */
  default void attributePostProcessingConcurrent(Repository repository, Collection<Asset> assets) {
    attributePostProcessingConcurrent(repository, assets, Integer.MAX_VALUE);
  }

  /**
   * Performs concurrent post-processing on multiple assets with a concurrency limit.
   * <p>
   * This method allows limiting the maximum number of assets processed concurrently,
   * which is useful for controlling resource usage when processing large collections.
   * <p>
   * Implementation Notes:
   * <ul>
   *   <li>Uses Java 21 Virtual Threads for efficient concurrent processing</li>
   *   <li>Automatically handles thread management and error propagation</li>
   *   <li>Collects and propagates exceptions from all asset processing tasks</li>
   *   <li>Uses a semaphore to limit concurrency when specified</li>
   * </ul>
   *
   * @param repository the repository containing the assets
   * @param assets the collection of assets to process concurrently
   * @param maxConcurrency the maximum number of assets to process concurrently
   * @throws RuntimeException if any asset processing fails
   */
  default void attributePostProcessingConcurrent(Repository repository, Collection<Asset> assets, int maxConcurrency) {
    if (assets == null || assets.isEmpty()) {
      return;
    }

    // Use a semaphore to limit concurrency if needed
    final Semaphore semaphore = maxConcurrency < Integer.MAX_VALUE ? new Semaphore(maxConcurrency) : null;
    final List<Future<?>> futures = new ArrayList<>(assets.size());
    final List<Exception> exceptions = new ArrayList<>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit each asset for processing
      for (Asset asset : assets) {
        futures.add(executor.submit(() -> {
          try {
            if (semaphore != null) {
              semaphore.acquire();
            }
            attributePostProcessing(repository, asset);
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
          finally {
            if (semaphore != null) {
              semaphore.release();
            }
          }
        }));
      }

      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Asset processing was interrupted", e);
        }
        catch (ExecutionException e) {
          synchronized (exceptions) {
            exceptions.add(e.getCause() instanceof Exception ? (Exception) e.getCause() : e);
          }
        }
      }
    }

    // If any exceptions occurred, throw a combined exception
    if (!exceptions.isEmpty()) {
      RuntimeException exception = new RuntimeException("Failed to process " + exceptions.size() + " assets");
      exceptions.forEach(exception::addSuppressed);
      throw exception;
    }
  }
}