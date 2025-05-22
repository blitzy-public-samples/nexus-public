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
package com.google.inject.servlet;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.google.common.collect.Sets;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import org.eclipse.sisu.inject.BeanLocator;
import org.eclipse.sisu.wire.EntryListAdapter;

import static com.google.common.base.Preconditions.checkState;

/**
 * Dynamic {@code ServletPipeline} that can update its sequence of servlet definitions on-demand.
 * Includes patched methods from {@link ManagedServletPipeline} where delegating isn't possible.
 */
@Singleton
// don't use @Named, keep as implicit JIT-binding
final class DynamicServletPipeline
    extends ManagedServletPipeline
{
  static final Injector DUMMY_INJECTOR = Guice.createInjector();

  // dynamic list of definitions
  private final List<ServletDefinition> servletDefinitions;

  // stable cache of definitions - using volatile for thread-safe reads across virtual threads
  private volatile ServletDefinition[] servletDefinitionCache = {};
  
  // Virtual Thread executor for service operations
  private final ExecutorService virtualThreadExecutor;

  @Inject
  DynamicServletPipeline(final BeanLocator locator) {
    super(DUMMY_INJECTOR);

    // Use EntryListAdapter with BeanLocator for Eclipse Sisu 0.10.0 compatibility
    servletDefinitions = new EntryListAdapter<>(locator.locate(Key.get(ServletDefinition.class)));
    // Create a virtual thread per task executor for optimal I/O-bound operations
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Refreshes the servlet definition cache in a thread-safe manner.  
   * This method is synchronized to ensure thread safety during updates,
   * while the volatile field ensures visibility across threads (including virtual threads).
   */
  public synchronized void refreshCache() {
    final Object[] snapshot = servletDefinitions.toArray();
    servletDefinitionCache = Arrays.copyOf(snapshot, snapshot.length, ServletDefinition[].class);
    PipelineLogger.dump(servletDefinitionCache);
  }

  @Override
  public boolean service(ServletRequest request, ServletResponse response) throws IOException, ServletException {
    // Use virtual threads for service operations to improve concurrency
    try {
      return virtualThreadExecutor.submit(() -> {
        for (ServletDefinition servletDefinition : servletDefinitions()) {
          if (servletDefinition.service(request, response)) {
            return true;
          }
        }
        return false;
      }).get();
    } catch (Exception e) {
      // Use pattern matching for switch to handle different exception types
      Throwable cause = e.getCause();
      switch (cause) {
        case IOException ioe -> throw ioe;
        case ServletException se -> throw se;
        case null -> throw new ServletException("Error processing request", e);
        default -> throw new ServletException("Error processing request", cause);
      }
    }
  }

  @Override
  public void destroy() {
    Set<HttpServlet> destroyedSoFar = Sets.newIdentityHashSet();
    for (ServletDefinition servletDefinition : servletDefinitions()) {
      servletDefinition.destroy(destroyedSoFar);
    }
    virtualThreadExecutor.shutdown();
  }

  @Override
  RequestDispatcher getRequestDispatcher(final String path) {
    for (final ServletDefinition servletDefinition : servletDefinitions()) {
      if (servletDefinition.shouldServe(path)) {
        return new RequestDispatcher()
        {
          public void forward(
              ServletRequest servletRequest,
              ServletResponse servletResponse) throws ServletException, IOException
          {
            checkState(!servletResponse.isCommitted(),
                "Response has been committed--you can only call forward before"
                    + " committing the response (hint: don't flush buffers)");

            servletResponse.resetBuffer();

            ServletRequest requestToProcess =
                servletRequest instanceof HttpServletRequest ? wrapRequest((HttpServletRequest) servletRequest, path)
                    : servletRequest;

            doServiceImpl(requestToProcess, servletResponse);
          }

          public void include(
              ServletRequest servletRequest,
              ServletResponse servletResponse) throws ServletException, IOException
          {
            doServiceImpl(servletRequest, servletResponse);
          }

          private void doServiceImpl(
              ServletRequest servletRequest,
              ServletResponse servletResponse) throws ServletException, IOException
          {
            // Use virtual threads for request dispatching
            try {
              virtualThreadExecutor.submit(() -> {
                servletRequest.setAttribute(REQUEST_DISPATCHER_REQUEST, Boolean.TRUE);
                try {
                  servletDefinition.doService(servletRequest, servletResponse);
                }
                catch (ServletException | IOException e) {
                  throw new RuntimeException(e);
                }
                finally {
                  servletRequest.removeAttribute(REQUEST_DISPATCHER_REQUEST);
                }
                return null;
              }).get();
            } catch (Exception e) {
              // Use pattern matching for switch to handle different exception types
              Throwable cause = e.getCause();
              
              // Handle nested exceptions with pattern matching
              if (cause instanceof RuntimeException re && re.getCause() != null) {
                switch (re.getCause()) {
                  case ServletException se -> throw se;
                  case IOException ioe -> throw ioe;
                  default -> throw new ServletException("Error dispatching request", re.getCause());
                }
              }
              
              // Handle direct exceptions with pattern matching
              switch (cause) {
                case ServletException se -> throw se;
                case IOException ioe -> throw ioe;
                case null -> throw new ServletException("Error dispatching request", e);
                default -> throw new ServletException("Error dispatching request", cause);
              }
            }
          }
        };
      }
    }

    return null;
  }

  @Override
  protected boolean hasServletsMapped() {
    return servletDefinitionCache.length > 0;
  }

  private ServletDefinition[] servletDefinitions() {
    return servletDefinitionCache;
  }
}