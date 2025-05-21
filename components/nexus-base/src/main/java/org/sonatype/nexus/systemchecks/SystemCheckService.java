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
package org.sonatype.nexus.systemchecks;

import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Service providing access to the results of System checks for Nexus instances.
 */
public interface SystemCheckService
{
  /**
   * Return the available system check results.
   * 
   * <p>Implementations should leverage Java 21 Virtual Threads for parallel health check execution
   * to improve performance and scalability. Virtual Threads are lightweight threads that are managed
   * by the JVM rather than the OS, making them ideal for I/O-bound operations like health checks.</p>
   * 
   * <p>Implementation guidelines:</p>
   * <ul>
   *   <li>Use {@code Executors.newVirtualThreadPerTaskExecutor()} to create a virtual thread executor</li>
   *   <li>Submit each health check as a separate task to the executor</li>
   *   <li>Collect results from all tasks and return as a stream</li>
   *   <li>Avoid thread pools as Virtual Threads are designed to be created and discarded</li>
   *   <li>Be cautious with synchronized blocks as they can cause Virtual Thread pinning</li>
   * </ul>
   * 
   * <p>Example implementation pattern:</p>
   * <pre>{@code
   * public Stream<NodeSystemCheckResult> getResults() {
   *     List<NodeSystemCheckResult> results = new ArrayList<>();
   *     
   *     try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *         List<Future<NodeSystemCheckResult>> futures = new ArrayList<>();
   *         
   *         // Submit each health check as a separate task
   *         for (SystemCheck check : systemChecks) {
   *             futures.add(executor.submit(() -> performCheck(check)));
   *         }
   *         
   *         // Collect results
   *         for (Future<NodeSystemCheckResult> future : futures) {
   *             try {
   *                 results.add(future.get());
   *             } catch (Exception e) {
   *                 // Handle exceptions appropriately
   *             }
   *         }
   *     }
   *     
   *     return results.stream();
   * }
   * }</pre>
   * 
   * @return Stream of system check results from all Nexus nodes
   */
  Stream<NodeSystemCheckResult> getResults();
}