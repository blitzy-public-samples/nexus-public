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
package org.sonatype.nexus.testsuite.testsupport.system.repository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.GolangGroupRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.GolangHostedRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.GolangProxyRepositoryConfig;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_GOLANG;

/**
 * Golang format repository test system implementation that leverages Java 21 virtual threads
 * for concurrent repository operations.
 * <p>
 * This class provides support for creating and managing Golang repositories in test environments
 * with improved concurrency using Java 21 virtual threads for I/O-bound operations.
 * <p>
 * The implementation uses Java 21's virtual threads to improve performance and scalability
 * when provisioning and configuring repositories. Virtual threads are particularly well-suited
 * for I/O-bound operations like repository creation, which often involve database operations
 * and network calls.
 * <p>
 * This implementation is compatible with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * 
 * @since 3.60
 * @requires Java 21
 */
@Named(FORMAT_GOLANG)
@Singleton
public class GolangFormatRepositoryTestSystem
    extends SimpleFormatRepositoryTestSystemSupport
                <GolangHostedRepositoryConfig,
                    GolangProxyRepositoryConfig,
                    GolangGroupRepositoryConfig>
    implements FormatRepositoryTestSystem
{
  /**
   * Virtual thread executor service for handling concurrent repository operations.
   * Uses Java 21's virtual threads for improved scalability and reduced resource consumption.
   */
  private final ExecutorService virtualThreadExecutor;
  
  /**
   * Creates a new GolangFormatRepositoryTestSystem with the provided repository manager.
   * Initializes a virtual thread executor for concurrent repository operations.
   *
   * @param repositoryManager the repository manager to use for repository operations
   */
  @Inject
  public GolangFormatRepositoryTestSystem(final RepositoryManager repositoryManager) {
    super(repositoryManager, GolangHostedRepositoryConfig.class, GolangProxyRepositoryConfig.class,
        GolangGroupRepositoryConfig.class);
    this.virtualThreadExecutor = createVirtualThreadExecutor();
  }
  
  /**
   * Creates a virtual thread executor service using Java 21's virtual threads.
   * Virtual threads are lightweight threads that are well-suited for I/O-bound operations
   * like repository provisioning and configuration.
   * <p>
   * Virtual threads provide several advantages over platform threads:
   * <ul>
   *   <li>Lower memory footprint (measured in kilobytes vs megabytes)</li>
   *   <li>No need for complex thread pool sizing and tuning</li>
   *   <li>Better scalability for I/O-bound operations</li>
   *   <li>Simplified concurrency model</li>
   * </ul>
   *
   * @return an executor service that creates a new virtual thread for each task
   */
  private ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * {@inheritDoc}
   * 
   * This implementation uses Java 21 virtual threads for improved concurrency when tracking
   * repository creation events.
   */
  @Override
  public void installTracker(final java.util.function.Consumer<String> tracker) {
    // Use virtual threads for tracking repository creation events
    super.installTracker(name -> 
        virtualThreadExecutor.submit(() -> tracker.accept(name)));
  }
  
  /**
   * Shuts down the virtual thread executor service.
   * This method is automatically called when the repository test system is destroyed
   * to ensure proper resource cleanup.
   */
  @PreDestroy
  public void shutdown() {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdown();
    }
  }