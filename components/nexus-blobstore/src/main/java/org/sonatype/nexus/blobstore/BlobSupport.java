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

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Support for {@link Blob} implementations with support for locking.
 * Optimized for Java 21 Virtual Threads.
 *
 * @since 3.3
 */
public abstract class BlobSupport
    implements Blob
{
  private final BlobId blobId;

  private final Lock lock;

  private Map<String, String> headers;

  private BlobMetrics metrics;

  private volatile boolean stale;

  public BlobSupport(final BlobId blobId) {
    this.blobId = checkNotNull(blobId);
    // Using ReentrantLock which is optimized for Virtual Threads
    // and doesn't cause pinning like synchronized blocks would
    lock = new ReentrantLock();
    stale = true;
  }

  /**
   * Refreshes the blob's metadata.
   * Thread-safe and optimized for Virtual Threads.
   */
  public void refresh(final Map<String, String> headers, final BlobMetrics metrics) {
    checkNotNull(headers);
    checkNotNull(metrics);
    
    // Acquire lock to ensure thread safety when updating multiple fields
    lock.lock();
    try {
      this.headers = headers;
      this.metrics = metrics;
      stale = false;
    }
    finally {
      // Always release lock in finally block to ensure it's released even if an exception occurs
      lock.unlock();
    }
  }

  /**
   * Marks the blob as stale.
   * Uses volatile flag for thread safety with Virtual Threads.
   */
  public void markStale() {
    stale = true;
  }

  /**
   * Checks if the blob is stale.
   * Uses volatile flag for thread safety with Virtual Threads.
   */
  public boolean isStale() {
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
   * Acquires the lock for this blob.
   * Optimized for Virtual Threads - caller is responsible for releasing the lock.
   * 
   * @return The acquired lock
   */
  public Lock lock() {
    lock.lock();
    return lock;
  }

  /**
   * Tries to acquire the lock without blocking.
   * This is particularly useful in Virtual Thread contexts to avoid unnecessary blocking.
   * 
   * @return true if the lock was acquired, false otherwise
   */
  public boolean tryLock() {
    return lock.tryLock();
  }

  /**
   * Gets an input stream for the blob's content.
   * Optimized for Virtual Threads by ensuring non-blocking operations where possible.
   */
  @Override
  public InputStream getInputStream() {
    InputStream inputStream = doGetInputStream();
    if (!inputStream.markSupported()) {
      // Use BufferedInputStream to support mark/reset operations
      // This is efficient with Virtual Threads as it doesn't block during buffer operations
      return new BufferedInputStream(inputStream);
    }
    return inputStream;
  }

  /**
   * Gets the natural input stream for the given blob.
   * Implementation should be optimized for non-blocking I/O operations
   * to work efficiently with Virtual Threads.
   *
   * @since 3.19
   */
  protected abstract InputStream doGetInputStream();
}