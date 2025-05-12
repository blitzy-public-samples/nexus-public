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
import org.sonatype.nexus.repository.config.WritePolicy;

/**
 * Abstract support class for hosted repository configuration in tests.
 * <p>
 * This class extends {@link RepositoryConfigSupport} and implements {@link HostedRepositoryConfig}
 * to provide a fluent builder pattern for configuring hosted repository instances in test environments.
 * <p>
 * Hosted repositories are repositories that store content locally rather than proxying from a remote source.
 * This class provides configuration options specific to hosted repositories, such as write policy and
 * replication settings.
 * <p>
 * Compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * When running on Java 21, repository operations may benefit from Virtual Threads for improved concurrency.
 *
 * @param <THIS> The concrete implementation type for fluent method chaining
 */
public abstract class HostedRepositoryConfigSupport<THIS>
    extends RepositoryConfigSupport<THIS>
    implements HostedRepositoryConfig<THIS>
{
  private static final String HOSTED_RECIPE_SUFFIX = "-hosted";

  private WritePolicy writePolicy = WritePolicy.ALLOW;

  private Boolean replicationEnabled = false;

  /**
   * Constructs a new HostedRepositoryConfigSupport with the specified factory function.
   * 
   * @param factory The function that creates a Repository instance from this configuration
   */
  public HostedRepositoryConfigSupport(final Function<THIS, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the recipe name for this repository type.
   * <p>
   * The recipe name is constructed by appending "-hosted" to the format name.
   *
   * @return The recipe name for this repository type
   */
  @Override
  public String getRecipe() {
    return getFormat() + HOSTED_RECIPE_SUFFIX;
  }

  /**
   * Sets the write policy for this hosted repository.
   * <p>
   * The write policy determines whether content can be deployed to the repository,
   * and whether existing content can be overwritten.
   *
   * @param writePolicy The write policy to set
   * @return This instance for method chaining
   */
  @Override
  public THIS withWritePolicy(final WritePolicy writePolicy) {
    this.writePolicy = writePolicy;
    return toTHIS();
  }

  /**
   * Gets the write policy for this hosted repository.
   *
   * @return The write policy
   */
  @Override
  public WritePolicy getWritePolicy() {
    return writePolicy;
  }

  /**
   * Sets whether replication is enabled for this hosted repository.
   * <p>
   * When replication is enabled, content in this repository can be replicated to other instances.
   *
   * @param replicationEnabled True if replication should be enabled, false otherwise
   * @return This instance for method chaining
   */
  @Override
  public THIS withReplicationEnabled(final Boolean replicationEnabled) {
    this.replicationEnabled = replicationEnabled;
    return toTHIS();
  }

  /**
   * Gets whether replication is enabled for this hosted repository.
   *
   * @return True if replication is enabled, false otherwise
   */
  @Override
  public Boolean isReplicationEnabled() {
    return replicationEnabled;
  }
}
