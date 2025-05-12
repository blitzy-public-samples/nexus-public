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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.RawGroupRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.RawHostedRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.RawProxyRepositoryConfig;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_RAW;

/**
 * Raw format repository test system implementation.
 * <p>
 * This class provides support for creating and managing Raw format repositories in tests.
 * It leverages Java 21 virtual threads for improved concurrency in repository operations,
 * allowing for more efficient testing of high-concurrency scenarios.
 * <p>
 * The implementation supports both synchronous and asynchronous repository creation methods
 * inherited from {@link SimpleFormatRepositoryTestSystemSupport}.
 *
 * @since 3.60
 */
@Named(FORMAT_RAW)
@Singleton
public class RawFormatRepositoryTestSystem
    extends SimpleFormatRepositoryTestSystemSupport
                <RawHostedRepositoryConfig,
                    RawProxyRepositoryConfig,
                    RawGroupRepositoryConfig>
    implements FormatRepositoryTestSystem
{
  /**
   * Creates a new instance with the specified repository manager.
   * <p>
   * This constructor configures the test system with the appropriate Raw format
   * repository configuration classes and enables virtual thread support for
   * asynchronous repository operations.
   *
   * @param repositoryManager the repository manager
   */
  @Inject
  public RawFormatRepositoryTestSystem(final RepositoryManager repositoryManager) {
    super(repositoryManager, RawHostedRepositoryConfig.class, RawProxyRepositoryConfig.class,
        RawGroupRepositoryConfig.class);
  }
}
