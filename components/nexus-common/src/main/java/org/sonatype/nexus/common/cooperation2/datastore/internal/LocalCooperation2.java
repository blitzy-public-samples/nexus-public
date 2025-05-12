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

import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import org.sonatype.nexus.common.cooperation2.Config;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.ScopedCooperation2Support;

/**
 * An implementation of {@link Cooperation2Factory} which uses local concurrency controls.
 * 
 * This implementation is optimized for Java 21 Virtual Threads, providing efficient
 * thread management and concurrency control for I/O-bound operations. It leverages
 * the lightweight nature of virtual threads to handle a large number of concurrent
 * operations with minimal resource overhead.
 *
 * @since 3.41
 */
public class LocalCooperation2
    extends ScopedCooperation2Support
    implements Closeable
{
  /**
   * Virtual thread executor for handling I/O-bound operations.
   * Java 21 virtual threads are lightweight and can be created in much larger numbers
   * than platform threads. They automatically yield during blocking I/O operations,
   * allowing the carrier thread to do other work.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  
  /**
   * Creates a new instance with the specified scope and configuration.
   * 
   * @param scope the cooperation scope identifier
   * @param config the cooperation configuration
   */
  public LocalCooperation2(final String scope, final Config config) {
    super(scope, config);
    log.debug("Initialized LocalCooperation2 with Java 21 Virtual Thread support for scope: {}", scope);
  }
  
  /**
   * Returns the virtual thread executor for this cooperation instance.
   * This executor is optimized for I/O-bound operations using Java 21 virtual threads.
   * 
   * @return the virtual thread executor
   */
  public ExecutorService getVirtualThreadExecutor() {
    return virtualThreadExecutor;
  }
  
  /**
   * Closes this resource, shutting down the virtual thread executor.
   * This method should be called when the cooperation instance is no longer needed,
   * typically in a try-with-resources block or explicitly in application shutdown.
   */
  @Override
  public void close() {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      log.debug("Shutting down virtual thread executor for scope: {}", scope);
      virtualThreadExecutor.shutdown();
    }
  }
}