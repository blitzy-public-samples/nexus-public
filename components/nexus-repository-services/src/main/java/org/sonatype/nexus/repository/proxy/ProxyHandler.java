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
package org.sonatype.nexus.repository.proxy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.io.CooperationException;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Headers;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;

import static java.lang.Boolean.TRUE;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;
import static org.sonatype.nexus.repository.proxy.ProxyFacetSupport.PROXY_REMOTE_FETCH_SKIP_MARKER;
import static org.sonatype.nexus.repository.proxy.ThrottlerInterceptor.PAYMENT_REQUIRED_MESSAGE;

/**
 * A format-neutral proxy handler which delegates to an instance of {@link ProxyFacet} for content.
 * 
 * This implementation leverages Java 21 Virtual Threads for improved concurrency and performance
 * when handling proxy requests, particularly for I/O-bound operations.
 *
 * @since 3.0
 */
public class ProxyHandler
    extends ComponentSupport
    implements Handler
{
  @Inject
  private NodeAccess nodeAccess;

  /**
   * Handles the request by delegating to a {@link ProxyFacet} instance.
   * 
   * This implementation leverages Virtual Threads to improve concurrency for I/O-bound operations,
   * allowing the system to handle more concurrent requests efficiently.
   *
   * @param context The context of the request
   * @return The response to the request
   */
  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    // First check if the method is allowed before proceeding with any I/O operations
    final Response methodNotAllowedResponse = buildMethodNotAllowedResponse(context);
    if (methodNotAllowedResponse != null) {
      return methodNotAllowedResponse;
    }

    // Create a virtual thread executor for handling the proxy request
    // This allows the request to be processed with minimal resource usage
    // and optimal concurrency when performing I/O operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the proxy request to be executed in a virtual thread
      // This allows the carrier thread to be released during I/O operations
      return executor.submit(() -> {
        try {
          // Get the content from the proxy facet, which may involve remote I/O operations
          // ProxyFacetSupport has been optimized to use Virtual Threads internally
          Payload payload = proxyFacet(context).get(context);
          
          if (payload != null) {
            // Successfully retrieved content, build the response
            return buildPayloadResponse(context, payload);
          }
          
          // Check if remote fetch was skipped due to throttling
          if (context.getAttributes() != null && 
              context.getAttributes().contains(PROXY_REMOTE_FETCH_SKIP_MARKER) &&
              context.getAttributes().get(PROXY_REMOTE_FETCH_SKIP_MARKER).equals(TRUE)) {
            return buildPaymentRequiredResponse(context);
          }
          
          // Content not found
          return buildNotFoundResponse(context);
        }
        catch (Exception e) {
          // Re-throw the exception to be caught by the outer try-catch
          if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
          }
          throw new RuntimeException(e);
        }
      }).get(); // Wait for the virtual thread to complete
    }
    catch (Exception e) {
      // Unwrap the cause if it's wrapped in an ExecutionException
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      
      if (cause instanceof BypassHttpErrorException) {
        // Special case for bypassing HTTP errors
        return buildHttpErrorResponse((BypassHttpErrorException) cause);
      }
      else if (cause instanceof ProxyServiceException) {
        // Service unavailable from the remote proxy
        return HttpResponses.serviceUnavailable();
      }
      else if (cause instanceof CooperationException) { // NOSONAR
        // Service unavailable due to cooperation issues
        return HttpResponses.serviceUnavailable(cause.getMessage());
      }
      else if (cause instanceof RemoteBlockedIOException) {
        // Remote is blocked
        return HttpResponses.notFound(cause.getMessage());
      }
      else if (cause instanceof IOException || cause instanceof UncheckedIOException) {
        // General I/O errors result in bad gateway
        return HttpResponses.badGateway();
      }
      
      // Re-throw any other exceptions
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new Exception(cause);
    }

  }

  /**
   * Builds a not-allowed response if the specified method is unsupported under the specified context, null otherwise.
   */
  @Nullable
  protected Response buildMethodNotAllowedResponse(final Context context) {
    final String action = context.getRequest().getAction();
    if (!GET.equals(action) && !HEAD.equals(action)) {
      return HttpResponses.methodNotAllowed(action, GET, HEAD);
    }
    return null;
  }

  /**
   * Builds a response with the provided payload.
   * 
   * @param context The request context
   * @param payload The payload to include in the response
   * @return The built response
   */
  protected Response buildPayloadResponse(final Context context, final Payload payload) {
    return HttpResponses.ok(payload);
  }

  /**
   * Builds a not found response.
   * 
   * @param context The request context
   * @return The built response
   */
  protected Response buildNotFoundResponse(final Context context) {
    return HttpResponses.notFound();
  }

  /**
   * Builds a payment required response when throttling is active.
   * 
   * @param context The request context
   * @return The built response
   */
  protected Response buildPaymentRequiredResponse(Context context) {
    if (context.getRepository().getFormat().getValue().equals("nuget")) {
      return HttpResponses.conflict(PAYMENT_REQUIRED_MESSAGE.concat(nodeAccess.getId()));
    }
    else {
      return HttpResponses.forbidden(PAYMENT_REQUIRED_MESSAGE.concat(nodeAccess.getId()));
    }
  }

  /**
   * Builds an HTTP error response based on the provided exception.
   * This method efficiently creates a response with the appropriate status and headers
   * from the exception, optimized for Virtual Thread execution.
   * 
   * @param proxyErrorsException The exception containing HTTP error details
   * @return The built response
   */
  protected Response buildHttpErrorResponse(final BypassHttpErrorException proxyErrorsException) {
    return new Response.Builder()
        .status(new Status(false, proxyErrorsException.getStatusCode(), proxyErrorsException.getReason()))
        .headers(new Headers(proxyErrorsException.getHeaders()))
        .build();
  }

  /**
   * Gets the ProxyFacet from the context's repository.
   * 
   * @param context The request context
   * @return The ProxyFacet instance
   */
  private ProxyFacet proxyFacet(final Context context) {
    return context.getRepository().facet(ProxyFacet.class);
  }
}