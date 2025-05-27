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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.sonatype.nexus.thread.internal.MDCUtils;

/**
 * A FilterChain that dispatches to the guice-servlet filter pipeline and then to the servlet pipeline.
 * This implementation is optimized for Java 21 Virtual Threads and properly handles thread context.
 *
 * @since 3.60
 */
class FilterChainInvocation
    implements FilterChain
{
  private final FilterDefinition[] filterDefinitions;
  private final DynamicServletPipeline servletPipeline;
  private final FilterChain proceedingFilterChain;
  private int filterIndex = 0;

  FilterChainInvocation(
      final FilterDefinition[] filterDefinitions,
      final DynamicServletPipeline servletPipeline,
      final FilterChain proceedingFilterChain)
  {
    this.filterDefinitions = filterDefinitions;
    this.servletPipeline = servletPipeline;
    this.proceedingFilterChain = proceedingFilterChain;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response)
      throws IOException, ServletException
  {
    // Ensure MDC context is properly set for Virtual Thread execution
    if (MDCUtils.isVirtualThread()) {
      MDCUtils.setIfNeeded();
    }
    
    // If we've reached the end of the filter chain, dispatch to the servlet pipeline
    if (filterIndex >= filterDefinitions.length) {
      // If servletPipeline has no servlets mapped, proceed to the next filter chain
      if (servletPipeline.hasServletsMapped()) {
        servletPipeline.dispatch(request, response, proceedingFilterChain);
      }
      else if (proceedingFilterChain != null) {
        proceedingFilterChain.doFilter(request, response);
      }
    }
    else {
      // Otherwise, dispatch to the next filter in the chain
      final FilterDefinition filterDefinition = filterDefinitions[filterIndex++];
      try {
        filterDefinition.doFilter(request, response, this);
      }
      catch (IOException | ServletException e) {
        throw e;
      }
      catch (Exception e) {
        // Wrap any other exceptions as ServletException for proper error handling
        throw new ServletException(e);
      }
    }
  }
}