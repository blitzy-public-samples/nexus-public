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
package org.sonatype.nexus.thread;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;

/**
 * Factory for creating {@link ForkJoinPool} instances optimized for different workloads in Java 21.
 * <p>
 * This factory provides methods to create ForkJoinPools for both CPU-bound and I/O-bound operations,
 * with appropriate configurations for each use case. It also provides integration with Java 21's
 * Virtual Threads when appropriate for the workload.
 * <p>
 * Guidelines for usage:
 * <ul>
 *   <li>For CPU-bound operations (computation, data processing): Use {@link #createCpuBoundPool}</li>
 *   <li>For I/O-bound operations (network, disk): Consider using {@link #createVirtualThreadExecutor} instead</li>
 *   <li>For mixed workloads: Use {@link #createForkJoinPool} with appropriate parallelism settings</li>
 * </ul>
 *
 * @since 3.20
 */
public class NexusForkJoinPoolFactory
{
  private NexusForkJoinPoolFactory() {
    // static utility class
  }

  // Copied from the constant in {@link ForkJoinPool} because the constant is not visible.
  private static final int MAX_CAP = 0x7fff;
  
  /**
   * Default system property for configuring Virtual Thread scheduler parallelism.
   */
  public static final String VIRTUAL_THREAD_SCHEDULER_PARALLELISM = "jdk.virtualThreadScheduler.parallelism";

  /**
   * Creates a ForkJoinPool with default settings based on available processors.
   * <p>
   * This method creates a ForkJoinPool with parallelism equal to the number of available processors,
   * using a custom thread factory for proper thread naming. This is suitable for general-purpose
   * workloads in Java 21.
   *
   * @param threadNamePrefix prefix to use for worker thread names
   * @return a new ForkJoinPool instance
   */
  public static ForkJoinPool createForkJoinPool(final String threadNamePrefix) {
    NexusForkJoinWorkerThreadFactory nexusForkJoinWorkerThreadFactory =
        new NexusForkJoinWorkerThreadFactory(threadNamePrefix);
    return new ForkJoinPool(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
        nexusForkJoinWorkerThreadFactory, null, false);
  }
  
  /**
   * Creates a ForkJoinPool optimized for CPU-bound operations in Java 21.
   * <p>
   * This method creates a ForkJoinPool with parallelism equal to the number of available processors,
   * which is optimal for CPU-bound workloads. It uses a custom thread factory for proper thread naming
   * and configures the pool for LIFO processing mode, which is better for work-stealing with CPU-bound tasks.
   * <p>
   * Use this for computationally intensive operations like data processing, compression, or calculations.
   *
   * @param threadNamePrefix prefix to use for worker thread names
   * @return a new ForkJoinPool instance optimized for CPU-bound operations
   */
  public static ForkJoinPool createCpuBoundPool(final String threadNamePrefix) {
    NexusForkJoinWorkerThreadFactory threadFactory = new NexusForkJoinWorkerThreadFactory(threadNamePrefix);
    int parallelism = Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors());
    
    // For CPU-bound operations, use async mode = false (LIFO mode) for better work-stealing
    return new ForkJoinPool(parallelism, threadFactory, null, false);
  }
  
  /**
   * Creates a ForkJoinPool optimized for I/O-bound operations in Java 21.
   * <p>
   * This method creates a ForkJoinPool with higher parallelism than the number of available processors,
   * which is beneficial for I/O-bound workloads where threads spend time waiting. It uses a custom thread
   * factory for proper thread naming and configures the pool for FIFO processing mode, which is better
   * for I/O-bound tasks.
   * <p>
   * Note: For most I/O-bound operations in Java 21, consider using {@link #createVirtualThreadExecutor}
   * instead, as Virtual Threads are specifically designed for this purpose.
   *
   * @param threadNamePrefix prefix to use for worker thread names
   * @param parallelism the parallelism level (number of threads)
   * @return a new ForkJoinPool instance optimized for I/O-bound operations
   */
  public static ForkJoinPool createIoBoundPool(final String threadNamePrefix, final int parallelism) {
    NexusForkJoinWorkerThreadFactory threadFactory = new NexusForkJoinWorkerThreadFactory(threadNamePrefix);
    int effectiveParallelism = Math.min(MAX_CAP, Math.max(parallelism, Runtime.getRuntime().availableProcessors() * 2));
    
    // For I/O-bound operations, use async mode = true (FIFO mode) for better throughput
    return new ForkJoinPool(effectiveParallelism, threadFactory, null, true);
  }
  
  /**
   * Creates an Executor that creates a new virtual thread for each task.
   * <p>
   * This method leverages Java 21's Virtual Threads, which are lightweight threads managed by the JVM.
   * Virtual Threads are ideal for I/O-bound operations as they have minimal overhead and can scale to
   * thousands of concurrent operations without exhausting system resources.
   * <p>
   * Use this for I/O-bound operations like network calls, file operations, or database access.
   *
   * @return an Executor that creates a new virtual thread for each task
   */
  public static Executor createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates an Executor that creates a new virtual thread for each task with custom thread factory.
   * <p>
   * This method leverages Java 21's Virtual Threads with a custom thread factory for naming and
   * configuration. Virtual Threads are ideal for I/O-bound operations as they have minimal overhead
   * and can scale to thousands of concurrent operations without exhausting system resources.
   * <p>
   * Use this for I/O-bound operations like network calls, file operations, or database access
   * where custom thread naming is required.
   *
   * @param threadNamePrefix prefix to use for virtual thread names
   * @return an Executor that creates a new virtual thread for each task
   */
  public static Executor createVirtualThreadExecutor(final String threadNamePrefix) {
    ThreadFactory threadFactory = Thread.ofVirtual()
        .name(threadNamePrefix, 0)
        .factory();
    return Executors.newThreadPerTaskExecutor(threadFactory);
  }
  
  /**
   * Returns the optimal parallelism level for the current system based on available processors.
   * <p>
   * This method considers the number of available processors and any system property overrides
   * to determine the optimal parallelism level for ForkJoinPools in Java 21.
   *
   * @return the optimal parallelism level
   */
  public static int getOptimalParallelism() {
    // Check if virtual thread scheduler parallelism is explicitly configured
    String parallelismProperty = System.getProperty(VIRTUAL_THREAD_SCHEDULER_PARALLELISM);
    if (parallelismProperty != null && !parallelismProperty.isEmpty()) {
      try {
        return Integer.parseInt(parallelismProperty);
      }
      catch (NumberFormatException e) {
        // Fall back to default if property is invalid
      }
    }
    
    // Default to available processors
    return Runtime.getRuntime().availableProcessors();
  }
}
