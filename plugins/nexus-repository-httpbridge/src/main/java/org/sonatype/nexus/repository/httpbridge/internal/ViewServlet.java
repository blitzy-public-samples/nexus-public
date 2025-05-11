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
import java.util.Enumeration;
import java.util.concurrent.Callable;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.repository.BadRequestException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.httpbridge.HttpResponseSender;
import org.sonatype.nexus.repository.httpbridge.internal.describe.DescribeType;
import org.sonatype.nexus.repository.httpbridge.internal.describe.Description;
import org.sonatype.nexus.repository.httpbridge.internal.describe.DescriptionHelper;
import org.sonatype.nexus.repository.httpbridge.internal.describe.DescriptionRenderer;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.view.ContentTypes;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.shiro.authz.AuthorizationException;
import org.jboss.logging.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Repository view servlet.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ViewServlet
    extends HttpServlet
{
  private static final Logger log = LoggerFactory.getLogger(ViewServlet.class);

  private static final String SANDBOX = "sandbox allow-forms allow-modals allow-popups allow-presentation allow-scripts allow-top-navigation";

  @VisibleForTesting
  static final String P_DESCRIBE = "describe";

  protected static final String REPOSITORY_NOT_FOUND_MESSAGE = "Repository not found";

  private final RepositoryManager repositoryManager;

  private final HttpResponseSenderSelector httpResponseSenderSelector;

  private final DescriptionHelper descriptionHelper;

  private final DescriptionRenderer descriptionRenderer;

  private final boolean sandboxEnabled;

  /**
   * Record for capturing request context information
   */
  private record RequestContext(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String uri) {}

  @Inject
  public ViewServlet(final RepositoryManager repositoryManager,
                     final HttpResponseSenderSelector httpResponseSenderSelector,
                     final DescriptionHelper descriptionHelper,
                     final DescriptionRenderer descriptionRenderer,
                     @Named("${nexus.repository.sandbox.enable:-true}") final boolean sandboxEnabled)
  {
    this.repositoryManager = checkNotNull(repositoryManager);
    this.httpResponseSenderSelector = checkNotNull(httpResponseSenderSelector);
    this.descriptionHelper = checkNotNull(descriptionHelper);
    this.descriptionRenderer = checkNotNull(descriptionRenderer);
    this.sandboxEnabled = sandboxEnabled;
  }

  @Override
  public void init(final ServletConfig config) throws ServletException {
    super.init(config);
    log.info("Initialized");
  }

  @Override
  public void destroy() {
    super.destroy();
    log.info("Destroyed");
  }

  @Override
  protected void service(final HttpServletRequest httpRequest, final HttpServletResponse httpResponse)
      throws ServletException, IOException
  {
    String uri = httpRequest.getRequestURI();
    if (httpRequest.getQueryString() != null) {
      uri = STR."{uri}?{httpRequest.getQueryString()}";
    }

    if (log.isDebugEnabled()) {
      log.debug(STR."Servicing: {httpRequest.getMethod()} {uri} ({httpRequest.getRequestURL()})");
    }

    // Create a RequestContext record to hold the request information
    RequestContext context = new RequestContext(httpRequest, httpResponse, uri);

    // Use a virtual thread to handle the request
    try {
      // Create a callable that will process the request
      Callable<Void> task = () -> {
        MDC.put(getClass().getName(), uri);
        try {
          doService(context.httpRequest(), context.httpResponse());
          log.debug("Service completed");
          return null;
        }
        catch (BadRequestException e) { // NOSONAR
          log.warn(STR."Bad request. Reason: {e.getMessage()}");
          send(null, HttpResponses.badRequest(e.getMessage()), context.httpResponse());
          return null;
        }
        catch (Exception e) {
          if (!(e instanceof AuthorizationException)) {
            log.warn(STR."Failure servicing: {context.httpRequest().getMethod()} {context.uri()}", e);
          }
          if (e instanceof ServletException || e instanceof IOException) {
            throw e;
          }
          throw new ServletException(e);
        }
        finally {
          MDC.remove(getClass().getName());
        }
      };

      // Execute the task in a virtual thread
      Thread.startVirtualThread(task).join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServletException("Request processing interrupted", e);
    }
    catch (Exception e) {
      if (e instanceof ServletException servletException) {
        throw servletException;
      }
      if (e instanceof IOException ioException) {
        throw ioException;
      }
      throw new ServletException("Error processing request", e);
    }
  }

  protected void doService(final HttpServletRequest httpRequest, final HttpServletResponse httpResponse)
      throws Exception
  {
    // Set security headers
    if (sandboxEnabled) {
      httpResponse.setHeader(HttpHeaders.CONTENT_SECURITY_POLICY, SANDBOX);
    }
    httpResponse.setHeader(HttpHeaders.X_XSS_PROTECTION, "1; mode=block");
    // Add recommended security headers for Java 21
    httpResponse.setHeader(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff");
    httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

    // resolve repository for request
    RepositoryPath path = RepositoryPath.parse(httpRequest.getPathInfo());
    log.debug(STR."Parsed path: {path}");

    Repository repo = repository(path.getRepositoryName());
    if (repo == null) {
      send(null, HttpResponses.notFound(REPOSITORY_NOT_FOUND_MESSAGE), httpResponse);
      return;
    }
    log.debug(STR."Repository: {repo}");

    if (!repo.getConfiguration().isOnline()) {
      send(null, HttpResponses.serviceUnavailable("Repository offline"), httpResponse);
      return;
    }

    ViewFacet facet = repo.facet(ViewFacet.class);
    log.debug(STR."Dispatching to view facet: {facet}");

    // Dispatch the request
    Request request = buildRequest(httpRequest, path.getRemainingPath());
    dispatchAndSend(request, facet, httpResponseSenderSelector.sender(repo), httpResponse);
  }

  /**
   * Build view request from {@link HttpServletRequest}.
   */
  private Request buildRequest(final HttpServletRequest httpRequest, final String path) {
    Request.Builder builder = new Request.Builder()
        .headers(new HttpHeadersAdapter(httpRequest))
        .action(httpRequest.getMethod())
        .path(path)
        .parameters(new HttpParametersAdapter(httpRequest))
        .payload(new HttpRequestPayloadAdapter(httpRequest));

    if (HttpPartIteratorAdapter.isMultipart(httpRequest)) {
      builder.multiparts(new HttpPartIteratorAdapter(httpRequest));
    }

    // copy http-servlet-request attributes
    Enumeration<String> attributes = httpRequest.getAttributeNames();
    while (attributes.hasMoreElements()) {
      String name = attributes.nextElement();
      builder.attribute(name, httpRequest.getAttribute(name));
    }

    return builder.build();
  }

  /**
   * Record to hold dispatch result information
   */
  private record DispatchResult(Response response, Exception failure) {}

  @VisibleForTesting
  void dispatchAndSend(final Request request,
                       final ViewFacet facet,
                       final HttpResponseSender sender,
                       final HttpServletResponse httpResponse)
      throws Exception
  {
    // Use a virtual thread for the dispatch operation which may involve I/O
    DispatchResult result = Thread.startVirtualThread(() -> {
      try {
        return new DispatchResult(facet.dispatch(request), null);
      }
      catch (Exception e) {
        return new DispatchResult(null, e);
      }
    }).join();

    String describeFlags = request.getParameters().get(P_DESCRIBE);
    log.trace(STR."Describe flags: {describeFlags}");
    
    if (describeFlags != null) {
      send(request, describe(request, result.response(), result.failure(), describeFlags), httpResponse);
    }
    else {
      if (result.failure() != null) {
        throw result.failure();
      }
      log.debug(STR."Request: {request}");
      
      // Use a virtual thread for sending the response which involves I/O
      Thread.startVirtualThread(() -> {
        try {
          sender.send(request, result.response(), httpResponse);
        }
        catch (Exception e) {
          log.error(STR."Error sending response: {e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).join();
    }
  }

  @VisibleForTesting
  Response describe(final Request request, final Response response, final Exception exception, final String flags) {
    final Description description = new Description(ImmutableMap.of(
        // placeholder for the describeHtml.vm
        "path", StringEscapeUtils.escapeHtml(request.getPath()),
        "nexusUrl", BaseUrlHolder.get()
    ));
    
    if (exception != null) {
      descriptionHelper.describeException(description, exception);
    }
    descriptionHelper.describeRequest(description, request);
    if (response != null) {
      descriptionHelper.describeResponse(description, response);
    }

    DescribeType type = DescribeType.parse(flags);
    log.trace(STR."Describe type: {type}");
    
    // Use pattern matching for switch statement
    return switch (type) {
      case HTML -> {
        String html = descriptionRenderer.renderHtml(description);
        yield HttpResponses.ok(new StringPayload(html, ContentTypes.TEXT_HTML));
      }
      case JSON -> {
        String json = descriptionRenderer.renderJson(description);
        yield HttpResponses.ok(new StringPayload(json, ContentTypes.APPLICATION_JSON));
      }
      default -> throw new RuntimeException(STR."Invalid describe-type: {type}");
    };
  }

  /**
   * Send with default sender.
   *
   * Needed in a few places _before_ we have a repository instance to determine its specific sender.
   */
  @VisibleForTesting
  void send(@Nullable final Request request, final Response response, final HttpServletResponse httpResponse)
      throws ServletException, IOException
  {
    try {
      // Use a virtual thread for sending the response which involves I/O
      Thread.startVirtualThread(() -> {
        try {
          httpResponseSenderSelector.defaultSender().send(request, response, httpResponse);
        }
        catch (Exception e) {
          log.error(STR."Error sending response with default sender: {e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServletException("Response sending interrupted", e);
    }
    catch (Exception e) {
      if (e.getCause() instanceof ServletException servletException) {
        throw servletException;
      }
      if (e.getCause() instanceof IOException ioException) {
        throw ioException;
      }
      throw new ServletException("Error sending response", e);
    }
  }

  /**
   * @return the named repository or {@code null}
   */
  @Nullable
  private Repository repository(final String name) {
    log.debug(STR."Looking for repository: {name}");
    return repositoryManager.get(name);
  }
}
