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
import java.util.concurrent.ExecutorService;

import jakarta.annotation.Nullable;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.httpbridge.HttpResponseSender;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;
import org.sonatype.nexus.thread.NexusExecutorService;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;

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
   * Virtual thread executor for I/O-bound operations.
   * Using virtual threads improves throughput for I/O operations like streaming response payloads.
   */
  private final ExecutorService virtualThreadExecutor = NexusExecutorService.forVirtualThreads(FakeAlmightySubject.TASK_SUBJECT);

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
      // Set status code using pattern matching for cleaner code
      switch (statusMessage) {
        case null -> httpResponse.setStatus(status.getCode());
        default -> httpResponse.setStatus(status.getCode(), statusMessage);
      }
      
      // Handle payload using pattern matching
      if (status.isSuccessful() || payload != null) {
        if (payload != null) {
          log.trace("Attaching payload: {}", payload);

          // Set content type if available
          if (payload.getContentType() != null) {
            httpResponse.setContentType(payload.getContentType());
          }
          
          // Set content length if known
          if (payload.getSize() != Payload.UNKNOWN_SIZE) {
            httpResponse.setContentLengthLong(payload.getSize());
          }

          // Stream content for non-HEAD requests
          if (request != null && !HttpMethods.HEAD.equals(request.getAction())) {
            try (InputStream input = payload.openInputStream(); OutputStream output = httpResponse.getOutputStream()) {
              // Use virtual threads for I/O-bound streaming operations to improve throughput
              try {
                virtualThreadExecutor.submit(() -> {
                  try {
                    payload.copy(input, output);
                    return null;
                  } catch (IOException e) {
                    log.error("Error copying payload", e);
                    throw new RuntimeException(e);
                  }
                }).get(); // Wait for completion
              } catch (Exception e) {
                if (e.getCause() instanceof IOException) {
                  throw (IOException) e.getCause();
                }
                throw new IOException("Error streaming payload", e);
              }
            }
          }
        }
      } else {
        httpResponse.sendError(status.getCode(), statusMessage);
      }
    }
  }
}