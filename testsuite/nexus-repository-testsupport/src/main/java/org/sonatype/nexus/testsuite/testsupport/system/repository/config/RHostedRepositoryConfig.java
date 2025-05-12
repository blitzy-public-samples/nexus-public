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

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_R;

/**
 * Configuration for R format hosted repositories in test environments.
 * <p>
 * This class provides a fluent API for configuring R format hosted repositories
 * for testing purposes. It is compatible with Java 21 and supports testing with
 * JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * <p>
 * When used with {@code RFormatRepositoryTestSystem}, repository operations can
 * leverage Java 21 virtual threads for improved performance, particularly for
 * I/O-bound operations.
 *
 * @since 3.60
 */
public class RHostedRepositoryConfig
    extends HostedRepositoryConfigSupport<RHostedRepositoryConfig>
{
  /**
   * Constructs a new R hosted repository configuration with the specified factory function.
   * <p>
   * The factory function is used to create a Repository instance from this configuration.
   * When running on Java 21, the factory may leverage virtual threads for I/O operations.
   *
   * @param factory The function that creates a Repository instance from this configuration
   */
  public RHostedRepositoryConfig(final Function<RHostedRepositoryConfig, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the format of this repository configuration.
   *
   * @return The R format identifier
   */
  @Override
  public String getFormat() {
    return FORMAT_R;
  }
}
