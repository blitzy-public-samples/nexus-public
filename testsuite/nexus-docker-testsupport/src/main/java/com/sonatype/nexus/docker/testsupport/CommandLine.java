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
package com.sonatype.nexus.docker.testsupport;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for Command Line IT Support classes to conform to.
 * <p>
 * This interface is compatible with Java 21 and supports execution in both platform threads and virtual threads.
 * Implementations should ensure thread safety and proper handling of I/O operations to avoid virtual thread pinning.
 * </p>
 * <p>
 * Virtual threads are lightweight threads that reduce the effort of writing, maintaining, and debugging high-throughput
 * concurrent applications. When implementing this interface for Java 21 environments, consider leveraging virtual
 * threads for I/O-bound operations to improve scalability and resource utilization.
 * </p>
 * 
 * @since 3.0
 */
public interface CommandLine
{
  /**
   * Execute commands on command line.
   * <p>
   * This method may perform blocking I/O operations. When called from a virtual thread in Java 21,
   * the virtual thread will be automatically suspended during I/O operations, allowing other virtual
   * threads to execute on the same carrier thread.
   * </p>
   *
   * @param commands to execute.
   * @return {@link Optional} of results from a "docker exec" command.
   */
  Optional<List<String>> exec(String commands);

  /**
   * Execute commands on command line asynchronously.
   * <p>
   * This method provides an asynchronous alternative to {@link #exec(String)} that returns a
   * {@link CompletableFuture} for better integration with asynchronous workflows and virtual threads.
   * </p>
   *
   * @param commands to execute.
   * @return {@link CompletableFuture} containing an {@link Optional} of results from a "docker exec" command.
   * @since Java 21
   */
  default CompletableFuture<Optional<List<String>>> execAsync(String commands) {
    return CompletableFuture.supplyAsync(() -> exec(commands));
  }

  /**
   * Download a file from the container.
   * <p>
   * This method performs blocking I/O operations. When called from a virtual thread in Java 21,
   * the virtual thread will be automatically suspended during I/O operations, allowing other virtual
   * threads to execute on the same carrier thread.
   * </p>
   *
   * @param fromContainerPath the file to download in the container.
   * @param toLocal {@link File} host path to download to
   */
  void download(String fromContainerPath, File toLocal);

  /**
   * Download a file from the container asynchronously.
   * <p>
   * This method provides an asynchronous alternative to {@link #download(String, File)} that returns a
   * {@link CompletableFuture} for better integration with asynchronous workflows and virtual threads.
   * </p>
   *
   * @param fromContainerPath the file to download in the container.
   * @param toLocal {@link File} host path to download to
   * @return {@link CompletableFuture} that completes when the download is finished
   * @since Java 21
   */
  default CompletableFuture<Void> downloadAsync(String fromContainerPath, File toLocal) {
    return CompletableFuture.runAsync(() -> download(fromContainerPath, toLocal));
  }

  /**
   * Initialization method that should be called right after creation of a Command Line Client but before actual
   * commands will be allowed to execute, this will allow implementers to do any pre-conditional work.
   * <p>
   * Implementations should ensure this method is thread-safe and can be called from both platform and virtual threads.
   * </p>
   */
  void init();

  /**
   * Called to exit the command line. Similar as exiting a terminal.
   * <p>
   * Implementations should ensure this method is thread-safe and can be called from both platform and virtual threads.
   * Any resources held by the implementation should be properly released to avoid resource leaks.
   * </p>
   */
  void exit();

  /**
   * Retrieves the TCP Port of the host.
   * <p>
   * This method may perform network operations. When called from a virtual thread in Java 21,
   * the virtual thread will be automatically suspended during network operations, allowing other virtual
   * threads to execute on the same carrier thread.
   * </p>
   *
   * @param containerPort the container that is expected to be associated with the host port.
   * @return representing port.
   */
  Integer getHostTcpPort(String containerPort);
  
  /**
   * Checks if the implementation supports execution in virtual threads.
   * <p>
   * Some implementations may have dependencies or use native methods that cause virtual thread pinning,
   * which can reduce the benefits of virtual threads. This method allows clients to check if the
   * implementation is optimized for virtual threads.
   * </p>
   *
   * @return {@code true} if the implementation is optimized for virtual threads, {@code false} otherwise.
   * @since Java 21
   */
  default boolean supportsVirtualThreads() {
    return true;
  }
}