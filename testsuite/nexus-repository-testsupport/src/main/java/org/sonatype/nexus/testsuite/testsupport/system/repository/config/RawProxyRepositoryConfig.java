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

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_RAW;

/**
 * Configuration class for Raw proxy repositories in test environments.
 * <p>
 * This class provides a specialized configuration for Raw format proxy repositories,
 * extending the common proxy repository configuration support. It is designed to work
 * with the repository test system for integration testing.
 * <p>
 * Compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * When used with Java 21, repository operations may benefit from Virtual Threads for improved
 * I/O concurrency in test environments.
 */
public class RawProxyRepositoryConfig
    extends ProxyRepositoryConfigSupport<RawProxyRepositoryConfig>
{
  /**
   * Constructs a new Raw proxy repository configuration with the specified factory function.
   * 
   * @param factory The function that creates a Repository instance from this configuration
   */
  public RawProxyRepositoryConfig(final Function<RawProxyRepositoryConfig, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the format identifier for this repository configuration.
   * 
   * @return The Raw format identifier
   */
  @Override
  public String getFormat() {
    return FORMAT_RAW;
  }
}
