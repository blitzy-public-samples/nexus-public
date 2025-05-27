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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.repository.view.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP request payload adapts {@link HttpServletRequest} body-content to {@link Payload}.
 *
 * @since 3.0
 */
class HttpRequestPayloadAdapter
    implements Payload
{
  private static final Logger log = LoggerFactory.getLogger(HttpRequestPayloadAdapter.class);
  private final HttpServletRequest request;

  private final String contentType;

  private final long size;

  public HttpRequestPayloadAdapter(final HttpServletRequest request) {
    this.request = request;
    this.contentType = request.getContentType();
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
   * Opens an input stream from the HTTP request, optimized for Virtual Thread execution.
   * This implementation ensures proper resource handling when running in a Virtual Thread context.
   * 
   * When executed in a Virtual Thread, this method returns a wrapper around the original input stream
   * that will automatically clean up resources when the Virtual Thread completes, even if the caller
   * forgets to close the stream explicitly.
   * 
   * @return An input stream for reading the request body
   * @throws IOException if an I/O error occurs
   * @since 3.0 (Virtual Thread optimization added in Java 21 upgrade)
   */
  @Override
  public InputStream openInputStream() throws IOException {
    // Get the raw input stream from the request
    InputStream inputStream = request.getInputStream();
    
    // Create a wrapper that ensures proper cleanup in Virtual Thread context
    return new VirtualThreadAwareInputStream(inputStream);
  }
  
  /**
   * An InputStream wrapper that is optimized for Virtual Thread execution.
   * It ensures proper resource cleanup when the Virtual Thread completes or is interrupted.
   */
  private static class VirtualThreadAwareInputStream extends InputStream {
    private final InputStream delegate;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    VirtualThreadAwareInputStream(InputStream delegate) {
      this.delegate = delegate;
      
      // Register a cleanup hook for when the current thread (potentially a Virtual Thread) completes
      if (Thread.currentThread().isVirtual()) {
        // Use a cleanup action that will be executed when the Virtual Thread completes
        Thread currentThread = Thread.currentThread();
        ThreadFactory daemonThreadFactory = r -> {
          Thread t = new Thread(r, "virtual-thread-cleanup-monitor");
          t.setDaemon(true);
          return t;
        };
        // We need to shut down the executor after use to prevent resource leaks
        var executor = Executors.newSingleThreadExecutor(daemonThreadFactory);
        executor.submit(() -> {
          try {
            // Wait for the virtual thread to complete
            currentThread.join();
          } catch (InterruptedException e) {
            // Ignore interruption
          } finally {
            // Ensure stream is closed when the Virtual Thread completes
            closeQuietly();
            // Shut down the executor to prevent resource leaks
            executor.shutdown();
          }
        });
      }
    }
    
    @Override
    public int read() throws IOException {
      return delegate.read();
    }
    
    @Override
    public int read(byte[] b) throws IOException {
      return delegate.read(b);
    }
    
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return delegate.read(b, off, len);
    }
    
    @Override
    public long skip(long n) throws IOException {
      return delegate.skip(n);
    }
    
    @Override
    public int available() throws IOException {
      return delegate.available();
    }
    
    @Override
    public void close() throws IOException {
      if (closed.compareAndSet(false, true)) {
        delegate.close();
      }
    }
    
    @Override
    public synchronized void mark(int readlimit) {
      delegate.mark(readlimit);
    }
    
    @Override
    public synchronized void reset() throws IOException {
      delegate.reset();
    }
    
    @Override
    public boolean markSupported() {
      return delegate.markSupported();
    }
    
    /**
     * Closes the stream quietly without throwing exceptions.
     */
    private void closeQuietly() {
      try {
        close();
      } catch (IOException e) {
        log.debug("Error closing input stream in Virtual Thread context", e);
      }
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