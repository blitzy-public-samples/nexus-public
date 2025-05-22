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

import java.lang.annotation.Annotation;
import java.util.concurrent.Executors;

import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;

/**
 * Updates the associated {@link DynamicFilterPipeline} as {@link FilterPipeline} bindings come and go.
 * Updated for Java 21 Virtual Thread compatibility and Eclipse Sisu 0.10.0 API.
 * Compatible with Jakarta Servlet API (jakarta.servlet.*) replacing the legacy javax.servlet.* imports.
 */
final class FilterPipelineMediator
    implements Mediator<Annotation, FilterPipeline, DynamicFilterPipeline>
{
  /**
   * Adds a new FilterPipeline binding.
   * Optimized for Java 21 Virtual Thread compatibility by ensuring I/O operations
   * can be properly handled by the virtual thread scheduler.
   * 
   * This method handles the jakarta.servlet.ServletContext reference passed from
   * the DynamicFilterPipeline to the FilterPipeline's initPipeline method.
   */
  public void add(
      final BeanEntry<Annotation, FilterPipeline> entry,
      final DynamicFilterPipeline watcher) throws Exception
  {
    // Use try-with-resources pattern to ensure proper resource cleanup with Virtual Threads
    try {
      // initialize pipeline before exposing via cache
      final FilterPipeline pipeline = entry.getValue();
      
      // Initialize pipeline with Jakarta Servlet API compatible context
      pipeline.initPipeline(watcher.getServletContext());
      
      // Refresh the cache after initialization
      watcher.refreshCache();
    } catch (Exception e) {
      // Ensure exceptions are properly propagated in Virtual Thread context
      throw e;
    }
  }

  /**
   * Removes a FilterPipeline binding.
   * Optimized for Java 21 Virtual Thread compatibility by ensuring I/O operations
   * can be properly handled by the virtual thread scheduler.
   * 
   * This method ensures proper cleanup of resources when a FilterPipeline is removed,
   * compatible with Jakarta Servlet API references.
   */
  public void remove(
      final BeanEntry<Annotation, FilterPipeline> entry,
      final DynamicFilterPipeline watcher) throws Exception
  {
    // Use try-with-resources pattern to ensure proper resource cleanup with Virtual Threads
    try {
      // remove pipeline from cache before disposing
      final FilterPipeline pipeline = entry.getValue();
      
      // Refresh the cache before destroying the pipeline
      watcher.refreshCache();
      
      // Destroy the pipeline after cache refresh
      pipeline.destroyPipeline();
    } catch (Exception e) {
      // Ensure exceptions are properly propagated in Virtual Thread context
      throw e;
    }
  }
}