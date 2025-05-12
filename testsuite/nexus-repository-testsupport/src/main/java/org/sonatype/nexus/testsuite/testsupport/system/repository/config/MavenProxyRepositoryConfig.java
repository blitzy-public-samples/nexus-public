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
 * Configuration class for Maven proxy repositories in tests.
 * 
 * <p>This class is compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.</p>
 * 
 * <p>When used with Java 21, this configuration can be used to test Maven proxy repositories that leverage Virtual Threads
 * for improved I/O operations performance, particularly for remote connections and Maven artifact transfers.</p>
 * 
 * <p>The configuration supports both version policy (RELEASE, SNAPSHOT, MIXED) and layout policy (STRICT, PERMISSIVE)
 * settings specific to Maven repositories.</p>
 */
public class MavenProxyRepositoryConfig
    extends ProxyRepositoryConfigSupport<MavenProxyRepositoryConfig>
{
  /** Version policy for the Maven repository (RELEASE, SNAPSHOT, or MIXED) */
  private VersionPolicy versionPolicy = VersionPolicy.MIXED;

  /** Layout policy for the Maven repository (STRICT or PERMISSIVE) */
  private LayoutPolicy layoutPolicy = LayoutPolicy.STRICT;

  /**
   * Constructor.
   *
   * @param repositoryFactory Function to create a repository from this configuration
   */
  public MavenProxyRepositoryConfig(final Function<MavenProxyRepositoryConfig, Repository> repositoryFactory) {
    super(repositoryFactory);
  }

  /**
   * Gets the format for this repository configuration.
   * 
   * @return The format name ("maven")
   */
  @Override
  public String getFormat() {
    return FORMAT_MAVEN;
  }

  /**
   * Sets the version policy for this Maven proxy repository.
   * 
   * <p>The version policy determines which versions of artifacts are allowed in the repository:</p>
   * <ul>
   *   <li>RELEASE - Only release versions</li>
   *   <li>SNAPSHOT - Only snapshot versions</li>
   *   <li>MIXED - Both release and snapshot versions</li>
   * </ul>
   * 
   * @param versionPolicy The version policy to set
   * @return this instance for fluent API
   */
  public MavenProxyRepositoryConfig withVersionPolicy(final VersionPolicy versionPolicy) {
    this.versionPolicy = versionPolicy;
    return this;
  }

  /**
   * Gets the version policy for this Maven proxy repository.
   * 
   * @return The version policy
   */
  public VersionPolicy getVersionPolicy() {
    return versionPolicy;
  }

  /**
   * Sets the layout policy for this Maven proxy repository.
   * 
   * <p>The layout policy determines how strict the repository is about Maven layout conventions:</p>
   * <ul>
   *   <li>STRICT - Enforces standard Maven layout conventions</li>
   *   <li>PERMISSIVE - Allows deviations from standard Maven layout</li>
   * </ul>
   * 
   * @param layoutPolicy The layout policy to set
   * @return this instance for fluent API
   */
  public MavenProxyRepositoryConfig withLayoutPolicy(final LayoutPolicy layoutPolicy) {
    this.layoutPolicy = layoutPolicy;
    return this;
  }

  /**
   * Gets the layout policy for this Maven proxy repository.
   * 
   * @return The layout policy
   */
  public LayoutPolicy getLayoutPolicy() {
    return layoutPolicy;
  }
}