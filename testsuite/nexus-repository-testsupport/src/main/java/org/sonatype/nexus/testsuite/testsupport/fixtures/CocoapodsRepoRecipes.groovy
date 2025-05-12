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
 * Factory for Cocoapods {@link Repository} {@link Configuration}
 * 
 * <p>Java 21 compatible implementation supporting virtual threads and modern language features.</p>
 */
@CompileStatic
trait CocoapodsRepoRecipes
    extends ConfigurationRecipes
{

  /**
   * Creates a Cocoapods proxy repository with the given name and remote URL.
   *
   * @param name the name of the repository to create
   * @param remoteUrl the URL of the remote repository to proxy
   * @return the created {@link Repository} instance
   */
  @Nonnull
  Repository createCocoapodsProxy(final String name, final String remoteUrl) {
    createRepository(createProxy(name, 'cocoapods-proxy', remoteUrl))
  }

  /**
   * Creates a repository from the given configuration.
   * This method must be implemented by classes that use this trait.
   *
   * @param configuration the repository configuration
   * @return the created {@link Repository} instance
   */
  abstract Repository createRepository(final Configuration configuration)
}