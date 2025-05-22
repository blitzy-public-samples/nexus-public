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
package org.sonatype.nexus.pax.logging;

import org.sonatype.nexus.logging.task.ProgressTaskLogger;
import org.sonatype.nexus.common.thread.VirtualThreadMetricsCollector;
import org.sonatype.nexus.pax.logging.mdc.VirtualThreadMDCAdapter;

import org.osgi.framework.BundleContext;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.lang.reflect.Method;

/**
 * Adds logging statement immediately after activating pax-logging.
 * Initializes Java 21 features like Virtual Threads and String Templates.
 *
 * @since 3.13
 */
public class NexusLogActivator
    extends org.ops4j.pax.logging.logback.internal.Activator
{
  static NexusLogActivator INSTANCE;

  private BundleContext context;
  private VirtualThreadMetricsCollector virtualThreadMetricsCollector;

  public BundleContext getContext() {
    return context;
  }

  @Override
  public void start(final BundleContext bundleContext) throws Exception {
    super.start(bundleContext);
    this.context = bundleContext;
    
    // Initialize logging
    LoggerFactory.getLogger(NexusLogActivator.class).info("start");
    
    // Detect Java 21 and Virtual Thread capability
    boolean isJava21OrHigher = detectJava21();
    boolean supportsVirtualThreads = isJava21OrHigher && detectVirtualThreadSupport();
    
    LoggerFactory.getLogger(NexusLogActivator.class).info(
        "Java version: {}, Virtual Threads supported: {}", 
        System.getProperty("java.version"), 
        supportsVirtualThreads);
    
    // Initialize Virtual Thread metrics collector if supported
    if (supportsVirtualThreads) {
      initializeVirtualThreadSupport();
    }
    
    // Register String Template processor for structured logging if supported
    if (isJava21OrHigher) {
      registerStringTemplateProcessor();
    }
    
    setInstance(this);
  }

  @Override
  public void stop(final BundleContext bundleContext) throws Exception {
    // Shutdown Virtual Thread metrics collector if initialized
    if (virtualThreadMetricsCollector != null) {
      virtualThreadMetricsCollector.shutdown();
      virtualThreadMetricsCollector = null;
    }
    
    ProgressTaskLogger.shutdown();
    clearInstance();
    super.stop(bundleContext);
  }

  /**
   * Detects if the current JVM is Java 21 or higher.
   */
  private boolean detectJava21() {
    try {
      String javaVersion = System.getProperty("java.version");
      if (javaVersion != null) {
        // Extract major version number
        int majorVersion;
        if (javaVersion.startsWith("1.")) {
          // Old version format: 1.8.x
          majorVersion = Integer.parseInt(javaVersion.substring(2, 3));
        } else {
          // New version format: 11.x, 17.x, 21.x
          int dotIndex = javaVersion.indexOf('.');
          if (dotIndex != -1) {
            majorVersion = Integer.parseInt(javaVersion.substring(0, dotIndex));
          } else {
            majorVersion = Integer.parseInt(javaVersion);
          }
        }
        return majorVersion >= 21;
      }
    } catch (Exception e) {
      LoggerFactory.getLogger(NexusLogActivator.class).warn("Error detecting Java version", e);
    }
    return false;
  }

  /**
   * Detects if the current JVM supports Virtual Threads.
   */
  private boolean detectVirtualThreadSupport() {
    try {
      // Try to access the Thread.ofVirtual() method which is available in Java 21
      Method ofVirtualMethod = Thread.class.getMethod("ofVirtual");
      return ofVirtualMethod != null;
    } catch (NoSuchMethodException e) {
      // Virtual threads not supported
      return false;
    } catch (Exception e) {
      LoggerFactory.getLogger(NexusLogActivator.class).warn("Error detecting Virtual Thread support", e);
      return false;
    }
  }

  /**
   * Initializes Virtual Thread support by setting up the metrics collector
   * and registering the Virtual Thread-aware MDC adapter.
   */
  private void initializeVirtualThreadSupport() {
    try {
      // Initialize the Virtual Thread metrics collector
      virtualThreadMetricsCollector = new VirtualThreadMetricsCollector();
      virtualThreadMetricsCollector.initialize();
      LoggerFactory.getLogger(NexusLogActivator.class).info("Virtual Thread metrics collector initialized");
      
      // Register Virtual Thread-aware MDC adapter with SLF4J
      registerVirtualThreadMDCAdapter();
    } catch (Exception e) {
      LoggerFactory.getLogger(NexusLogActivator.class).error("Failed to initialize Virtual Thread support", e);
    }
  }

  /**
   * Registers a Virtual Thread-aware MDC adapter with SLF4J.
   */
  private void registerVirtualThreadMDCAdapter() {
    try {
      // Create a Virtual Thread-aware MDC adapter
      MDCAdapter vtMDCAdapter = new VirtualThreadMDCAdapter();
      
      // Use reflection to set the MDC adapter in SLF4J
      Class<?> mdcClass = MDC.class;
      Method method = mdcClass.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
      method.setAccessible(true);
      method.invoke(null, vtMDCAdapter);
      
      LoggerFactory.getLogger(NexusLogActivator.class).info("Virtual Thread-aware MDC adapter registered");
    } catch (Exception e) {
      LoggerFactory.getLogger(NexusLogActivator.class).error("Failed to register Virtual Thread MDC adapter", e);
    }
  }

  /**
   * Registers String Template processor for structured logging.
   */
  private void registerStringTemplateProcessor() {
    try {
      // Use reflection to avoid direct dependencies on preview features
      Class<?> stringTemplateClass = Class.forName("java.lang.StringTemplate");
      Class<?> processorClass = Class.forName("java.lang.StringTemplate$Processor");
      
      // Register a logging-specific String Template processor if needed
      // This is a placeholder for actual implementation which would depend on the logging framework
      LoggerFactory.getLogger(NexusLogActivator.class).info("String Template processor support detected");
    } catch (ClassNotFoundException e) {
      // String Templates not available
      LoggerFactory.getLogger(NexusLogActivator.class).debug("String Template processor not available", e);
    } catch (Exception e) {
      LoggerFactory.getLogger(NexusLogActivator.class).warn("Error registering String Template processor", e);
    }
  }

  private static void setInstance(NexusLogActivator instance) {
    INSTANCE = instance;
  }

  private static void clearInstance() {
    INSTANCE = null;
  }
}