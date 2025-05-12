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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.RGroupRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.RHostedRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.RProxyRepositoryConfig;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_R;

/**
 * R format repository test system implementation.
 * <p>
 * This implementation is compatible with Java 21 and leverages virtual threads
 * for concurrent repository operations to improve performance and scalability.
 * <p>
 * Virtual threads are lightweight threads that are managed by the JVM rather than
 * the operating system, allowing for higher concurrency with lower overhead.
 * This is particularly beneficial for I/O-bound operations like repository
 * provisioning and configuration.
 *
 * @since 3.60
 */
@Named(FORMAT_R)
@Singleton
public class RFormatRepositoryTestSystem
    extends SimpleFormatRepositoryTestSystemSupport
                <RHostedRepositoryConfig,
                    RProxyRepositoryConfig,
                    RGroupRepositoryConfig>
    implements FormatRepositoryTestSystem
{
  /**
   * Creates a new R format repository test system.
   *
   * @param repositoryManager the repository manager to use for creating repositories
   */
  @Inject
  public RFormatRepositoryTestSystem(final RepositoryManager repositoryManager) {
    super(repositoryManager, RHostedRepositoryConfig.class, RProxyRepositoryConfig.class, RGroupRepositoryConfig.class);
  }
  
  /**
   * Creates a hosted repository asynchronously using virtual threads.
   * <p>
   * This method leverages Java 21 virtual threads to improve performance for I/O-bound
   * repository creation operations.
   *
   * @param config the hosted repository configuration
   * @return a CompletableFuture that will complete with the created repository
   */
  public CompletableFuture<Repository> createHostedAsync(final RHostedRepositoryConfig config) {
    return CompletableFuture.supplyAsync(() -> createHosted(config), 
        Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Creates a proxy repository asynchronously using virtual threads.
   * <p>
   * This method leverages Java 21 virtual threads to improve performance for I/O-bound
   * repository creation operations.
   *
   * @param config the proxy repository configuration
   * @return a CompletableFuture that will complete with the created repository
   */
  public CompletableFuture<Repository> createProxyAsync(final RProxyRepositoryConfig config) {
    return CompletableFuture.supplyAsync(() -> createProxy(config), 
        Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Creates a group repository asynchronously using virtual threads.
   * <p>
   * This method leverages Java 21 virtual threads to improve performance for I/O-bound
   * repository creation operations.
   *
   * @param config the group repository configuration
   * @return a CompletableFuture that will complete with the created repository
   */
  public CompletableFuture<Repository> createGroupAsync(final RGroupRepositoryConfig config) {
    return CompletableFuture.supplyAsync(() -> createGroup(config), 
        Executors.newVirtualThreadPerTaskExecutor());
  }
}