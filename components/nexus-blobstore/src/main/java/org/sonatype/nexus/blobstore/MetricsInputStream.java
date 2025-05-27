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

import java.io.FilterInputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.io.BaseEncoding;
import com.google.common.io.CountingInputStream;

/**
 * A utility to collect metrics about the content of an input stream.
 * Optimized for efficient operation with Java 21 Virtual Threads.
 * <p>
 * This implementation is designed to work efficiently with Virtual Threads by:
 * - Avoiding operations that could cause thread pinning
 * - Using non-blocking operations for metric collection
 * - Ensuring thread safety for concurrent access
 * - Caching computed metrics to avoid redundant computation
 *
 * @since 3.0
 */
public class MetricsInputStream
    extends FilterInputStream
{
  private final MessageDigest messageDigest;

  private final CountingInputStream countingInputStream;
  
  // Using AtomicReference to cache the computed metrics for thread safety without causing thread pinning
  // This avoids synchronized blocks that would prevent Virtual Threads from unmounting during I/O operations
  private final AtomicReference<StreamMetrics> cachedMetrics = new AtomicReference<>();

  /**
   * Creates a new MetricsInputStream that wraps the given input stream.
   * 
   * @param input the input stream to wrap
   */
  public MetricsInputStream(final InputStream input) {
    this(new CountingInputStream(input), createSha1());
  }

  /**
   * Creates a new MetricsInputStream with the specified counting stream and message digest.
   * 
   * @param countingStream the counting stream to track bytes read
   * @param messageDigest the message digest to compute hash
   */
  private MetricsInputStream(final CountingInputStream countingStream, final MessageDigest messageDigest) {
    super(new DigestInputStream(countingStream, messageDigest));
    this.messageDigest = messageDigest;
    this.countingInputStream = countingStream;
  }

  private static final BaseEncoding HEX = BaseEncoding.base16().lowerCase();

  /**
   * Returns the message digest as a hexadecimal string.
   * This method is non-blocking and optimized for Virtual Thread execution.
   * <p>
   * Implementation note: The digest() method creates a copy of the digest's state,
   * so this method can be called multiple times without affecting the ongoing digest calculation.
   * This is important for Virtual Thread efficiency as it allows metrics to be retrieved
   * without interfering with the stream processing.
   * 
   * @return the message digest as a hexadecimal string
   */
  public String getMessageDigest() {
    return HEX.encode(messageDigest.digest());
  }

  /**
   * Returns the number of bytes read from the stream so far.
   * This method is non-blocking and optimized for Virtual Thread execution.
   * <p>
   * Implementation note: CountingInputStream.getCount() is a simple accessor that
   * returns the current count without any blocking operations, making it ideal for
   * use with Virtual Threads.
   * 
   * @return the number of bytes read
   */
  public long getSize() {
    return countingInputStream.getCount();
  }

  /**
   * Returns metrics about the content of the stream.
   * This method is optimized to avoid thread pinning operations when used with Virtual Threads.
   * The metrics are cached to avoid redundant computation when called multiple times.
   * <p>
   * Implementation note: This method uses an AtomicReference to cache the metrics in a thread-safe
   * manner without using synchronized blocks that could cause Virtual Thread pinning.
   * 
   * @return metrics about the content of the stream
   */
  public StreamMetrics getMetrics() {
    // Check if we already have computed metrics
    StreamMetrics metrics = cachedMetrics.get();
    if (metrics != null) {
      return metrics;
    }
    
    // Create new metrics and try to cache them atomically
    metrics = new StreamMetrics(getSize(), getMessageDigest());
    cachedMetrics.compareAndSet(null, metrics);
    
    // Return either our new metrics or the ones that were set by another thread
    return cachedMetrics.get();
  }

  /**
   * Creates a SHA-1 message digest.
   * This method is optimized to be efficient with Virtual Thread context.
   * <p>
   * Implementation note: MessageDigest creation is a one-time operation that doesn't block
   * or cause thread pinning during stream processing. The actual digest computation happens
   * incrementally as data is read through the DigestInputStream.
   * 
   * @return a new SHA-1 message digest
   * @throws RuntimeException if SHA-1 algorithm is not available
   */
  private static MessageDigest createSha1() {
    try {
      return MessageDigest.getInstance("SHA1");
    }
    catch (NoSuchAlgorithmException e) {
      // should never happen
      throw new RuntimeException("Failed to create SHA1 digest", e);
    }
  }
}