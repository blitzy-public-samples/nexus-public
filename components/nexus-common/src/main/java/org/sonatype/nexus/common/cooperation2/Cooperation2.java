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
package org.sonatype.nexus.common.cooperation2;

import java.io.IOException;
import java.util.Map;

/**
 * Cooperation interface for coordinating work between multiple threads or nodes to prevent duplicate work.
 * <p>
 * This interface supports Java 21 Virtual Threads for I/O-bound operations, allowing for improved scalability
 * and resource utilization. Operations that involve network calls, file system access, or database interactions
 * are particularly well-suited for Virtual Thread execution.
 * <p>
 * When using Virtual Threads with this interface, context propagation is handled automatically to ensure
 * that thread-local variables and other context information are properly maintained when a Virtual Thread
 * is suspended and later resumed.
 * 
 * @since 3.41
 */
public interface Cooperation2
{
  /**
   * Sets the function that is used to perform the work. Depending on the thread, or the node chosen to perform the
   * co-operation there is no guarantee that the function will be called and the {@link Cooperation2} built may return
   * the result from another thread.
   * <p>
   * The provided work function may be executed using a Virtual Thread if the operation is I/O-bound and
   * Virtual Thread execution is enabled in the builder. This is particularly beneficial for operations that
   * involve network calls, file system access, or database interactions.
   *
   * @param workFunction a function which can be used to perform the operation
   * @param <RET> the type of the return value
   * @return the resulting builder
   */
  <RET> Builder<RET> on(IOCall<RET> workFunction);
  
  /**
   * Sets the function that is used to perform the work, with an explicit indication that the operation
   * can benefit from Virtual Thread execution. This method should be used for I/O-bound operations
   * that may block for extended periods, such as network calls, file system access, or database interactions.
   * <p>
   * When an operation is marked as suitable for Virtual Thread execution, the implementation may choose to
   * execute it using a Virtual Thread, which allows for better resource utilization when the operation blocks.
   * <p>
   * Note that the implementation may still choose not to use Virtual Threads based on runtime conditions or
   * configuration settings.
   *
   * @param workFunction a function which can be used to perform the operation
   * @param <RET> the type of the return value
   * @return the resulting builder with Virtual Thread execution enabled by default
   * @since Java 21
   */
  <RET> Builder<RET> onIOOperation(IOCall<RET> workFunction);

  /**
   * @return number of threads cooperating per request-key.
   */
  Map<String, Integer> getThreadCountPerKey();

  interface Builder<RET>
  {
    /**
     * Set an {@link IOCheck} that can be used to verify that the work of this cooperation has already been performed.
     * If this check is not provided it is assumed the work is not complete.
     *
     * This function is not guaranteed to be called (if another thread, or node is the lead).
     *
     * Exceptions thrown by this supplier will terminate the co-operation.
     *
     * @param checkFunction a supplier which can be used to determine whether the work required for a co-operation key
     *          has been completed.
     * @return the resulting builder
     */
    Builder<RET> checkFunction(IOCheck<RET> checkFunction);

    /**
     * The co-operation may (depending on implementation) perform the work if concurrency controls timeout.
     *
     * @param performWorkOnFail whether to perform work if concurrency controls timeout
     * @return the resulting builder
     */
    Builder<RET> performWorkOnFail(final boolean performWorkOnFail);
    
    /**
     * Specifies whether this operation should use Virtual Threads for execution. This is particularly
     * beneficial for I/O-bound operations that may block for extended periods, such as network calls,
     * file system access, or database interactions.
     * <p>
     * When Virtual Thread execution is enabled, the implementation will attempt to execute the operation
     * using a Virtual Thread, which allows for better resource utilization when the operation blocks.
     * <p>
     * Note that the implementation may still choose not to use Virtual Threads based on runtime conditions
     * or if the JVM does not support Virtual Threads.
     * <p>
     * Best practices for Virtual Thread usage:
     * <ul>
     *   <li>Use Virtual Threads for I/O-bound operations that may block</li>
     *   <li>Avoid using synchronized blocks or methods within Virtual Thread operations</li>
     *   <li>Be aware that thread-local variables are maintained across Virtual Thread suspensions</li>
     *   <li>Do not use Virtual Threads for CPU-intensive operations</li>
     * </ul>
     *
     * @param useVirtualThreads whether to use Virtual Threads for this operation
     * @return the resulting builder
     * @since Java 21
     */
    Builder<RET> useVirtualThreads(final boolean useVirtualThreads);

    /**
     * Perform the co-operation. (Note implementations may not execute the work asynchronously)
     * <p>
     * If Virtual Thread execution is enabled for this operation, the implementation will attempt to
     * execute the operation using a Virtual Thread, which allows for better resource utilization when
     * the operation blocks. Context propagation is handled automatically to ensure that thread-local
     * variables and other context information are properly maintained when a Virtual Thread is suspended
     * and later resumed.
     *
     * @param action the action being performed
     * @param scopes optional scopes to further qualify the action
     * @return the result of the cooperation
     * @throws IOException if an I/O error occurs during the cooperation
     */
    RET cooperate(String action, String... scopes) throws IOException;
  }
}