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
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.Payload;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.servlet.ServletRequestContext;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Servlet multipart-payload adapter with Virtual Thread support for Java 21.
 *
 * @since 3.0
 */
class HttpPartIteratorAdapter
    implements Iterable<PartPayload>
{
  private final HttpServletRequest httpRequest;

  public HttpPartIteratorAdapter(final HttpServletRequest httpRequest) {
    this.httpRequest = checkNotNull(httpRequest);
  }

  /**
   * Creates an iterator for multipart processing using Java 21 Virtual Threads.
   * This implementation leverages Virtual Threads to efficiently handle I/O operations
   * during multipart processing, improving scalability for large file uploads.
   */
  @Override
  public Iterator<PartPayload> iterator() {
    try {
      final FileItemIterator itemIterator = new ServletFileUpload().getItemIterator(httpRequest);
      return new VirtualThreadPayloadIterator(itemIterator);
    }
    catch (FileUploadException | IOException e) {
      throw new MultipartProcessingException("Failed to initialize multipart processing", e);
    }
  }

  /**
   * {@link FileItemStream} payload.
   */
  private static class FileItemStreamPayload
      implements PartPayload
  {
    private final FileItemStream next;

    public FileItemStreamPayload(final FileItemStream next) {
      this.next = next;
    }

    @Override
    public InputStream openInputStream() throws IOException {
      return next.openStream();
    }

    @Override
    public long getSize() {
      return -1;
    }

    @Nullable
    @Override
    public String getContentType() {
      return next.getContentType();
    }

    @Nullable
    @Override
    public String getName() {
      return next.getName();
    }

    @Override
    public String getFieldName() {
      return next.getFieldName();
    }

    @Override
    public boolean isFormField() {
      return next.isFormField();
    }
  }

  /**
   * {@link Payload} iterator optimized for Virtual Threads.
   * This implementation uses Virtual Threads to process multipart data
   * asynchronously, improving performance for I/O-bound operations.
   */
  private static class VirtualThreadPayloadIterator
      implements Iterator<PartPayload>
  {
    private final FileItemIterator itemIterator;
    private final ConcurrentLinkedQueue<PartPayload> prefetchedItems;
    private final AtomicBoolean prefetchInProgress;
    private final AtomicBoolean endOfIterator;
    private final ExecutorService virtualThreadExecutor;
    private CompletableFuture<Void> prefetchFuture;

    public VirtualThreadPayloadIterator(final FileItemIterator itemIterator) {
      this.itemIterator = itemIterator;
      this.prefetchedItems = new ConcurrentLinkedQueue<>();
      this.prefetchInProgress = new AtomicBoolean(false);
      this.endOfIterator = new AtomicBoolean(false);
      this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
      
      // Start prefetching the first item
      prefetchNextItem();
    }

    /**
     * Prefetches the next item using a Virtual Thread to avoid blocking the caller thread.
     * This method is non-blocking and improves performance for I/O-bound operations.
     */
    private void prefetchNextItem() {
      if (prefetchInProgress.compareAndSet(false, true) && !endOfIterator.get()) {
        prefetchFuture = CompletableFuture.runAsync(() -> {
          try {
            if (itemIterator.hasNext()) {
              FileItemStream nextItem = itemIterator.next();
              prefetchedItems.add(new FileItemStreamPayload(nextItem));
            } else {
              endOfIterator.set(true);
            }
          } catch (FileUploadException | IOException e) {
            throw new MultipartProcessingException("Error processing multipart data", e);
          } finally {
            prefetchInProgress.set(false);
          }
        }, virtualThreadExecutor);
      }
    }

    @Override
    public boolean hasNext() {
      // Wait for any ongoing prefetch to complete
      if (prefetchFuture != null && prefetchInProgress.get()) {
        try {
          prefetchFuture.join();
        } catch (Exception e) {
          throw new MultipartProcessingException("Error while prefetching multipart data", e);
        }
      }
      
      // Check if we have prefetched items or if we've reached the end
      return !prefetchedItems.isEmpty() || !endOfIterator.get();
    }

    @Override
    public PartPayload next() {
      if (!hasNext()) {
        throw new NoSuchElementException("No more multipart items available");
      }
      
      // Get the next prefetched item
      PartPayload nextItem = prefetchedItems.poll();
      
      // Start prefetching the next item if we're not at the end
      if (!endOfIterator.get()) {
        prefetchNextItem();
      }
      
      return nextItem;
    }

    /**
     * @throws UnsupportedOperationException
     */
    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Exception thrown when multipart processing encounters an error.
   * Provides more context about the failure for better diagnostics.
   */
  public static class MultipartProcessingException extends RuntimeException {
    public MultipartProcessingException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Determine if given request is multipart.
   */
  public static boolean isMultipart(final HttpServletRequest httpRequest) {
    // We're circumventing ServletFileUpload.isMultipartContent as some clients (nuget) use PUT for multipart uploads
    return FileUploadBase.isMultipartContent(new ServletRequestContext(httpRequest));
  }
}