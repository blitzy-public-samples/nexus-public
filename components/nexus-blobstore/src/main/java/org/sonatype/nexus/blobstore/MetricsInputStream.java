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
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.io.BaseEncoding;

/**
 * A utility to collect metrics about the content of an input stream.
 * Optimized for Java 21 Virtual Threads with thread-safe counting and efficient
 * SHA-1 message digest computation.
 *
 * @since 3.0
 */
public class MetricsInputStream
    extends FilterInputStream
{
  private final MessageDigest messageDigest;

  private final AtomicLong count = new AtomicLong(0);

  /**
   * Creates a new MetricsInputStream that wraps the given input stream.
   * 
   * @param input the input stream to wrap
   */
  public MetricsInputStream(final InputStream input) {
    super(new DigestInputStream(input, createSha1()));
    this.messageDigest = ((DigestInputStream) in).getMessageDigest();
  }

  private static final BaseEncoding HEX = BaseEncoding.base16().lowerCase();

  /**
   * Returns the SHA-1 message digest of all bytes read from this stream so far.
   * 
   * @return the SHA-1 message digest as a hexadecimal string
   */
  public String getMessageDigest() {
    return HEX.encode(messageDigest.digest());
  }

  /**
   * Returns the number of bytes read from this stream so far.
   * Thread-safe for use with Virtual Threads.
   * 
   * @return the number of bytes read
   */
  public long getSize() {
    return count.get();
  }

  /**
   * Returns metrics about the content of this stream.
   * 
   * @return stream metrics containing size and SHA-1 digest
   */
  public StreamMetrics getMetrics() {
    return new StreamMetrics(getSize(), getMessageDigest());
  }

  /**
   * Creates a new SHA-1 message digest instance.
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
      throw new RuntimeException(e);
    }
  }

  @Override
  public int read() throws IOException {
    int b = super.read();
    if (b != -1) {
      count.incrementAndGet();
    }
    return b;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int bytesRead = super.read(b, off, len);
    if (bytesRead > 0) {
      count.addAndGet(bytesRead);
    }
    return bytesRead;
  }

  @Override
  public long skip(long n) throws IOException {
    long bytesSkipped = super.skip(n);
    if (bytesSkipped > 0) {
      count.addAndGet(bytesSkipped);
    }
    return bytesSkipped;
  }
}