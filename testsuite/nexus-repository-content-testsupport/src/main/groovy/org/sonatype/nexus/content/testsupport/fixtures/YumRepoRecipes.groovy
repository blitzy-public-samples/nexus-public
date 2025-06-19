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
package org.sonatype.nexus.content.testsupport.fixtures

import jakarta.annotation.Nonnull

import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.config.Configuration
import org.sonatype.nexus.repository.config.WritePolicy

import groovy.transform.CompileStatic

/**
 * Factory for Yum {@link Repository} {@link Configuration}
 * 
 * Optimized for Java 21 with support for record patterns and sequenced collections.
 */
@CompileStatic
trait YumRepoRecipes
    extends ConfigurationRecipes
{
  /**
   * Creates a Yum proxy repository with the given name and remote URL.
   *
   * @param name the repository name
   * @param remoteUrl the remote URL to proxy
   * @return the created repository
   */
  @Nonnull
  Repository createYumProxy(final String name, final String remoteUrl) {
    Configuration config = createProxy(name, 'yum-proxy', remoteUrl, false)
    createRepository(config)
  }

  /**
   * Creates a Yum hosted repository with the given name and write policy.
   *
   * @param name the repository name
   * @param writePolicy the write policy to use, defaults to ALLOW
   * @return the created repository
   */
  @Nonnull
  Repository createYumHosted(final String name, final WritePolicy writePolicy = WritePolicy.ALLOW) {
    Configuration config = createHosted(name, 'yum-hosted', writePolicy, false)
    createRepository(config)
  }

  /**
   * Creates a Yum group repository with the given name and members.
   * Uses sequenced collections to maintain member order.
   *
   * @param name the repository name
   * @param members the member repository names
   * @return the created repository
   */
  @Nonnull
  Repository createYumGroup(final String name, final String... members) {
    Configuration config = createGroup(name, 'yum-group', members)
    createRepository(config)
  }

  /**
   * Abstract method to create a repository from a configuration.
   * Implementations should handle the configuration using record patterns when possible.
   *
   * @param configuration the repository configuration
   * @return the created repository
   */
  abstract Repository createRepository(final Configuration configuration)
}