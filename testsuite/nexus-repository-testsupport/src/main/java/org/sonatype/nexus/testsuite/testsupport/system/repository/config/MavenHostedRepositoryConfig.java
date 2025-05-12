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
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.VersionPolicy;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_MAVEN;

/**
 * Configuration class for Maven hosted repositories in test environments.
 * <p>
 * This class is compatible with Java 21 and supports the latest test frameworks
 * (JUnit Jupiter 5.10.1 and Mockito 5.8.0) when used in test scenarios.
 *
 * @since 3.0
 */
public class MavenHostedRepositoryConfig
    extends HostedRepositoryConfigSupport<MavenHostedRepositoryConfig>
{
  private VersionPolicy versionPolicy = VersionPolicy.MIXED;

  private LayoutPolicy layoutPolicy = LayoutPolicy.STRICT;

  /**
   * Constructs a new Maven hosted repository configuration with the specified factory.
   *
   * @param factory Function that creates a Repository from this configuration
   */
  public MavenHostedRepositoryConfig(final Function<MavenHostedRepositoryConfig, Repository> factory) {
    super(factory);
  }

  @Override
  public String getFormat() {
    return FORMAT_MAVEN;
  }

  /**
   * Sets the version policy for this Maven repository.
   *
   * @param versionPolicy the version policy to use
   * @return this configuration instance for method chaining
   */
  public MavenHostedRepositoryConfig withVersionPolicy(final VersionPolicy versionPolicy) {
    this.versionPolicy = versionPolicy;
    return this;
  }

  /**
   * Gets the configured version policy.
   *
   * @return the version policy
   */
  public VersionPolicy getVersionPolicy() {
    return versionPolicy;
  }

  /**
   * Sets the layout policy for this Maven repository.
   *
   * @param layoutPolicy the layout policy to use
   * @return this configuration instance for method chaining
   */
  public MavenHostedRepositoryConfig withLayoutPolicy(final LayoutPolicy layoutPolicy) {
    this.layoutPolicy = layoutPolicy;
    return this;
  }

  /**
   * Gets the configured layout policy.
   *
   * @return the layout policy
   */
  public LayoutPolicy getLayoutPolicy() {
    return layoutPolicy;
  }
}