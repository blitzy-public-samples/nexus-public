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
package com.sonatype.nexus.ssl.plugin.spi;

import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.capability.CapabilityReference;

/**
 * Manages retrieve / update of TrustStore capabilities / type.
 * <p>
 * This interface provides methods to retrieve and manage TrustStore capabilities.
 * Implementations should leverage Java 21 features such as Virtual Threads for
 * I/O-bound operations to improve performance and scalability.
 *
 * @since ssl 1.0
 */
public interface CapabilityManager
{
  /**
   * Retrieves a capability reference by its ID.
   *
   * @param id The unique identifier of the capability to retrieve
   * @return The capability reference, or null if not found
   */
  CapabilityReference get(String id);

  /**
   * Enables or disables a capability by its ID.
   * <p>
   * This operation may involve I/O and should be implemented using Virtual Threads
   * when running in a Java 21 environment for improved scalability.
   *
   * @param id The unique identifier of the capability to enable/disable
   * @param enabled True to enable the capability, false to disable it
   * @return The updated capability reference
   * @throws Exception If an error occurs during the operation
   */
  CapabilityReference enable(String id, boolean enabled) throws Exception;
  
  /**
   * Asynchronously enables or disables a capability by its ID.
   * <p>
   * This method leverages Java 21 Virtual Threads for non-blocking I/O operations,
   * providing better scalability for concurrent operations. Implementations should
   * use {@code Executors.newVirtualThreadPerTaskExecutor()} for executing the operation.
   *
   * @param id The unique identifier of the capability to enable/disable
   * @param enabled True to enable the capability, false to disable it
   * @return A CompletableFuture that will complete with the updated capability reference
   *         or complete exceptionally if an error occurs
   * @since 3.60.0
   */
  default CompletableFuture<CapabilityReference> enableAsync(String id, boolean enabled) {
    CompletableFuture<CapabilityReference> future = new CompletableFuture<>();
    try {
      // Default implementation calls the synchronous method
      // Implementations should override this with a proper Virtual Thread implementation
      CapabilityReference result = enable(id, enabled);
      future.complete(result);
    } 
    catch (Exception e) {
      future.completeExceptionally(e);
    }
    return future;
  }
}