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
package org.sonatype.nexus.repository.apt.internal.snapshot;

import java.io.FilterInputStream;
import java.io.IOException;

import org.bouncycastle.bcpg.ArmoredInputStream;

/**
 * A FilterInputStream implementation that reads clear text data from an ArmoredInputStream.
 * This class stops reading when the end of clear text is reached or when the underlying
 * stream is exhausted.
 *
 * @since 3.31
 * @see ArmoredInputStream
 */
public class AptFilterInputStream
    extends FilterInputStream
{
  private boolean done = false;

  private final ArmoredInputStream armoredIn;

  /**
   * Creates a new AptFilterInputStream that filters the specified ArmoredInputStream.
   *
   * @param armoredIn the ArmoredInputStream to filter
   */
  public AptFilterInputStream(final ArmoredInputStream armoredIn) {
    super(armoredIn);
    this.armoredIn = armoredIn;
  }

  /**
   * Reads the next byte of data from this input stream. The value byte is
   * returned as an {@code int} in the range {@code 0} to
   * {@code 255}. If no byte is available because the end of the stream
   * has been reached or the stream is not in clear text mode, the value
   * {@code -1} is returned.
   *
   * @return the next byte of data, or {@code -1} if the end of the
   *         stream is reached or the stream is not in clear text mode
   * @throws IOException if an I/O error occurs
   */
  @Override
  public int read() throws IOException {
    if (done) {
      return -1;
    }
    int c = armoredIn.read();
    if (c < 0 || !armoredIn.isClearText()) {
      done = true;
      return -1;
    }
    return c;
  }

  /**
   * Reads up to {@code len} bytes of data from this input stream
   * into an array of bytes. This method blocks until some input is
   * available.
   * <p>
   * This method simply performs {@code read()} repeatedly until either
   * {@code len} bytes have been read, the end of the stream has been reached,
   * or an exception is thrown.
   *
   * @param b   the buffer into which the data is read
   * @param off the start offset in the destination array {@code b}
   * @param len the maximum number of bytes read
   * @return the total number of bytes read into the buffer, or
   *         {@code -1} if there is no more data because the end of
   *         the stream has been reached
   * @throws IOException if an I/O error occurs
   */
  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    for (int i = 0; i < len; i++) {
      int c = read();
      if (c == -1) {
        return i == 0 ? -1 : i;
      }
      b[off + i] = (byte) c;
    }
    return len;
  }
}