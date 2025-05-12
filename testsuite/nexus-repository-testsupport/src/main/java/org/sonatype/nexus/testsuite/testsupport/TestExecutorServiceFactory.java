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
package org.sonatype.nexus.testsuite.testsupport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Factory for creating ExecutorService instances optimized for testing.
 * <p>
 * This factory creates appropriate executor services based on the runtime environment and test configuration.
 * When running with Java 21 and the virtual-threads profile activated, it will create executors that use
 * virtual threads for improved concurrency and performance during testing.
 * </p>
 */
public class TestExecutorServiceFactory 
{
  private static final String VIRTUAL_THREADS_PROPERTY = "test.virtual.threads";
  
  private static final int DEFAULT_THREAD_COUNT = 16;
  
  /**
   * Creates an ExecutorService appropriate for the current test environment.
   * <p>
   * When the system property "test.virtual.threads" is set to "true" (which happens when the
   * virtual-threads Maven profile is active), this will create a virtual thread per task executor.
   * Otherwise, it will create a fixed thread pool with the default size.
   * </p>
   *
   * @return an ExecutorService instance
   */
  public static ExecutorService create() {
    return create(DEFAULT_THREAD_COUNT);
  }
  
  /**
   * Creates an ExecutorService appropriate for the current test environment with the specified thread count.
   * <p>
   * When the system property "test.virtual.threads" is set to "true" (which happens when the
   * virtual-threads Maven profile is active), this will create a virtual thread per task executor.
   * Otherwise, it will create a fixed thread pool with the specified size.
   * </p>
   *
   * @param threads the number of threads to use if not using virtual threads
   * @return an ExecutorService instance
   */
  public static ExecutorService create(final int threads) {
    boolean useVirtualThreads = Boolean.parseBoolean(System.getProperty(VIRTUAL_THREADS_PROPERTY, "false"));
    
    if (useVirtualThreads) {
      try {
        // Use virtual threads when available (Java 21+) and enabled
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-virtual-", 0).factory();
        return Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      }
      catch (NoSuchMethodError e) {
        // Fall back to platform threads if virtual threads are not available
        return Executors.newFixedThreadPool(threads);
      }
    }
    else {
      return Executors.newFixedThreadPool(threads);
    }
  }
}