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

/**
 * Abstract support class for group repository configuration in tests.
 * <p>
 * This class provides a fluent builder pattern for configuring group repository instances
 * in test environments. It extends {@link RepositoryConfigSupport} and implements the
 * {@link GroupRepositoryConfig} interface to provide specific functionality for group repositories.
 * <p>
 * Group repositories aggregate content from multiple member repositories and can optionally
 * designate a writable member for content deployment.
 * <p>
 * Compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 *
 * @param <THIS> The concrete implementation type for fluent method chaining
 */
public abstract class GroupRepositoryConfigSupport<THIS>
    extends RepositoryConfigSupport<THIS>
    implements GroupRepositoryConfig<THIS>
{
  private static final String GROUP_RECIPE_SUFFIX = "-group";

  private String[] members;

  private String groupWriteMember = "None";

  /**
   * Constructs a new GroupRepositoryConfigSupport with the specified factory function.
   * 
   * @param factory The function that creates a Repository instance from this configuration
   */
  public GroupRepositoryConfigSupport(final Function<THIS, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the recipe identifier for this group repository.
   * <p>
   * The recipe is constructed by appending "-group" to the format name.
   *
   * @return The recipe identifier string
   */
  @Override
  public String getRecipe() {
    return getFormat() + GROUP_RECIPE_SUFFIX;
  }

  /**
   * Sets the member repositories for this group repository.
   * <p>
   * Member repositories are aggregated in the group repository view.
   *
   * @param members The names of the member repositories
   * @return This instance for method chaining
   */
  @Override
  public THIS withMembers(final String... members) {
    this.members = members;
    return toTHIS();
  }

  /**
   * Gets the member repositories for this group repository.
   *
   * @return The array of member repository names
   */
  @Override
  public String[] getMembers() {
    return members;
  }

  /**
   * Sets the writable member repository for this group repository.
   * <p>
   * The writable member is used when content is deployed to the group repository.
   * If set to "None", the group repository will not accept deployments.
   *
   * @param groupWriteMember The name of the writable member repository or "None"
   * @return This instance for method chaining
   */
  @Override
  public THIS withGroupWriteMember(final String groupWriteMember) {
    this.groupWriteMember = groupWriteMember;
    return toTHIS();
  }

  /**
   * Gets the writable member repository for this group repository.
   *
   * @return The name of the writable member repository or "None" if not set
   */
  @Override
  public String getGroupWriteMember() {
    return groupWriteMember;
  }
}