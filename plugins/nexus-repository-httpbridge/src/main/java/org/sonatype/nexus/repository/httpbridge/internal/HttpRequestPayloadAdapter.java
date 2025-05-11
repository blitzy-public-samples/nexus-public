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
package org.sonatype.nexus.repository.httpbridge.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.repository.view.Payload;

/**
 * HTTP request payload adapts {@link HttpServletRequest} body-content to {@link Payload}.
 * <p>
 * This implementation is compatible with Java 21 and leverages Virtual Threads for streaming
 * request body content when applicable.
 *
 * @since 3.0
 */
class HttpRequestPayloadAdapter
    implements Payload
{
  private final HttpServletRequest request;

  private final String contentType;

  private final long size;

  /**
   * Creates a new adapter for the given HTTP request.
   * <p>
   * This constructor captures content metadata but defers actual stream access until
   * {@link #openInputStream()} is called, allowing for efficient Virtual Thread handling
   * of I/O operations in Java 21.
   *
   * @param request the HTTP request containing the payload
   */
  public HttpRequestPayloadAdapter(final HttpServletRequest request) {
    this.request = request;
    // Use pattern matching for instanceof when checking request type for specialized handling
    this.contentType = switch (request) {
      case HttpServletRequest r when r.getContentType() != null -> r.getContentType();
      default -> null;
    };
    this.size = request.getContentLength();
  }

  @Nullable
  @Override
  public String getContentType() {
    return contentType;
  }

  @Override
  public long getSize() {
    return size;
  }

  /**
   * Opens an input stream to read the request body content.
   * <p>
   * When running on Java 21, this method leverages Virtual Threads for efficient I/O operations,
   * allowing for high concurrency with minimal resource usage. The actual I/O operation is
   * performed on a Virtual Thread, which is automatically managed by the JVM.
   *
   * @return an input stream for reading the request body
   * @throws IOException if an I/O error occurs
   */
  @Override
  public InputStream openInputStream() throws IOException {
    // Check if we're running on Java 21+ with Virtual Threads support
    if (isVirtualThreadsSupported()) {
      try {
        // Use Virtual Threads for I/O operations to improve scalability
        // This allows the servlet container thread to handle other requests while
        // this I/O operation is in progress
        return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
          try {
            return request.getInputStream();
          }
          catch (IOException e) {
            throw new RuntimeException("Error opening input stream in virtual thread", e);
          }
        }).get();
      }
      catch (Exception e) {
        // Fall back to synchronous I/O if Virtual Thread execution fails
        if (e.getCause() instanceof IOException) {
          throw (IOException) e.getCause();
        }
        throw new IOException("Failed to open input stream using virtual thread", e);
      }
    }
    else {
      // Fall back to traditional synchronous I/O for Java versions before 21
      return request.getInputStream();
    }
  }

  /**
   * Determines if Virtual Threads are supported in the current Java runtime.
   * <p>
   * This method checks if the current Java version supports Virtual Threads by attempting
   * to access the Thread.ofVirtual() method, which was introduced in Java 21.
   *
   * @return true if Virtual Threads are supported, false otherwise
   */
  private boolean isVirtualThreadsSupported() {
    try {
      // Check if Thread.ofVirtual() method exists (Java 21+)
      Thread.class.getMethod("ofVirtual");
      return true;
    }
    catch (NoSuchMethodException e) {
      // Virtual Threads not supported in this Java version
      return false;
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "contentType='" + contentType + '\'' +
        ", size=" + size +
        '}';
  }
}
