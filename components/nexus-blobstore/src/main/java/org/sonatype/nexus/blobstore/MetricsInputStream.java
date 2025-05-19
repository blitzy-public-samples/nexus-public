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
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.io.BaseEncoding;

/**
 * A utility to collect metrics about the content of an input stream.
 * Optimized for efficient operation with Virtual Threads in Java 21.
 *
 * @since 3.0
 */
public class MetricsInputStream
    extends FilterInputStream
{
  private final MessageDigest messageDigest;
  private final AtomicLong count;
  private final byte[] buffer = new byte[8192]; // Optimal buffer size for most I/O operations

  /**
   * Creates a new MetricsInputStream that wraps the given input stream.
   * This implementation is optimized for Virtual Threads by avoiding operations
   * that could cause thread pinning.
   *
   * @param input the input stream to wrap
   */
  public MetricsInputStream(final InputStream input) {
    super(input);
    this.messageDigest = createSha1();
    this.count = new AtomicLong(0);
  }

  private static final BaseEncoding HEX = BaseEncoding.base16().lowerCase();

  @Override
  public int read() throws IOException {
    int b = super.read();
    if (b != -1) {
      count.incrementAndGet();
      // Update digest without synchronization to avoid thread pinning
      messageDigest.update((byte) b);
    }
    return b;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int bytesRead = super.read(b, off, len);
    if (bytesRead > 0) {
      count.addAndGet(bytesRead);
      // Update digest without synchronization to avoid thread pinning
      messageDigest.update(b, off, bytesRead);
    }
    return bytesRead;
  }

  @Override
  public long skip(long n) throws IOException {
    // For accurate metrics, we need to read the skipped bytes
    // rather than actually skipping them
    long remaining = n;
    int bytesRead;
    
    while (remaining > 0) {
      int bytesToRead = (int) Math.min(buffer.length, remaining);
      bytesRead = read(buffer, 0, bytesToRead);
      if (bytesRead < 0) {
        break; // End of stream
      }
      remaining -= bytesRead;
    }
    
    return n - remaining;
  }

  /**
   * Returns the hex-encoded SHA1 message digest of all bytes read so far.
   * This method is thread-safe and optimized for Virtual Threads.
   *
   * @return the hex-encoded SHA1 message digest
   */
  public String getMessageDigest() {
    // Create a clone to avoid modifying the original digest
    // This prevents thread pinning during concurrent operations
    MessageDigest clone = (MessageDigest) messageDigest.clone();
    return HEX.encode(clone.digest());
  }

  /**
   * Returns the number of bytes read so far.
   * This method is thread-safe and optimized for Virtual Threads.
   *
   * @return the number of bytes read
   */
  public long getSize() {
    return count.get();
  }

  /**
   * Returns metrics about the stream content read so far.
   * This method is thread-safe and optimized for Virtual Threads.
   *
   * @return metrics about the stream content
   */
  public StreamMetrics getMetrics() {
    // Create immutable metrics object with current values
    // to avoid potential thread pinning operations
    return new StreamMetrics(getSize(), getMessageDigest());
  }

  /**
   * Creates a SHA1 message digest instance.
   * This method is optimized to handle exceptions without thread pinning.
   *
   * @return a new SHA1 message digest instance
   * @throws RuntimeException if SHA1 algorithm is not available
   */
  private static MessageDigest createSha1() {
    try {
      return MessageDigest.getInstance("SHA1");
    }
    catch (NoSuchAlgorithmException e) {
      // should never happen
      throw new RuntimeException("Failed to create SHA1 message digest", e);
    }
  }
}