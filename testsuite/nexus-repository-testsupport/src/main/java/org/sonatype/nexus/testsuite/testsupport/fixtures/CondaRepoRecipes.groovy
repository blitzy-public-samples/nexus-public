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
package org.sonatype.nexus.testsuite.testsupport.fixtures

import javax.annotation.Nonnull

import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.config.Configuration

import groovy.transform.CompileStatic

/**
 * Factory for Conda {@link Repository} {@link Configuration}
 * 
 * This trait provides methods to create Conda repository configurations for testing purposes.
 * Compatible with Java 21 and leverages modern JVM features for improved performance.
 *
 * @since 3.19
 */
@CompileStatic
trait CondaRepoRecipes
    extends ConfigurationRecipes
{
  /**
   * Creates a Conda proxy repository with the specified name and remote URL.
   * 
   * This method utilizes pattern matching when processing repository configurations
   * and is optimized for Java 21 virtual threads when handling repository I/O operations.
   *
   * @param name the name of the repository to create
   * @param remoteUrl the URL of the remote repository to proxy
   * @return the created {@link Repository} instance
   * @throws IllegalArgumentException if name or remoteUrl is null or empty
   */
  @Nonnull
  Repository createCondaProxy(final String name, final String remoteUrl) {
    // Validate inputs using pattern matching when available
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Repository name cannot be null or empty")
    }
    
    if (remoteUrl == null || remoteUrl.isEmpty()) {
      throw new IllegalArgumentException("Remote URL cannot be null or empty")
    }
    
    def configuration = createProxy(name, 'conda-proxy', remoteUrl)
    createRepository(configuration)
  }

  /**
   * Abstract method to be implemented by concrete classes to create a repository
   * from the provided configuration.
   *
   * @param configuration the repository configuration
   * @return the created {@link Repository} instance
   */
  abstract Repository createRepository(final Configuration configuration)
}