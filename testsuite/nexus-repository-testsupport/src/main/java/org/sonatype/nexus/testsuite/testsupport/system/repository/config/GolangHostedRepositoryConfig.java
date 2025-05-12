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

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_GOLANG;

/**
 * Configuration for a hosted Golang repository in tests.
 * <p>
 * This class extends {@link HostedRepositoryConfigSupport} to provide a fluent builder pattern
 * for configuring Golang hosted repository instances in test environments.
 * <p>
 * Hosted Golang repositories store Go modules locally rather than proxying from a remote source.
 * <p>
 * Compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * When running on Java 21, repository operations may benefit from Virtual Threads for improved concurrency.
 */
public class GolangHostedRepositoryConfig
    extends HostedRepositoryConfigSupport<GolangHostedRepositoryConfig>
{
  /**
   * Constructs a new GolangHostedRepositoryConfig with the specified factory function.
   * 
   * @param factory The function that creates a Repository instance from this configuration
   */
  public GolangHostedRepositoryConfig(final Function<GolangHostedRepositoryConfig, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the format name for this repository type.
   *
   * @return The Golang format name
   */
  @Override
  public String getFormat() {
    return FORMAT_GOLANG;
  }
}