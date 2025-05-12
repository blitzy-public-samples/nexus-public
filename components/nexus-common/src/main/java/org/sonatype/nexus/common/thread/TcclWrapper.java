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
 * <p>This implementation is compatible with both platform threads and Java 21 Virtual Threads.
 * When used with Virtual Threads, it ensures proper thread context class loader management without
 * causing thread pinning or memory leaks.</p>
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
   * <p>This method creates a proxy that works efficiently with both platform threads and Java 21 Virtual Threads.
   * The implementation uses {@link TcclBlock} to ensure proper thread context class loader management
   * and cleanup, even in the presence of exceptions.</p>
   * 
   * <p>When used with Virtual Threads, this approach avoids thread pinning by ensuring that the
   * InvocationHandler doesn't hold any thread-local state that would prevent proper unmounting
   * of the virtual thread from its carrier thread.</p>
   * 
   * @param type the interface type to proxy
   * @param target the target object implementing the interface
   * @param classLoader the class loader to set as the thread context class loader during method invocation
   * @return a proxy instance of the specified type
   * @throws NullPointerException if any parameter is null
   */
  @SuppressWarnings("unchecked")
  public static <T> T create(final Class<T> type, final T target, final ClassLoader classLoader) {
    checkNotNull(type, "Type cannot be null");
    checkNotNull(target, "Target cannot be null");
    checkNotNull(classLoader, "ClassLoader cannot be null");

    // Create an InvocationHandler that properly handles exceptions and works with Virtual Threads
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Use try-with-resources to ensure proper TCCL management with both platform and virtual threads
        try (TcclBlock tccl = TcclBlock.begin(classLoader)) {
          try {
            return method.invoke(target, args);
          }
          catch (InvocationTargetException e) {
            // Unwrap the target exception to preserve the original stack trace
            throw e.getTargetException();
          }
        }
      }
    };

    // Create the proxy with the interface class loader to ensure proper class visibility
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
  }
}
