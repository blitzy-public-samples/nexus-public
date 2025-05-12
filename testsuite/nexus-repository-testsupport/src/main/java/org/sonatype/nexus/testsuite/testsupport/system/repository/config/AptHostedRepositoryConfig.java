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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import org.sonatype.nexus.repository.Repository;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_APT;

/**
 * Configuration class for APT hosted repositories in test environments.
 * <p>
 * This class extends {@link HostedRepositoryConfigSupport} to provide a fluent builder pattern
 * for configuring APT hosted repository instances in test environments.
 * <p>
 * APT repositories require specific configuration such as distribution name and GPG keypair
 * for signing packages. This class provides methods to configure these APT-specific settings.
 * <p>
 * Compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * When running on Java 21, file operations in the {@link #withKeypair(Path)} method may benefit 
 * from Virtual Threads for improved I/O performance.
 */
public class AptHostedRepositoryConfig
    extends HostedRepositoryConfigSupport<AptHostedRepositoryConfig>
{
  private String distribution;

  private String keypair;

  /**
   * Constructs a new AptHostedRepositoryConfig with the specified factory function.
   * 
   * @param factory The function that creates a Repository instance from this configuration
   */
  public AptHostedRepositoryConfig(final Function<AptHostedRepositoryConfig, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the format name for this repository type.
   *
   * @return The format name "apt"
   */
  @Override
  public String getFormat() {
    return FORMAT_APT;
  }

  /**
   * Sets the distribution name for this APT repository.
   * <p>
   * The distribution name is used in the APT repository metadata and affects
   * how APT clients interact with the repository.
   *
   * @param distribution The distribution name to set
   * @return This instance for method chaining
   */
  public AptHostedRepositoryConfig withDistribution(final String distribution) {
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
   * Sets the GPG keypair for this APT repository using a string representation.
   * <p>
   * The keypair is used to sign packages in the APT repository.
   *
   * @param keypair The GPG keypair as a string
   * @return This instance for method chaining
   */
  public AptHostedRepositoryConfig withKeypair(final String keypair) {
    this.keypair = keypair;
    return this;
  }

  /**
   * Sets the GPG keypair for this APT repository by reading from a file.
   * <p>
   * The keypair is used to sign packages in the APT repository.
   * <p>
   * This method reads the entire file into memory, which is appropriate for test environments
   * but may not be suitable for very large keypair files in production.
   * When running on Java 21, this I/O operation may benefit from Virtual Threads.
   *
   * @param gpgFilePath The path to the GPG keypair file
   * @return This instance for method chaining
   * @throws UncheckedIOException If an I/O error occurs while reading the file
   */
  public AptHostedRepositoryConfig withKeypair(final Path gpgFilePath) {
    try {
      this.keypair = new String(Files.readAllBytes(gpgFilePath), StandardCharsets.UTF_8);
      return this;
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Gets the GPG keypair for this APT repository.
   *
   * @return The GPG keypair as a string
   */
  public String getKeypair() {
    return keypair;
  }
}