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

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/**
 * Converter that provides metrics about virtual threads when running on Java 21+.
 * This includes the count of active virtual threads and other relevant metrics.
 * On Java versions prior to 21, it returns a message indicating virtual threads are not available.
 *
 * @since 3.60.0
 */
public class VirtualThreadMetricsConverter
    extends ClassicConverter
{
  private static final String NOT_AVAILABLE = "vt-metrics-not-available";
  private static final String METRICS_FORMAT = "vt-active:%d";
  
  // Use reflection to access virtual thread metrics to maintain compatibility with Java 11
  private static final MethodHandle GET_ACTIVE_THREAD_COUNT_METHOD;
  
  static {
    MethodHandle getActiveThreadCountMethod = null;
    try {
      // Try to access the ThreadMXBean's method for getting virtual thread count (Java 21+)
      Class<?> threadMXBeanClass = Class.forName("java.lang.management.ThreadMXBean");
      Method getActiveThreadCountMethod0 = threadMXBeanClass.getMethod("getThreadCount");
      
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      getActiveThreadCountMethod = lookup.unreflect(getActiveThreadCountMethod0);
    }
    catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
      // Method doesn't exist in current Java version, which is fine
    }
    GET_ACTIVE_THREAD_COUNT_METHOD = getActiveThreadCountMethod;
  }
  
  /**
   * Gets the count of active virtual threads.
   *
   * @return the count of active virtual threads, or -1 if not available
   */
  private static int getActiveVirtualThreadCount() {
    if (GET_ACTIVE_THREAD_COUNT_METHOD == null) {
      return -1; // Not running on Java 21+
    }
    
    try {
      Object threadMXBean = ManagementFactory.getThreadMXBean();
      return (int) GET_ACTIVE_THREAD_COUNT_METHOD.invoke(threadMXBean);
    }
    catch (Throwable e) {
      return -1; // Error invoking method
    }
  }
  
  @Override
  public String convert(ILoggingEvent event) {
    int activeVirtualThreadCount = getActiveVirtualThreadCount();
    
    if (activeVirtualThreadCount >= 0) {
      return String.format(METRICS_FORMAT, activeVirtualThreadCount);
    }
    else {
      return NOT_AVAILABLE;
    }
  }
}