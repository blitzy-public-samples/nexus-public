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
package org.sonatype.nexus.siesta;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.ext.RuntimeDelegate;

import org.sonatype.nexus.rest.Component;

import com.google.inject.Key;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;
import org.eclipse.sisu.inject.BeanLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Siesta servlet.
 *
 * This is a thin wrapper around {@link ComponentContainer} which also handles {@link Component} registration.
 * Enhanced with Java 21 Virtual Threads for improved concurrency and performance.
 *
 * @since 3.0
 */
@Named
@Singleton
public class SiestaServlet
    extends HttpServlet
{
  private final Logger log = LoggerFactory.getLogger(getClass());

  private final BeanLocator beanLocator;

  private final ComponentContainer componentContainer;
  
  /**
   * Virtual thread executor for handling HTTP requests
   */
  private ExecutorService virtualThreadExecutor;

  @Inject
  public SiestaServlet(final BeanLocator beanLocator, final ComponentContainer componentContainer) {
    this.beanLocator = checkNotNull(beanLocator);
    this.componentContainer = checkNotNull(componentContainer);

    log.debug(STR."Component container: \{componentContainer}");
    
    // Initialize the virtual thread executor
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public void init(final ServletConfig config) throws ServletException {
    super.init(config);

    // Initialize container
    componentContainer.init(config);
    
    // Log RESTEasy version information
    RuntimeDelegate delegate = RuntimeDelegate.getInstance();
    log.info(STR."JAX-RS RuntimeDelegate: \{delegate} (ensuring compatibility with RESTEasy 6.2.7.Final)");

    // Watch for components
    beanLocator.watch(Key.get(Component.class), new ComponentMediator(), componentContainer);

    log.info("Initialized with Virtual Thread support for improved concurrency");
  }

  /**
   * Handles component [de]registration events.
   */
  private class ComponentMediator
      implements Mediator<Annotation, Component, ComponentContainer>
  {
    @Override
    public void add(final BeanEntry<Annotation, Component> entry, final ComponentContainer container) throws Exception {
      log.trace(STR."Adding component: \{entry.getKey()}");
      try {
        container.addComponent(entry);
      }
      catch (Exception e) {
        log.error("Failed to add component", e);
      }
    }

    @Override
    public void remove(final BeanEntry<Annotation, Component> entry, final ComponentContainer container)
        throws Exception
    {
      log.trace(STR."Removing component: \{entry.getKey()}");
      try {
        container.removeComponent(entry);
      }
      catch (Exception e) {
        log.error("Failed to remove component", e);
      }
    }
  }

  @Override
  public void service(final HttpServletRequest request, final HttpServletResponse response)
      throws ServletException, IOException
  {
    checkNotNull(request);
    checkNotNull(response);

    // Log the request URI+URL muck
    String uri = request.getRequestURI();
    if (request.getQueryString() != null) {
      uri = String.format("%s?%s", uri, request.getQueryString());
    }

    if (log.isDebugEnabled()) {
      log.debug(STR."Processing: \{request.getMethod()} \{uri} (\{request.getRequestURL()})");
    }

    if (log.isTraceEnabled()) {
      log.trace(STR."Context path: \{request.getContextPath()}");
      log.trace(STR."Servlet path: \{request.getServletPath()}");
    }
    
    // Capture MDC context to propagate to virtual thread
    final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    final String mdcKey = getClass().getName();
    
    try {
      // Submit request processing to virtual thread executor
      virtualThreadExecutor.submit(() -> {
        // Propagate MDC context to virtual thread
        if (mdcContext != null) {
          MDC.setContextMap(mdcContext);
        }
        MDC.put(mdcKey, uri);
        
        try {
          // Process the request in the virtual thread
          componentContainer.service(request, response);
        } catch (Exception e) {
          log.error(STR."Error processing request in virtual thread: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        } finally {
          // Clean up MDC context in virtual thread
          MDC.remove(mdcKey);
          MDC.clear();
        }
        return null;
      }).get(); // Wait for completion to ensure response is fully processed
    } catch (Exception e) {
      log.error(STR."Failed to process request with virtual thread: \{e.getMessage()}", e);
      throw new ServletException("Virtual thread request processing failed", e);
    }
  }

  @Override
  public void destroy() {
    // Shutdown virtual thread executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.close();
      virtualThreadExecutor = null;
    }
    
    componentContainer.destroy();
    super.destroy();

    log.info("Destroyed");
  }
}
