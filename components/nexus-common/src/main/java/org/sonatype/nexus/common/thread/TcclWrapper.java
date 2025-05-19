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
package org.sonatype.nexus.common.thread;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper to create wrappers around components to ensure that the TCCL is properly configured.
 * Optimized for Java 21 Virtual Threads to minimize overhead during proxy invocation.
 *
 * @since 3.0
 */
public class TcclWrapper
{
  private TcclWrapper() {
    // empty
  }

  /**
   * Creates a dynamic-proxy for type, delegating to target and setting the TCCL to class-loader before invocation.
   * This implementation is optimized for Java 21 Virtual Threads to minimize overhead during proxy invocation.
   */
  @SuppressWarnings("unchecked")
  public static <T> T create(final Class<T> type, final T target, final ClassLoader classLoader) {
    checkNotNull(type);
    checkNotNull(target);
    checkNotNull(classLoader);

    // Create an optimized InvocationHandler for Virtual Threads
    InvocationHandler handler = (proxy, method, args) -> {
      // Get the current thread's context class loader
      ClassLoader currentLoader = Thread.currentThread().getContextClassLoader();
      
      // Only switch the class loader if it's different from the target one
      // This optimization reduces unnecessary context switching
      if (currentLoader != classLoader) {
        try {
          // Set the target class loader
          Thread.currentThread().setContextClassLoader(classLoader);
          
          // Invoke the method on the target
          return method.invoke(target, args);
        } finally {
          // Restore the original class loader
          Thread.currentThread().setContextClassLoader(currentLoader);
        }
      } else {
        // If the class loader is already correct, just invoke the method
        // This avoids unnecessary context switching overhead
        return method.invoke(target, args);
      }
    };

    // Create the proxy with the optimized handler
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
  }
}