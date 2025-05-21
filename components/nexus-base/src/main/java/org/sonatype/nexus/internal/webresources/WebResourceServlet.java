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
package org.sonatype.nexus.internal.webresources;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.servlet.ServletHelper;
import org.sonatype.nexus.servlet.XFrameOptions;
import org.sonatype.nexus.webresources.WebResource;
import org.sonatype.nexus.webresources.WebResource.Prepareable;
import org.sonatype.nexus.webresources.WebResourceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.net.HttpHeaders.CACHE_CONTROL;
import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.HttpHeaders.IF_MODIFIED_SINCE;
import static com.google.common.net.HttpHeaders.LAST_MODIFIED;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static com.google.common.net.HttpHeaders.X_XSS_PROTECTION;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;

/**
 * Provides access to resources via configured {@link WebResourceService}.
 * Uses Java 21 Virtual Threads for improved concurrency and resource utilization.
 *
 * @since 2.8
 */
@Singleton
@Named
public class WebResourceServlet
    extends HttpServlet
{
  private static final Logger log = LoggerFactory.getLogger(WebResourceServlet.class);

  private final WebResourceService webResources;

  private final long maxAgeSeconds;

  private final XFrameOptions xframeOptions;

  private static final String INDEX_PATH = "/index.html";
  
  /**
   * Virtual Thread executor for handling resource serving tasks.
   * Uses a dedicated executor for web resource serving to avoid impacting other operations.
   */
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public WebResourceServlet(
      final WebResourceService webResources,
      final XFrameOptions xframeOptions,
      @Named("${nexus.webresources.maxAge:-30days}") final Time maxAge)
  {
    this.webResources = checkNotNull(webResources);
    this.maxAgeSeconds = checkNotNull(maxAge.toSeconds());
    this.xframeOptions = checkNotNull(xframeOptions);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    log.info("Max-age: {} ({} seconds)", maxAge, maxAgeSeconds);
    log.info("Initialized WebResourceServlet with Virtual Threads support");
  }

  @Override
  protected void doGet(
      final HttpServletRequest request,
      final HttpServletResponse response) throws ServletException, IOException
  {
    String path = request.getPathInfo();

    // default-page handling
    if ("".equals(path) || "/".equals(path)) {
      path = INDEX_PATH;
    }
    else if (path.endsWith("/")) {
      path += "index.html";
    }
    else if (INDEX_PATH.equals(path)) {
      response.sendRedirect(BaseUrlHolder.getRelativePath()); // prevent browser from sending XHRs to incorrect URL -
                                                              // NEXUS-14593
      return;
    }

    WebResource resource = webResources.getResource(path);
    if (resource == null) {
      // if there is an index.html for the requested path, redirect to it
      if (webResources.getResource(path + INDEX_PATH) != null) {
        String location = String.format("%s%s/", BaseUrlHolder.getRelativePath(), path);
        log.debug("Redirecting: {} -> {}", path, location);
        response.sendRedirect(location);
      }
      else {
        response.sendError(SC_NOT_FOUND);
      }
      return;
    }

    // Use Virtual Threads to handle resource serving
    // This allows for high concurrency without blocking platform threads
    try {
      // Submit the resource serving task to the virtual thread executor
      // This allows the servlet container thread to return to the pool quickly
      virtualThreadExecutor.submit(() -> {
        try {
          serveResource(resource, request, response);
        }
        catch (IOException e) {
          log.warn("Error serving resource {}: {}", path, e.getMessage());
          // Cannot call sendError here as it might be too late (response already committed)
          // Just log the error and let the client handle the incomplete response
        }
        catch (Exception e) {
          log.error("Unexpected error serving resource {}", path, e);
        }
      }).get(); // Wait for completion to ensure response is fully sent
    }
    catch (Exception e) {
      log.error("Failed to process request for {}", path, e);
      if (!response.isCommitted()) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process request");
      }
    }
  }

  /**
   * Serves the requested resource using non-blocking I/O patterns where possible.
   * Optimized for execution in a Virtual Thread context.
   */
  private void serveResource(
      WebResource resource,
      final HttpServletRequest request,
      final HttpServletResponse response) throws IOException
  {
    log.trace("Serving resource: {} (thread: {})", resource, Thread.currentThread());

    // NEXUS-6569 Add X-Frame-Options header
    response.setHeader(X_FRAME_OPTIONS, xframeOptions.getValueForPath(request.getPathInfo()));
    response.setHeader(X_XSS_PROTECTION, "1; mode=block");

    // support resources which need to be prepared before serving
    if (resource instanceof Prepareable) {
      resource = ((Prepareable) resource).prepare();
      checkState(resource != null, "Prepared resource is null");
    }
    checkNotNull(resource);

    String contentType = resource.getContentType();
    if (contentType == null) {
      contentType = WebResource.UNKNOWN_CONTENT_TYPE;
    }
    response.setHeader(CONTENT_TYPE, contentType);
    response.setDateHeader(LAST_MODIFIED, resource.getLastModified());

    // set content-length, complain if invalid
    long size = resource.getSize();
    if (size < 0) {
      log.warn("Resource {} has invalid size: {}", resource.getPath(), size);
    }
    response.setHeader(CONTENT_LENGTH, String.valueOf(size));

    // set max-age if cacheable
    if (resource.isCacheable()) {
      response.setHeader(CACHE_CONTROL, "max-age=" + maxAgeSeconds);
    }
    else {
      ServletHelper.addNoCacheResponseHeaders(response);
    }

    // honor if-modified-since GETs
    long ifModifiedSince = request.getDateHeader(IF_MODIFIED_SINCE);
    // handle conditional GETs
    if (ifModifiedSince > -1 && resource.getLastModified() <= ifModifiedSince) {
      // this is a conditional GET using time-stamp, and resource is not modified
      response.setStatus(SC_NOT_MODIFIED);
    }
    else {
      // send the content only if needed (this method will be called for HEAD requests too)
      if ("GET".equalsIgnoreCase(request.getMethod())) {
        // Use try-with-resources to ensure proper resource cleanup
        try (InputStream in = resource.getInputStream()) {
          // Use non-blocking I/O patterns for sending content
          // ServletHelper.sendContent handles the actual I/O operations
          ServletHelper.sendContent(in, response);
        }
      }
    }
  }
  
  @Override
  public void destroy() {
    // Ensure proper cleanup of the virtual thread executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      log.debug("Virtual thread executor shutdown");
    }
    super.destroy();
  }
}