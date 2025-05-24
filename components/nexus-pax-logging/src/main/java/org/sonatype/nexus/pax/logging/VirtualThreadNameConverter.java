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

/**
 * Converter that provides the thread name with special handling for virtual threads.
 * For platform threads, it returns an empty string. For virtual threads, it returns the thread name.
 * This allows for selective display of only virtual thread names in log patterns.
 *
 * @since 3.60.0
 */
public class VirtualThreadNameConverter
    extends ClassicConverter
{
  private static final String EMPTY = "";
  
  // Use reflection to check for virtual threads to maintain compatibility with Java 11
  private static final MethodHandle IS_VIRTUAL_METHOD;
  
  static {
    MethodHandle isVirtualMethod = null;
    try {
      // Try to get the isVirtual method from Thread class (Java 21+)
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      isVirtualMethod = lookup.findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
    }
    catch (NoSuchMethodException | IllegalAccessException e) {
      // Method doesn't exist in current Java version, which is fine
    }
    IS_VIRTUAL_METHOD = isVirtualMethod;
  }
  
  /**
   * Checks if the given thread is a virtual thread.
   *
   * @param thread the thread to check
   * @return true if the thread is a virtual thread, false otherwise
   */
  private static boolean isVirtualThread(Thread thread) {
    if (IS_VIRTUAL_METHOD == null) {
      return false; // Not running on Java 21+
    }
    
    try {
      return (boolean) IS_VIRTUAL_METHOD.invoke(thread);
    }
    catch (Throwable e) {
      return false; // Error invoking method, assume not virtual
    }
  }
  
  @Override
  public String convert(ILoggingEvent event) {
    Thread currentThread = Thread.currentThread();
    
    if (isVirtualThread(currentThread)) {
      return currentThread.getName();
    }
    else {
      return EMPTY;
    }
  }
}