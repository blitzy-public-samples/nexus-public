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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

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
 * Leverages Java 21 Virtual Threads for improved concurrency and performance.
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
   * Executor service using Virtual Threads for handling proxy requests.
   * Virtual Threads are lightweight and efficient for I/O-bound operations like proxy requests.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception { // NOSONAR
    final Response methodNotAllowedResponse = buildMethodNotAllowedResponse(context);
    if (methodNotAllowedResponse != null) {
      return methodNotAllowedResponse;
    }

    try {
      // Use Virtual Thread to handle the proxy request
      // This allows for better concurrency without blocking platform threads during I/O operations
      return virtualThreadExecutor.submit(() -> {
        try {
          Payload payload = proxyFacet(context).get(context);
          if (payload != null) {
            return buildPayloadResponse(context, payload);
          }
          if (context.getAttributes() != null && context.getAttributes().contains(PROXY_REMOTE_FETCH_SKIP_MARKER) &&
              context.getAttributes().get(PROXY_REMOTE_FETCH_SKIP_MARKER).equals(TRUE)) {
            return buildPaymentRequiredResponse(context);
          }
          return buildNotFoundResponse(context);
        }
        catch (BypassHttpErrorException e) {
          return buildHttpErrorResponse(e);
        }
        catch (ProxyServiceException e) {
          log.debug("Service unavailable due to proxy service exception", e);
          return HttpResponses.serviceUnavailable();
        }
        catch (CooperationException e) { // NOSONAR
          log.debug("Service unavailable due to cooperation exception: {}", e.getMessage());
          return HttpResponses.serviceUnavailable(e.getMessage());
        }
        catch (RemoteBlockedIOException e) {
          log.debug("Remote blocked: {}", e.getMessage());
          return HttpResponses.notFound(e.getMessage());
        }
        catch (IOException | UncheckedIOException e) {
          log.debug("Bad gateway due to I/O exception", e);
          return HttpResponses.badGateway();
        }
        catch (Exception e) {
          log.error("Unexpected error handling proxy request", e);
          return HttpResponses.internalServerError(e.getMessage());
        }
      }).get();
    }
    catch (Exception e) {
      log.error("Error executing proxy request in virtual thread", e);
      throw e;
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
   * Optimized for efficient handling of payload data.
   */
  protected Response buildPayloadResponse(final Context context, final Payload payload) {
    return HttpResponses.ok(payload);
  }

  /**
   * Builds a not found response for the given context.
   */
  protected Response buildNotFoundResponse(final Context context) {
    return HttpResponses.notFound();
  }

  /**
   * Builds a payment required response, using either conflict (for NuGet) or forbidden (for other formats).
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
   * Builds an HTTP error response from a BypassHttpErrorException.
   * Preserves the original status code, reason, and headers.
   */
  protected Response buildHttpErrorResponse(final BypassHttpErrorException proxyErrorsException) {
    return new Response.Builder()
        .status(new Status(false, proxyErrorsException.getStatusCode(), proxyErrorsException.getReason()))
        .headers(new Headers(proxyErrorsException.getHeaders()))
        .build();
  }

  /**
   * Retrieves the ProxyFacet from the repository in the given context.
   */
  private ProxyFacet proxyFacet(final Context context) {
    return context.getRepository().facet(ProxyFacet.class);
  }
}