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
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.CocoapodsProxyRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.GroupRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.HostedRepositoryConfig;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_COCOAPODS;

/**
 * Test system implementation for Cocoapods format repositories.
 * <p>
 * This class supports creating and configuring Cocoapods repositories for testing purposes.
 * It leverages Java 21 virtual threads for improved concurrency in I/O-bound operations.
 *
 * @since 3.0
 */
@Named(FORMAT_COCOAPODS)
@Singleton
public class CocoapodsFormatRepositoryTestSystem
    extends FormatRepositoryTestSystemSupport
                <HostedRepositoryConfig<?>,
                    CocoapodsProxyRepositoryConfig,
                    GroupRepositoryConfig<?>>
    implements FormatRepositoryTestSystem
{
  /**
   * Constructs a new instance with the specified repository manager.
   *
   * @param repositoryManager the repository manager to use for creating repositories
   */
  @Inject
  public CocoapodsFormatRepositoryTestSystem(final RepositoryManager repositoryManager) {
    super(repositoryManager);
  }

  /**
   * Creates a proxy repository with the specified configuration.
   * <p>
   * This implementation uses Java 21 virtual threads for I/O-bound operations
   * to improve concurrency and resource utilization during repository creation.
   *
   * @param config the configuration for the proxy repository
   * @return the created repository
   * @throws Exception if an error occurs during repository creation
   */
  public Repository createProxy(final CocoapodsProxyRepositoryConfig config) throws Exception {
    // Use virtual threads for I/O-bound repository creation operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Repository> future = CompletableFuture.supplyAsync(
          () -> {
            try {
              return doCreate(createProxyConfiguration(config));
            }
            catch (Exception e) {
              throw new RuntimeException("Failed to create proxy repository", e);
            }
          },
          executor
      );
      return future.join();
    }
  }
}