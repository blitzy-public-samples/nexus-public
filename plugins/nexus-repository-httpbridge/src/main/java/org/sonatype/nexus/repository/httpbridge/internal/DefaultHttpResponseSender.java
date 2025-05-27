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
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.httpbridge.HttpResponseSender;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;
import org.sonatype.nexus.thread.io.StreamCopier;

/**
 * Default {@link HttpResponseSender}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class DefaultHttpResponseSender
    extends ComponentSupport
    implements HttpResponseSender
{
  /**
   * Virtual Thread executor for I/O operations, leveraging Java 21's lightweight threads
   * for high-concurrency I/O without blocking platform threads.
   */
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  @Override
  public void send(@Nullable final Request request, final Response response, final HttpServletResponse httpResponse)
      throws ServletException, IOException
  {
    log.debug("Sending response: {}", response);

    // add response headers
    for (Map.Entry<String, String> header : response.getHeaders()) {
      httpResponse.addHeader(header.getKey(), header.getValue());
    }

    // add status followed by payload if we have one
    Status status = response.getStatus();
    String statusMessage = status.getMessage();
    try (Payload payload = response.getPayload()) {
      if (statusMessage == null) {
        httpResponse.setStatus(status.getCode());
      }
      else {
        httpResponse.setStatus(status.getCode(), statusMessage);
      }
      if (status.isSuccessful() || payload != null) {
        if (payload != null) {
          log.trace("Attaching payload: {}", payload);

          if (payload.getContentType() != null) {
            httpResponse.setContentType(payload.getContentType());
          }
          if (payload.getSize() != Payload.UNKNOWN_SIZE) {
            httpResponse.setContentLengthLong(payload.getSize());
          }

          if (request != null && !HttpMethods.HEAD.equals(request.getAction())) {
            copyPayloadWithVirtualThreads(payload, httpResponse);
          }
        }
      }
      else {
        httpResponse.sendError(status.getCode(), statusMessage);
      }
    }
  }

  /**
   * Copies payload content to the HTTP response using Virtual Threads for optimal I/O performance.
   * This approach leverages Java 21 Virtual Threads to handle I/O operations without blocking platform threads,
   * allowing for high concurrency even with many simultaneous connections.
   *
   * @param payload the payload to copy from
   * @param httpResponse the HTTP response to copy to
   * @throws IOException if an I/O error occurs during the copy operation
   */
  private void copyPayloadWithVirtualThreads(final Payload payload, final HttpServletResponse httpResponse) 
      throws IOException {
    try {
      // Use StreamCopier to handle the I/O operation with proper error handling
      // This leverages Virtual Threads for non-blocking I/O operations
      new StreamCopier<Void>(
          outputStream -> {
            try (InputStream input = payload.openInputStream()) {
              // Let the payload implementation handle the copy operation
              // which may have format-specific optimizations
              payload.copy(input, outputStream);
            } catch (IOException e) {
              log.warn("Error reading from payload input stream", e);
              throw new RuntimeException("Failed to read from payload", e);
            }
          },
          inputStream -> {
            try (OutputStream output = httpResponse.getOutputStream()) {
              // Use a buffer size that balances memory usage with performance
              byte[] buffer = new byte[8192];
              int bytesRead;
              while ((bytesRead = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
              }
              output.flush();
              return null;
            } catch (IOException e) {
              log.warn("Error writing to HTTP response output stream", e);
              throw new RuntimeException("Failed to write to HTTP response", e);
            }
          },
          VIRTUAL_THREAD_EXECUTOR
      ).read();
    } catch (RuntimeException e) {
      // Unwrap any IOExceptions that were wrapped in RuntimeException
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }
}