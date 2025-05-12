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
package org.sonatype.nexus.testsuite.testsupport.system.repository.config;

import java.util.function.Function;

import org.sonatype.nexus.repository.Repository;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_CONDA;

/**
 * Configuration class for Conda proxy repositories in test environments.
 * <p>
 * This class extends {@link ProxyRepositoryConfigSupport} to provide Conda-specific
 * repository configuration for integration tests. It is compatible with Java 21
 * and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * <p>
 * When used with Java 21, this configuration supports proxy repositories that leverage
 * Virtual Threads for improved I/O operations performance, particularly for remote
 * connections to Conda repositories and content transfers.
 *
 * @since 3.0
 */
public class CondaProxyRepositoryConfig
    extends ProxyRepositoryConfigSupport<CondaProxyRepositoryConfig>
{
  /**
   * Constructs a new Conda proxy repository configuration with the specified factory function.
   * <p>
   * The factory function is used to create a repository instance from this configuration.
   * When running on Java 21, the created repository can leverage Virtual Threads for
   * improved performance in I/O-bound operations.
   *
   * @param factory the function that creates a Repository instance from this configuration
   */
  public CondaProxyRepositoryConfig(final Function<CondaProxyRepositoryConfig, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the format identifier for Conda repositories.
   *
   * @return the Conda format identifier
   */
  @Override
  public String getFormat() {
    return FORMAT_CONDA;
  }
}