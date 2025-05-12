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
package org.sonatype.nexus.testsuite.testsupport.performance;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
 import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating virtual thread executors when running on Java 21.
 * 
 * <p>This class uses reflection to create virtual thread executors when running on Java 21,
 * and falls back to platform thread executors on earlier Java versions. This allows the same
 * code to be used regardless of the Java version, with virtual threads automatically utilized
 * when available.</p>
 *
 * @since 3.60
 */
public class VirtualThreadExecutorFactory 
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadExecutorFactory.class);
  
  private static final boolean VIRTUAL_THREADS_SUPPORTED;
  
  static {
    boolean supported = false;
    try {
      // Check if Thread.ofVirtual() method exists (Java 21 feature)
      Class.forName("java.lang.Thread").getMethod("ofVirtual");
      supported = true;
      log.info("Virtual threads are supported in this Java runtime");
    } 
    catch (NoSuchMethodException | ClassNotFoundException e) {
      log.info("Virtual threads are not supported in this Java runtime");
    }
    VIRTUAL_THREADS_SUPPORTED = supported;
  }
  
  private VirtualThreadExecutorFactory() {
    // Utility class
  }
  
  /**
   * Creates an executor service using virtual threads if running on Java 21,
   * or a fixed thread pool if running on an earlier Java version.
   * 
   * @param fallbackThreadCount number of threads to use if virtual threads are not available
   * @return an executor service
   */
  public static ExecutorService createExecutor(int fallbackThreadCount) {
    if (VIRTUAL_THREADS_SUPPORTED) {
      try {
        // Use reflection to call: Executors.newVirtualThreadPerTaskExecutor()
        Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
        return (ExecutorService) method.invoke(null);
      } 
      catch (Exception e) {
        log.warn("Failed to create virtual thread executor, falling back to platform threads", e);
      }
    }
    
    // Fall back to platform threads
    return Executors.newFixedThreadPool(fallbackThreadCount);
  }
  
  /**
   * Creates a thread factory that produces virtual threads if running on Java 21,
   * or platform threads if running on an earlier Java version.
   * 
   * @param namePrefix prefix for thread names
   * @return a thread factory
   */
  public static ThreadFactory createThreadFactory(String namePrefix) {
    if (VIRTUAL_THREADS_SUPPORTED) {
      try {
        // Use reflection to call: Thread.ofVirtual().name(namePrefix, 0).factory()
        Class<?> threadClass = Class.forName("java.lang.Thread");
        Method ofVirtualMethod = threadClass.getMethod("ofVirtual");
        Object builder = ofVirtualMethod.invoke(null);
        
        Method nameMethod = builder.getClass().getMethod("name", String.class, long.class);
        Object namedBuilder = nameMethod.invoke(builder, namePrefix, 0);
        
        Method factoryMethod = namedBuilder.getClass().getMethod("factory");
        return (ThreadFactory) factoryMethod.invoke(namedBuilder);
      } 
      catch (Exception e) {
        log.warn("Failed to create virtual thread factory, falling back to platform threads", e);
      }
    }
    
    // Fall back to platform threads
    return r -> {
      Thread t = new Thread(r);
      t.setName(namePrefix + "-" + t.getId());
      return t;
    };
  }
  
  /**
   * Checks if virtual threads are supported in the current Java runtime.
   * 
   * @return true if virtual threads are supported
   */
  public static boolean isVirtualThreadsSupported() {
    return VIRTUAL_THREADS_SUPPORTED;
  }
}