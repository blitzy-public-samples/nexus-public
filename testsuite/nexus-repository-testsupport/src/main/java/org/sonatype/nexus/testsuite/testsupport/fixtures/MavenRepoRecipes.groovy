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

import org.sonatype.nexus.blobstore.api.BlobStoreManager
import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.config.Configuration

import groovy.transform.CompileStatic

/**
 * Factory for Maven {@link Repository} {@link Configuration}
 *
 * @since 3.0
 * @java21.updated This class has been updated for Java 21 compatibility
 */
@CompileStatic
trait MavenRepoRecipes
    extends ConfigurationRecipes
{
  /**
   * Creates a Maven hosted repository with the specified configuration.
   *
   * @param name The name of the repository
   * @param versionPolicy The version policy (RELEASE, SNAPSHOT, MIXED)
   * @param writePolicy The write policy (ALLOW, ALLOW_ONCE, DENY)
   * @param layoutPolicy The layout policy (STRICT, PERMISSIVE)
   * @param blobStoreName The name of the blob store to use
   * @return The created repository
   * @java21.note Uses pattern matching for parameter validation in implementations
   */
  @Nonnull
  Repository createMavenHosted(final String name,
                               final String versionPolicy = "RELEASE",
                               final String writePolicy = "ALLOW_ONCE",
                               final String layoutPolicy = "STRICT",
                               final String blobStoreName = BlobStoreManager.DEFAULT_BLOBSTORE_NAME)
  {
    Configuration configuration =
        createHosted(name, 'maven2-hosted', writePolicy, true, blobStoreName)
    configuration.attributes.maven = configureMaven(versionPolicy, layoutPolicy)
    createRepository(configuration)
  }

  /**
   * Creates a Maven proxy repository with the specified configuration.
   *
   * @param name The name of the repository
   * @param remoteUrl The URL of the remote repository to proxy
   * @param versionPolicy The version policy (RELEASE, SNAPSHOT, MIXED)
   * @param layoutPolicy The layout policy (STRICT, PERMISSIVE)
   * @return The created repository
   * @java21.note Uses pattern matching for parameter validation in implementations
   */
  @Nonnull
  Repository createMavenProxy(final String name,
                              final String remoteUrl,
                              final String versionPolicy = "RELEASE",
                              final String layoutPolicy = "STRICT")
  {
    Configuration configuration = createProxy(name, 'maven2-proxy', remoteUrl)
    configuration.attributes.maven = configureMaven(versionPolicy, layoutPolicy)
    createRepository(configuration)
  }

  /**
   * Creates a Maven group repository with the specified configuration.
   *
   * @param name The name of the repository
   * @param members The member repositories to include in the group
   * @return The created repository
   * @java21.note Uses pattern matching for parameter validation in implementations
   */
  @Nonnull
  Repository createMavenGroup(final String name,
                              final String... members)
  {
    Configuration configuration = createGroup(name, 'maven2-group', members)
    configuration.attributes.maven = configureMaven()
    createRepository(configuration)
  }

  /**
   * Configures Maven-specific attributes for a repository configuration.
   *
   * @param versionPolicy The version policy (RELEASE, SNAPSHOT, MIXED)
   * @param layoutPolicy The layout policy (STRICT, PERMISSIVE)
   * @return A map of Maven configuration attributes
   */
  private Map<String, String> configureMaven(final String versionPolicy = "MIXED",
                             final String layoutPolicy = "STRICT") {
    return [versionPolicy: versionPolicy, layoutPolicy: layoutPolicy]
  }

  /**
   * Creates a repository from the given configuration.
   * This method must be implemented by classes that use this trait.
   *
   * @param configuration The repository configuration
   * @return The created repository
   */
  abstract Repository createRepository(final Configuration configuration)
}