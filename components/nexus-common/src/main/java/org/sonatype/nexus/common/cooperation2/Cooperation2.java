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
 * Cooperation interface for coordinating work between threads and nodes.
 * 
 * @since 3.41
 */
public interface Cooperation2
{
  /**
   * Sets the function that is used to perform the work. Depending on the thread, or the node chosen to perform the
   * co-operation there is no guarantee that the function will be called and the {@link Cooperation2} built may return
   * the result from another thread.
   *
   * @param workFunction a function which can be used to perform the operation
   * @param <RET> the type of the return value
   * @return the resulting builder
   */
  <RET> Builder<RET> on(IOCall<RET> workFunction);

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
     * Indicates that this operation is I/O-bound and would benefit from execution in a Virtual Thread.
     * When this flag is set, the implementation may choose to execute the work in a Virtual Thread
     * to improve throughput, especially for operations that spend significant time waiting for I/O.
     * 
     * <p>Virtual Threads are particularly beneficial for operations that:</p>
     * <ul>
     *   <li>Perform network I/O (HTTP requests, database queries, etc.)</li>
     *   <li>Wait for file system operations</li>
     *   <li>Block on external resources</li>
     * </ul>
     * 
     * <p><strong>Note:</strong> Operations that use {@code synchronized} blocks or methods may experience
     * "pinning" which prevents the Virtual Thread from being unmounted during blocking operations.
     * This can reduce the benefits of Virtual Threads.</p>
     * 
     * @param useVirtualThread whether to use a Virtual Thread for executing this operation
     * @return the resulting builder
     * @since 3.60
     */
    Builder<RET> useVirtualThread(final boolean useVirtualThread);
    
    /**
     * Specifies how to handle context propagation when using Virtual Threads.
     * This is important for ensuring that thread-local values, transaction contexts,
     * security contexts, and other thread-bound state are properly propagated to
     * Virtual Threads when they are used.
     * 
     * <p>When context propagation is enabled, the implementation will ensure that relevant
     * thread-local state is captured from the parent thread and properly restored in the
     * Virtual Thread before executing the work function.</p>
     * 
     * <p><strong>Note:</strong> Context propagation may have a small performance overhead,
     * but is essential for correct operation of code that relies on ThreadLocal values.</p>
     * 
     * @param propagateContext whether to propagate thread-local context to Virtual Threads
     * @return the resulting builder
     * @since 3.60
     */
    Builder<RET> propagateContext(final boolean propagateContext);

    /**
     * Perform the co-operation. (Note implementations may not execute the work asynchronously)
     * 
     * <p>When Virtual Threads are enabled via {@link #useVirtualThread(boolean)}, the implementation
     * may choose to execute the work in a Virtual Thread for improved throughput, especially for
     * I/O-bound operations. Context propagation behavior is controlled by the {@link #propagateContext(boolean)}
     * setting.</p>
     * 
     * @param action the action being performed
     * @param scopes the scopes involved in the action
     * @return the result of the cooperation
     * @throws IOException if an I/O error occurs during the operation
     */
    RET cooperate(String action, String... scopes) throws IOException;
  }
}