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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper to create wrappers around components to ensure that the TCCL is properly configured.
 * 
 * <p>
 * This implementation is compatible with both platform threads and Java 21 Virtual Threads.
 * The Thread Context ClassLoader (TCCL) switching mechanism works transparently with both
 * thread types, ensuring consistent behavior across different thread implementations.
 * </p>
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
   * 
   * <p>
   * This method is compatible with Java 21 Virtual Threads. The dynamic proxy creation and invocation
   * logic ensures that the Thread Context ClassLoader is properly set regardless of whether the
   * calling thread is a platform thread or a virtual thread.
   * </p>
   * 
   * <p>
   * Note: When using with Virtual Threads, be aware that each virtual thread will have its own
   * context class loader state, which is appropriate for the one-task-per-thread model of Virtual Threads.
   * </p>
   */
  @SuppressWarnings("unchecked")
  public static <T> T create(final Class<T> type, final T target, final ClassLoader classLoader) {
    checkNotNull(type);
    checkNotNull(target);
    checkNotNull(classLoader);

    // Create an invocation handler that properly handles exceptions and preserves stack traces
    // This implementation works with both platform threads and virtual threads
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Use try-with-resources to ensure TCCL is properly restored even if an exception occurs
        // TcclBlock works with both platform and virtual threads as it uses Thread.currentThread()
        try (TcclBlock tccl = TcclBlock.begin(classLoader)) {
          try {
            return method.invoke(target, args);
          } 
          catch (InvocationTargetException e) {
            // Unwrap the original exception to preserve the stack trace
            throw e.getCause();
          }
        }
      }
    };

    // Create the proxy using the class loader of the interface type
    // This approach works with both platform and virtual threads
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
  }
}