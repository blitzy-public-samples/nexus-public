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
package org.sonatype.nexus.blobstore;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.sonatype.goodies.common.Locks;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.common.thread.ThreadHelper;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Support for {@link Blob} implementations with support for locking.
 * This class has been updated to work efficiently with Java 21 Virtual Threads by using
 * ReentrantLock instead of synchronized blocks to avoid Virtual Thread pinning, and by ensuring
 * proper memory visibility with volatile variables.
 *
 * @since 3.3
 * @see java.util.concurrent.locks.ReentrantLock
 * @see java.lang.Thread#startVirtualThread
 */
public abstract class BlobSupport
    implements Blob
{
  private final BlobId blobId;

  // Using ReentrantLock with fairness policy to ensure proper ordering when used with Virtual Threads
  private final Lock lock;

  private Map<String, String> headers;

  private BlobMetrics metrics;

  // Volatile ensures visibility across threads (including Virtual Threads)
  // in the Java Memory Model without additional synchronization
  private volatile boolean stale;

  public BlobSupport(final BlobId blobId) {
    this.blobId = checkNotNull(blobId);
    // Using fair lock to prevent starvation when multiple Virtual Threads contend for the lock
    // This helps ensure all Virtual Threads get a chance to acquire the lock in order of arrival
    lock = new ReentrantLock(true);
    stale = true;
  }

  /**
   * Refreshes the blob's metadata. This method updates the headers and metrics,
   * and marks the blob as not stale. The stale flag uses volatile semantics to ensure
   * visibility across threads, including Virtual Threads.
   *
   * @param headers The blob headers to set
   * @param metrics The blob metrics to set
   * @since 3.60 Updated for Virtual Thread compatibility
   */
  public void refresh(final Map<String, String> headers, final BlobMetrics metrics) {
    this.headers = checkNotNull(headers);
    this.metrics = checkNotNull(metrics);
    // Volatile write ensures visibility to all threads including Virtual Threads
    // without needing explicit synchronization
    stale = false;
  }

  /**
   * Marks this blob as stale, indicating its metadata needs to be refreshed.
   * Uses volatile semantics to ensure visibility across threads, including Virtual Threads.
   *
   * @since 3.60 Updated for Virtual Thread compatibility
   */
  public void markStale() {
    // Volatile write ensures visibility to all threads including Virtual Threads
    stale = true;
  }

  /**
   * Checks if this blob is stale and needs metadata refresh.
   * Uses volatile semantics to ensure visibility across threads, including Virtual Threads.
   *
   * @return true if the blob is stale, false otherwise
   * @since 3.60 Updated for Virtual Thread compatibility
   */
  public boolean isStale() {
    // Volatile read ensures we get the latest value across all threads including Virtual Threads
    return stale;
  }

  @Override
  public BlobId getId() {
    return blobId;
  }

  @Override
  public Map<String, String> getHeaders() {
    return headers;
  }

  @Override
  public BlobMetrics getMetrics() {
    return metrics;
  }

  /**
   * Acquires the lock for this blob. When used with Virtual Threads, this lock will allow
   * the Virtual Thread to be unmounted from its carrier thread while waiting to acquire the lock,
   * unlike synchronized blocks which would cause pinning in Java 21.
   *
   * @return The acquired lock which must be released in a finally block
   * @since 3.60
   */
  public Lock lock() {
    // Using ReentrantLock instead of synchronized to avoid Virtual Thread pinning in Java 21
    // The Locks utility ensures proper lock acquisition with exception handling
    return Locks.lock(lock);
  }

  /**
   * Gets an input stream for this blob's content. This method is optimized for use with Virtual Threads
   * to ensure high throughput when many concurrent blob reads are happening.
   *
   * @return An input stream for reading the blob's content
   * @since 3.60 Updated for Virtual Thread compatibility
   */
  @Override
  public InputStream getInputStream() {
    // This I/O operation is suitable for Virtual Threads as it doesn't use synchronized blocks
    // that would cause pinning. The actual I/O will happen in the implementation's doGetInputStream method.
    InputStream inputStream = doGetInputStream();
    if (!inputStream.markSupported()) {
      // BufferedInputStream improves performance by reducing the number of underlying I/O operations
      // and is compatible with Virtual Threads as it doesn't use synchronized for its core operations
      return new BufferedInputStream(inputStream);
    }
    return inputStream;
  }

  /**
   * Gets the natural input stream for the given blob. Implementations should ensure this method
   * is compatible with Virtual Threads by avoiding operations that would cause thread pinning,
   * such as synchronized blocks around I/O operations.
   *
   * @return An input stream for the blob's content
   * @since 3.19
   * @since 3.60 Updated documentation for Virtual Thread compatibility
   */
  protected abstract InputStream doGetInputStream();
}