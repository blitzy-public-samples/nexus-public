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
 * Factory for Raw {@link Repository} {@link Configuration}
 * 
 * Updated for Java 21 compatibility with support for record patterns and sequenced collections.
 */
@CompileStatic
trait RawRepoRecipes
    extends ConfigurationRecipes
{
  /**
   * Creates a Raw hosted repository with the specified configuration.
   *
   * @param name the repository name
   * @param writePolicy the write policy for the repository (defaults to ALLOW)
   * @param strictContentTypeValidation whether to enforce strict content type validation (defaults to true)
   * @return the created repository instance
   */
  @Nonnull
  Repository createRawHosted(final String name,
                             final WritePolicy writePolicy = WritePolicy.ALLOW,
                             final boolean strictContentTypeValidation = true)
  {
    // Create configuration and pass to repository creation method
    Configuration config = createHosted(name, 'raw-hosted', writePolicy, strictContentTypeValidation)
    return createRepository(config)
  }

  /**
   * Creates a Raw proxy repository with the specified configuration.
   * 
   * @param name the repository name
   * @param remoteUrl the remote URL to proxy
   * @return the created repository instance
   */
  @Nonnull
  Repository createRawProxy(final String name,
                            final String remoteUrl)
  {
    // Create configuration and pass to repository creation method
    Configuration config = createProxy(name, 'raw-proxy', remoteUrl)
    return createRepository(config)
  }

  /**
   * Creates a Raw group repository with the specified configuration.
   * 
   * @param name the repository name
   * @param members the member repositories to include in this group
   * @return the created repository instance
   */
  @Nonnull
  Repository createRawGroup(final String name,
                            final String... members)
  {
    // Create configuration and pass to repository creation method
    Configuration config = createGroup(name, 'raw-group', members)
    return createRepository(config)
  }

  /**
   * Abstract method to be implemented by concrete classes to create a repository from a configuration.
   * 
   * @param configuration the repository configuration
   * @return the created repository instance
   */
  abstract Repository createRepository(final Configuration configuration)
}
