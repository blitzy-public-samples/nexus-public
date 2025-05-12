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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.CondaProxyRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.GroupRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.HostedRepositoryConfig;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_CONDA;

/**
 * Conda format-specific repository test system.
 * 
 * @since 3.0
 * 
 * @Java21 This implementation leverages Virtual Threads for repository provisioning operations
 * to improve concurrency and reduce resource usage during test execution. The underlying
 * FormatRepositoryTestSystemSupport class uses Java 21's Virtual Threads for asynchronous
 * repository creation, which significantly improves performance when creating multiple
 * repositories concurrently during tests.
 */
@Named(FORMAT_CONDA)
@Singleton
public class CondaFormatRepositoryTestSystem
    extends FormatRepositoryTestSystemSupport
                <HostedRepositoryConfig<?>,
                    CondaProxyRepositoryConfig,
                    GroupRepositoryConfig<?>>
    implements FormatRepositoryTestSystem
{
  @Inject
  public CondaFormatRepositoryTestSystem(final RepositoryManager repositoryManager) {
    super(repositoryManager);
  }

  /**
   * Creates a Conda proxy repository with the given configuration.
   * 
   * @param config the repository configuration
   * @return the created repository
   * @throws Exception if repository creation fails
   * 
   * @Java21 This method leverages Virtual Threads for repository creation through the parent class
   * implementation, providing improved concurrency for I/O-bound operations.
   */
  public Repository createProxy(final CondaProxyRepositoryConfig config) throws Exception {
    return doCreate(createProxyConfiguration(config));
  }
}
