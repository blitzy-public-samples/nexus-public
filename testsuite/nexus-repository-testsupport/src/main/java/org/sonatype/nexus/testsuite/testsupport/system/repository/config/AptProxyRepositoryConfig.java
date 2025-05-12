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

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_APT;

/**
 * Configuration class for APT proxy repositories in test support.
 * <p>
 * This class provides a fluent API for configuring APT-specific proxy repository properties
 * for testing purposes. It extends the generic proxy repository configuration support
 * with APT-specific attributes like distribution and flat repository structure.
 * <p>
 * Compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * When used with Java 21, APT proxy repositories can leverage Virtual Threads for improved
 * performance in I/O operations, particularly for remote connections and content transfers.
 *
 * @since 3.60.0
 */
public class AptProxyRepositoryConfig
    extends ProxyRepositoryConfigSupport<AptProxyRepositoryConfig>
{
  /** The distribution name for this APT repository */
  private String distribution;

  /** Flag indicating if this is a flat repository structure */
  private Boolean flat;

  /**
   * Constructs a new APT proxy repository configuration with the specified factory function.
   *
   * @param factory Function to create a repository from this configuration
   */
  public AptProxyRepositoryConfig(final Function<AptProxyRepositoryConfig, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the format name for this repository configuration.
   *
   * @return The APT format name
   */
  @Override
  public String getFormat() {
    return FORMAT_APT;
  }

  /**
   * Sets the distribution name for this APT repository.
   * <p>
   * The distribution name is a required property for APT repositories and defines
   * the distribution section of the repository (e.g., "bionic", "focal", etc.).
   *
   * @param distribution The distribution name
   * @return this instance for fluent API
   */
  public AptProxyRepositoryConfig withDistribution(final String distribution) {
    this.distribution = distribution;
    return this;
  }

  /**
   * Gets the distribution name for this APT repository.
   *
   * @return The distribution name
   */
  public String getDistribution() {
    return distribution;
  }

  /**
   * Sets whether this APT repository has a flat structure.
   * <p>
   * A flat repository structure means that all packages are stored in the root directory
   * without the typical dists/pool hierarchy of a standard APT repository.
   *
   * @param flat True if the repository has a flat structure, false otherwise
   * @return this instance for fluent API
   */
  public AptProxyRepositoryConfig withFlat(final Boolean flat) {
    this.flat = flat;
    return this;
  }

  /**
   * Checks if this APT repository has a flat structure.
   *
   * @return True if the repository has a flat structure, false otherwise
   */
  public Boolean isFlat() {
    return flat;
  }
}
