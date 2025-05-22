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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
import com.google.common.base.Throwables;
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
      uri = uri + "?" + httpRequest.getQueryString();
    }

    if (log.isDebugEnabled()) {
      log.debug("Servicing: {} {} ({})", httpRequest.getMethod(), uri, httpRequest.getRequestURL());
    }

    // Create a virtual thread executor for processing this request
    try (ExecutorService executor = createVirtualThreadExecutor("service", uri)) {
      // Set thread name for better diagnostics
      String threadName = "nexus-request-" + httpRequest.getMethod() + "-" + uri.replaceAll("/", "_");
      
      // Submit the request processing to a virtual thread
      executor.submit(() -> {
        // Set MDC context for logging in the virtual thread
        MDC.put(getClass().getName(), uri);
        MDC.put("requestMethod", httpRequest.getMethod());
        MDC.put("requestURI", uri);
        
        try {
          doService(httpRequest, httpResponse);
          log.debug("Service completed on virtual thread");
        }
        catch (BadRequestException e) { // NOSONAR
          log.warn("Bad request. Reason: {}", e.getMessage());
          try {
            send(null, HttpResponses.badRequest(e.getMessage()), httpResponse);
          } catch (Exception ex) {
            log.error("Error sending bad request response", ex);
          }
        }
        catch (Exception e) {
          if (!(e instanceof AuthorizationException)) {
            log.warn("Failure servicing: {} {}", httpRequest.getMethod(), uri, e);
          }
          try {
            Throwables.propagateIfPossible(e, ServletException.class, IOException.class);
            throw new ServletException(e);
          } catch (Exception ex) {
            log.error("Error propagating exception", ex);
          }
        }
        finally {
          // Clean up MDC context
          MDC.remove(getClass().getName());
          MDC.remove("requestMethod");
          MDC.remove("requestURI");
        }
        return null;
      }).get(); // Wait for the virtual thread to complete
    } catch (Exception e) {
      log.error("Error processing request with virtual thread", e);
      throw new ServletException("Error processing request with virtual thread", e);
    }
  }

  protected void doService(final HttpServletRequest httpRequest, final HttpServletResponse httpResponse)
      throws Exception
  {
    if (sandboxEnabled) {
      httpResponse.setHeader(HttpHeaders.CONTENT_SECURITY_POLICY, SANDBOX);
    }
    httpResponse.setHeader(HttpHeaders.X_XSS_PROTECTION, "1; mode=block");

    // resolve repository for request
    RepositoryPath path = RepositoryPath.parse(httpRequest.getPathInfo());
    log.debug("Parsed path: {}", path);

    Repository repo = repository(path.getRepositoryName());
    if (repo == null) {
      send(null, HttpResponses.notFound(REPOSITORY_NOT_FOUND_MESSAGE), httpResponse);
      return;
    }
    log.debug("Repository: {}", repo);

    if (!repo.getConfiguration().isOnline()) {
      send(null, HttpResponses.serviceUnavailable("Repository offline"), httpResponse);
      return;
    }

    ViewFacet facet = repo.facet(ViewFacet.class);
    log.debug("Dispatching to view facet: {}", facet);

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

  @VisibleForTesting
  void dispatchAndSend(final Request request,
                       final ViewFacet facet,
                       final HttpResponseSender sender,
                       final HttpServletResponse httpResponse)
      throws Exception
  {
    // Set repository name in MDC for better diagnostics
    String repoName = request.getPath().split("/")[0];
    MDC.put("repository", repoName);
    
    try (ExecutorService executor = createVirtualThreadExecutor("send", request != null ? request.getPath() : "unknown")) {
      // Execute the dispatch operation on a virtual thread for improved concurrency
      Response response = executor.submit(() -> {
        try {
          // Set thread name for better diagnostics
          Thread.currentThread().setName("nexus-dispatch-" + repoName + "-" + request.getAction());
          return facet.dispatch(request);
        } catch (Exception e) {
          // Capture the exception to be handled outside the virtual thread
          log.debug("Exception during dispatch on virtual thread", e);
          throw e;
        }
      }).get(); // Wait for the virtual thread to complete
      
      String describeFlags = request.getParameters().get(P_DESCRIBE);
      log.trace("Describe flags: {}", describeFlags);
      
      if (describeFlags != null) {
        send(request, describe(request, response, null, describeFlags), httpResponse);
      } else {
        log.debug("Request: {}", request);
        
        // Send the response on a virtual thread for improved concurrency
        executor.submit(() -> {
          try {
            // Set thread name for better diagnostics
            Thread.currentThread().setName("nexus-send-" + repoName + "-" + request.getAction());
            sender.send(request, response, httpResponse);
          } catch (Exception e) {
            log.error("Error sending response on virtual thread", e);
            throw e;
          }
          return null;
        }).get(); // Wait for the virtual thread to complete
      }
    } catch (Exception e) {
      // If the exception was thrown during dispatch, unwrap and rethrow it
      Throwables.propagateIfPossible(e.getCause(), Exception.class);
      throw e;
    } finally {
      MDC.remove("repository");
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
    log.trace("Describe type: {}", type);
    switch (type) {
      case HTML: {
        String html = descriptionRenderer.renderHtml(description);
        return HttpResponses.ok(new StringPayload(html, ContentTypes.TEXT_HTML));
      }
      case JSON: {
        String json = descriptionRenderer.renderJson(description);
        return HttpResponses.ok(new StringPayload(json, ContentTypes.APPLICATION_JSON));
      }
      default:
        throw new RuntimeException("Invalid describe-type: " + type);
    }
  }

  /**
   * Send with default sender.
   *
   * Needed in a few places _before_ we have a repository instance to determine its specific sender.
   * Uses a virtual thread for improved concurrency.
   */
  @VisibleForTesting
  void send(@Nullable final Request request, final Response response, final HttpServletResponse httpResponse)
      throws ServletException, IOException
  {
    try (ExecutorService executor = createVirtualThreadExecutor("send", request != null ? request.getPath() : "unknown")) {
      // Execute the send operation on a virtual thread
      executor.submit(() -> {
        try {
          // Set thread name for better diagnostics
          Thread.currentThread().setName("nexus-default-send-" + 
              (request != null ? request.getAction() + "-" + request.getPath() : "unknown"));
          
          // Add diagnostic information to MDC
          if (request != null) {
            MDC.put("requestAction", request.getAction());
            MDC.put("requestPath", request.getPath());
          }
          
          httpResponseSenderSelector.defaultSender().send(request, response, httpResponse);
        } catch (Exception e) {
          log.error("Error sending response with default sender on virtual thread", e);
          throw e;
        } finally {
          // Clean up MDC
          if (request != null) {
            MDC.remove("requestAction");
            MDC.remove("requestPath");
          }
        }
        return null;
      }).get(); // Wait for the virtual thread to complete
    } catch (Exception e) {
      // Unwrap and rethrow the exception
      if (e.getCause() instanceof ServletException) {
        throw (ServletException) e.getCause();
      } else if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new ServletException("Error sending response with default sender", e);
      }
    }
  }

  /**
   * @return the named repository or {@code null}
   */
  @Nullable
  private Repository repository(final String name) {
    log.debug("Looking for repository: {}", name);
    return repositoryManager.get(name);
  }
  
  /**
   * Creates a virtual thread executor for handling repository requests.
   * This method centralizes the creation of virtual thread executors with consistent naming.
   *
   * @param operationType The type of operation being performed (e.g., "dispatch", "send")
   * @param repositoryName The name of the repository being accessed
   * @return An ExecutorService that creates virtual threads for each task
   */
  private ExecutorService createVirtualThreadExecutor(String operationType, String repositoryName) {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}