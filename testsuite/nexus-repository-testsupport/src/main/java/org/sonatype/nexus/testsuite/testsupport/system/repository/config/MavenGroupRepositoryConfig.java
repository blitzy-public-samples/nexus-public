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
 * Configuration class for Maven group repositories in test support.
 * <p>
 * This class is compatible with Java 21 and supports the test infrastructure
 * for repository configuration in the Maven format.
 * 
 * @since 3.60
 */
public class MavenGroupRepositoryConfig
    extends GroupRepositoryConfigSupport<MavenGroupRepositoryConfig>
{
  private VersionPolicy versionPolicy = VersionPolicy.MIXED;

  private LayoutPolicy layoutPolicy = LayoutPolicy.STRICT;

  /**
   * Default constructor.
   */
  public MavenGroupRepositoryConfig() {
    this(null);
  }

  /**
   * Constructor with repository factory.
   *
   * @param repositoryFactory Function to create a repository from this configuration
   */
  public MavenGroupRepositoryConfig(final Function<MavenGroupRepositoryConfig, Repository> repositoryFactory) {
    super(repositoryFactory);
  }

  @Override
  public String getFormat() {
    return FORMAT_MAVEN;
  }

  /**
   * Sets the version policy for this Maven group repository.
   *
   * @param versionPolicy the version policy to set
   * @return this configuration instance for method chaining
   */
  public MavenGroupRepositoryConfig withVersionPolicy(final VersionPolicy versionPolicy) {
    this.versionPolicy = versionPolicy;
    return this;
  }

  /**
   * Gets the version policy for this Maven group repository.
   *
   * @return the configured version policy
   */
  public VersionPolicy getVersionPolicy() {
    return versionPolicy;
  }

  /**
   * Sets the layout policy for this Maven group repository.
   *
   * @param layoutPolicy the layout policy to set
   * @return this configuration instance for method chaining
   */
  public MavenGroupRepositoryConfig withLayoutPolicy(final LayoutPolicy layoutPolicy) {
    this.layoutPolicy = layoutPolicy;
    return this;
  }

  /**
   * Gets the layout policy for this Maven group repository.
   *
   * @return the configured layout policy
   */
  public LayoutPolicy getLayoutPolicy() {
    return layoutPolicy;
  }
}