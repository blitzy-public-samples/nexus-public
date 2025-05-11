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
import java.util.concurrent.Executors;

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
 * Servlet multipart-payload adapter with Java 21 enhancements.
 * Uses Virtual Threads for I/O operations and Pattern Matching for type checking.
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

  @Override
  public Iterator<PartPayload> iterator() {
    try {
      // Create a ServletFileUpload instance
      ServletFileUpload upload = new ServletFileUpload();
      
      // Use a virtual thread to process the multipart data
      // This allows for efficient handling of I/O operations during file uploads
      return Thread.ofVirtual().name("multipart-parser").start(() -> {
        try {
          final FileItemIterator itemIterator = upload.getItemIterator(httpRequest);
          return new PayloadIterator(itemIterator);
        }
        catch (FileUploadException | IOException e) {
          throw new RuntimeException("Failed to process multipart request", e);
        }
      }).join();
    }
    catch (Exception e) {
      throw new RuntimeException("Error creating multipart iterator", e);
    }
  }

  /**
   * {@link FileItemStream} payload.
   */
  private static class FileItemStreamPayload
      implements PartPayload
  {
    private final FileItemStream fileItemStream;

    public FileItemStreamPayload(final FileItemStream fileItemStream) {
      this.fileItemStream = checkNotNull(fileItemStream);
    }

    @Override
    public InputStream openInputStream() throws IOException {
      // Using a virtual thread for I/O operations to improve scalability
      // This allows the system to handle many concurrent file uploads efficiently
      return Thread.ofVirtual().name("stream-reader").start(() -> {
        try {
          return fileItemStream.openStream();
        }
        catch (IOException e) {
          throw new RuntimeException("Failed to open input stream", e);
        }
      }).join();
    }

    @Override
    public long getSize() {
      return -1;
    }

    @Nullable
    @Override
    public String getContentType() {
      return fileItemStream.getContentType();
    }

    @Nullable
    @Override
    public String getName() {
      return fileItemStream.getName();
    }

    @Override
    public String getFieldName() {
      return fileItemStream.getFieldName();
    }

    @Override
    public boolean isFormField() {
      return fileItemStream.isFormField();
    }
  }

  /**
   * {@link Payload} iterator.
   */
  private static class PayloadIterator
      implements Iterator<PartPayload>
  {
    private final FileItemIterator itemIterator;

    public PayloadIterator(final FileItemIterator itemIterator) {
      this.itemIterator = checkNotNull(itemIterator);
    }

    @Override
    public boolean hasNext() {
      try {
        return itemIterator.hasNext();
      }
      catch (FileUploadException | IOException e) {
        throw new RuntimeException("Error checking for next item", e);
      }
    }

    @Override
    public PartPayload next() {
      try {
        // Using pattern matching for instanceof in Java 21
        // This simplifies type checking and casting in a single step
        var nextItem = itemIterator.next();
        if (nextItem instanceof FileItemStream fileItem) {
          // The pattern variable 'fileItem' is automatically cast and available for use
          return new FileItemStreamPayload(fileItem);
        }
        throw new IllegalStateException("Unexpected item type: " + nextItem.getClass().getName());
      }
      catch (FileUploadException | IOException e) {
        throw new RuntimeException("Error getting next item", e);
      }
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
   * Determine if given request is multipart.
   */
  public static boolean isMultipart(final HttpServletRequest httpRequest) {
    // We're circumventing ServletFileUpload.isMultipartContent as some clients (nuget) use PUT for multipart uploads
    return FileUploadBase.isMultipartContent(new ServletRequestContext(httpRequest));
  }
}