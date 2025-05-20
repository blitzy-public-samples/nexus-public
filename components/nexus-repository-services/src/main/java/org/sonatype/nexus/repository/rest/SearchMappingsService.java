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
package org.sonatype.nexus.repository.rest;

import java.util.concurrent.CompletableFuture;

/**
 * Provide a listing of all {@link SearchMapping}s that have been contributed via {@link SearchMappings}.
 *
 * @since 3.7
 */
public interface SearchMappingsService
{
  /**
   * Get all {@link SearchMapping}s.
   * 
   * This method is thread-safe and can be called from any context.
   */
  Iterable<SearchMapping> getAllMappings();
  
  /**
   * Get all {@link SearchMapping}s asynchronously using Java 21 Virtual Threads.
   * 
   * <p>This method leverages Java 21 Virtual Threads for improved concurrency performance when
   * retrieving search mappings. Virtual Threads provide lightweight concurrency with minimal
   * overhead, allowing thousands of concurrent operations without the resource constraints
   * of traditional platform threads.</p>
   * 
   * <p>Implementation notes:</p>
   * <ul>
   *   <li>The returned CompletableFuture will be completed on a Virtual Thread</li>
   *   <li>This method is non-blocking and returns immediately</li>
   *   <li>For optimal performance, avoid blocking operations when processing the result</li>
   *   <li>Error handling should be managed through CompletableFuture's exception handling methods</li>
   * </ul>
   * 
   * @return A CompletableFuture that will be completed with all search mappings
   * @since Java 21
   */
  default CompletableFuture<Iterable<SearchMapping>> getAllMappingsAsync() {
    return CompletableFuture.supplyAsync(this::getAllMappings, 
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
  }
}