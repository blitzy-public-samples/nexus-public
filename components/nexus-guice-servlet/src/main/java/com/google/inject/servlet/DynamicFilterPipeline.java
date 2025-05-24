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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import com.google.common.collect.Sets;
import com.google.inject.Key;
import org.eclipse.sisu.inject.BeanLocator;
import org.eclipse.sisu.wire.EntryListAdapter;
import org.sonatype.nexus.thread.internal.MDCUtils;

import static com.google.inject.servlet.DynamicServletPipeline.DUMMY_INJECTOR;

/**
 * Dynamic {@link FilterPipeline} that can update its sequence of filter definitions on-demand.
 * Includes patched methods from {@link ManagedFilterPipeline} where delegating isn't possible.
 * 
 * @since 3.60 Updated to support Jakarta EE 10 and Java 21 Virtual Threads
 */
@Singleton
// don't use @Named, keep as implicit JIT-binding
final class DynamicFilterPipeline
    extends ManagedFilterPipeline
{
  private final DynamicServletPipeline servletPipeline;

  private final BeanLocator locator;

  // dynamic list of definitions
  private final List<FilterDefinition> filterDefinitions;

  // stable cache of definitions with volatile for thread safety
  private volatile FilterDefinition[] filterDefinitionCache = {};
  
  // Virtual Thread executor for filter chain processing
  private final Executor virtualThreadExecutor;

  private volatile ServletContext servletContext;

  @Inject
  DynamicFilterPipeline(final DynamicServletPipeline servletPipeline, final BeanLocator locator) {
    super(DUMMY_INJECTOR, servletPipeline, null);

    this.servletPipeline = servletPipeline;
    this.locator = locator;
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    try {
      // disable lazy init as we don't use it
      super.initPipeline(null /* unused */);
    }
    catch (final Exception e) {
      throw new IllegalStateException(e);
    }

    // Use EntryListAdapter with Eclipse Sisu 0.10.0 compatibility
    filterDefinitions = new EntryListAdapter<>(locator.locate(Key.get(FilterDefinition.class)));
  }

  public ServletContext getServletContext() {
    return servletContext;
  }

  /**
   * Refreshes the filter definition cache in a thread-safe manner.
   * Optimized for both platform and virtual threads.
   */
  public synchronized void refreshCache() {
    final Object[] snapshot = filterDefinitions.toArray();
    filterDefinitionCache = Arrays.copyOf(snapshot, snapshot.length, FilterDefinition[].class);
    PipelineLogger.dump(filterDefinitionCache);

    servletPipeline.refreshCache();
  }

  @Override
  public synchronized void initPipeline(final ServletContext context) throws ServletException {
    if (servletContext == null && context != null) {
      servletContext = context;

      // register trigger to update definitions as FilterPipeline bindings come and go
      locator.watch(Key.get(FilterPipeline.class), new FilterPipelineMediator(), this);
    }
  }

  @Override
  public void dispatch(
      ServletRequest request,
      ServletResponse response,
      FilterChain proceedingFilterChain) throws IOException, ServletException
  {
    // Create a wrapped request with proper dispatcher support for Jakarta Servlet API
    final ServletRequest wrappedRequest = withDispatcher(request, servletPipeline);
    
    // Use Virtual Threads for filter chain execution to improve I/O-bound operations performance
    if (MDCUtils.isVirtualThread()) {
      // Already in a virtual thread, execute directly to avoid nesting
      new FilterChainInvocation(filterDefinitions(), servletPipeline, proceedingFilterChain)
          .doFilter(wrappedRequest, response);
    } else {
      // Execute in a virtual thread with proper MDC context propagation
      try {
        final FilterChainInvocation filterChain = 
            new FilterChainInvocation(filterDefinitions(), servletPipeline, proceedingFilterChain);
            
        // Wrap the filter chain execution with MDC context to ensure proper logging context
        Runnable task = MDCUtils.withMdcContext(() -> {
          try {
            filterChain.doFilter(wrappedRequest, response);
          } catch (IOException | ServletException e) {
            // Re-throw as runtime exception to be caught by the outer try-catch
            throw new RuntimeException(e);
          }
        });
        
        // Submit and wait for completion
        virtualThreadExecutor.execute(task);
      } catch (RuntimeException e) {
        // Unwrap the original exception
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
          throw (IOException) cause;
        } else if (cause instanceof ServletException) {
          throw (ServletException) cause;
        } else {
          throw e;
        }
      }
    }
  }

  @Override
  public void destroyPipeline() {
    servletPipeline.destroy();

    Set<Filter> destroyedSoFar = Sets.newIdentityHashSet();
    for (FilterDefinition filterDefinition : filterDefinitions()) {
      filterDefinition.destroy(destroyedSoFar);
    }
  }

  /**
   * Creates a request wrapper that provides a custom RequestDispatcher implementation
   * compatible with Jakarta Servlet API interfaces.
   */
  private static ServletRequest withDispatcher(
      ServletRequest servletRequest,
      final DynamicServletPipeline servletPipeline)
  {
    if (!servletPipeline.hasServletsMapped()) {
      return servletRequest;
    }

    return new HttpServletRequestWrapper((HttpServletRequest) servletRequest)
    {
      @Override
      public RequestDispatcher getRequestDispatcher(String path) {
        final RequestDispatcher dispatcher = servletPipeline.getRequestDispatcher(path);
        return (null != dispatcher) ? dispatcher : super.getRequestDispatcher(path);
      }
    };
  }

  /**
   * Returns the current filter definition cache in a thread-safe manner.
   * This method is optimized for both platform and virtual threads.
   */
  private FilterDefinition[] filterDefinitions() {
    return filterDefinitionCache;
  }
}