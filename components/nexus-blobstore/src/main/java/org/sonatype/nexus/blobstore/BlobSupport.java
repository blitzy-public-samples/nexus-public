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

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Support for {@link Blob} implementations with support for locking.
 *
 * @since 3.3
 */
public abstract class BlobSupport
    implements Blob
{
  private final BlobId blobId;

  /**
   * Lock for thread-safe operations on this blob.
   * Using a fair lock policy to prevent starvation in high-concurrency scenarios with Virtual Threads.
   */
  private final Lock lock;

  private Map<String, String> headers;

  private BlobMetrics metrics;

  /**
   * Flag indicating if this blob's metadata is stale and needs refreshing.
   * Volatile ensures visibility across threads, including Virtual Threads.
   */
  private volatile boolean stale;

  public BlobSupport(final BlobId blobId) {
    this.blobId = checkNotNull(blobId);
    // Using fair lock policy to prevent starvation with Virtual Threads
    lock = new ReentrantLock(true);
    stale = true;
  }

  /**
   * Refreshes this blob's metadata.
   */
  public void refresh(final Map<String, String> headers, final BlobMetrics metrics) {
    // Acquire lock to ensure thread-safety when updating metadata
    try (Lock l = lock()) {
      this.headers = checkNotNull(headers);
      this.metrics = checkNotNull(metrics);
      stale = false;
    }
  }

  /**
   * Marks this blob's metadata as stale, requiring a refresh.
   */
  public void markStale() {
    // Volatile write ensures visibility across all threads
    stale = true;
  }

  /**
   * Returns whether this blob's metadata is stale.
   */
  public boolean isStale() {
    // Volatile read ensures visibility of the most recent write
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
   * Acquires the lock for this blob and returns it.
   * The returned lock should be used in a try-with-resources block to ensure proper release.
   * 
   * @return the acquired lock, which will auto-close when used with try-with-resources
   */
  public Lock lock() {
    return Locks.lock(lock);
  }

  @Override
  public InputStream getInputStream() {
    // Thread-safe access to the input stream
    InputStream inputStream = doGetInputStream();
    if (!inputStream.markSupported()) {
      return new BufferedInputStream(inputStream);
    }
    return inputStream;
  }

  /**
   * Gets the natural input stream for the given blob.
   * Implementations must ensure this method is thread-safe and compatible with Virtual Threads.
   *
   * @return the input stream for this blob
   * @since 3.19
   */
  protected abstract InputStream doGetInputStream();
}