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
package org.sonatype.nexus.common.cooperation2.datastore.internal;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.sonatype.nexus.common.cooperation2.Config;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.ScopedCooperation2Support;

/**
 * An implementation of {@link Cooperation2Factory} which uses local concurrency controls
 * optimized for Java 21 Virtual Threads.
 * 
 * This implementation leverages Virtual Threads to efficiently handle I/O-bound operations
 * with minimal resource overhead. Virtual Threads are particularly well-suited for cooperative
 * execution patterns where multiple threads may be waiting on I/O operations.
 *
 * @since 3.41
 */
public class LocalCooperation2
    extends ScopedCooperation2Support
{
  /**
   * Creates a new instance with the given scope and configuration.
   *
   * @param scope the cooperation scope identifier
   * @param config the cooperation configuration
   */
  public LocalCooperation2(final String scope, final Config config) {
    super(scope, config);
  }
  
  /**
   * Executes the given task using a Virtual Thread.
   * 
   * @param <T> the return type of the task
   * @param task the task to execute
   * @return the result of the task
   */
  public <T> T executeWithVirtualThread(final Callable<T> task) {
    try {
      // Create a holder for the result
      final Supplier<T>[] resultHolder = new Supplier[1];
      
      // Submit the task to be executed by a virtual thread
      submitVirtualThreadTask(() -> {
        try {
          T result = task.call();
          resultHolder[0] = () -> result;
        }
        catch (Exception e) {
          resultHolder[0] = () -> { throw new RuntimeException(e); };
        }
      });
      
      // Wait for the result
      while (resultHolder[0] == null) {
        Thread.yield();
      }
      
      // Return the result
      return resultHolder[0].get();
    }
    catch (RuntimeException e) {
      if (e.getCause() != null) {
        throw new RuntimeException(e.getCause());
      }
      throw e;
    }
  }
  
  /**
   * Releases resources when this component is being disposed.
   */
  public void dispose() {
    shutdown();
  }
}