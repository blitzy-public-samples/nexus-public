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
 * Factory for Go {@link Repository} {@link Configuration}
 * <p>
 * Compatible with Java 21 runtime environment and leverages modern language features
 * where appropriate. This trait is designed to work with the updated OSGi bundle manifests
 * and dependency injection configuration in the Java 21 environment.
 * <p>
 * Used in test fixtures with JUnit Jupiter 5.10.1 and Spock 2.3-groovy-4.0.
 */
@CompileStatic
trait GolangRepoRecipes
    extends ConfigurationRecipes
{
  /**
   * Creates a Go proxy repository with the given name and remote URL.  
   * 
   * @param name the repository name
   * @param remoteUrl the remote repository URL to proxy
   * @return the created repository instance
   */
  @Nonnull
  Repository createGolangProxy(final String name,
                               final String remoteUrl)
  {
    // Create proxy configuration and pass to implementation
    // Java 21 compatible implementation with clear parameter passing
    createRepository(createProxy(name, 'go-proxy', remoteUrl))
  }

  /**
   * Creates a Go hosted repository with the given name.
   * 
   * @param name the repository name
   * @return the created repository instance
   */
  @Nonnull
  Repository createGolangHosted(final String name)
  {
    // Create hosted configuration and pass to implementation
    // Java 21 compatible implementation with clear parameter passing
    createRepository(createHosted(name, 'go-hosted'))
  }

  /**
   * Creates a Go repository group with the given name and member repositories.
   * 
   * @param name the repository group name
   * @param members the member repository names
   * @return the created repository group instance
   */
  @Nonnull
  Repository createGolangGroup(final String name, String ...members) {
    // Create group configuration and pass to implementation
    // Java 21 compatible implementation with varargs parameter handling
    createRepository(createGroup(name, 'go-group', members))
  }

  /**
   * Abstract method to be implemented by concrete classes to create a repository from configuration.
   * 
   * @param configuration the repository configuration
   * @return the created repository instance
   */
  abstract Repository createRepository(final Configuration configuration)
}
