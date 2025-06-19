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

import org.sonatype.nexus.blobstore.api.BlobStoreManager
import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.config.Configuration
import org.sonatype.nexus.repository.config.WritePolicy

import groovy.transform.CompileStatic

/**
 * Maven repository recipes for testing.
 * Updated for Java 21 compatibility with modern Groovy DSL.
 */
@CompileStatic
trait MavenRepoRecipes
    extends ConfigurationRecipes
{
  /**
   * Creates a Maven hosted repository with the specified configuration.
   * 
   * @param name Repository name
   * @param versionPolicy Version policy (RELEASE, SNAPSHOT, MIXED)
   * @param writePolicy Write policy for the repository
   * @param layoutPolicy Layout policy (STRICT, PERMISSIVE)
   * @param blobStoreName Name of the blob store to use
   * @return The created repository
   */
  @Nonnull
  Repository createMavenHosted(final String name,
                               final String versionPolicy = "RELEASE",
                               final WritePolicy writePolicy = WritePolicy.ALLOW_ONCE,
                               final String layoutPolicy = "STRICT",
                               final String blobStoreName = BlobStoreManager.DEFAULT_BLOBSTORE_NAME)
  {
    // Create the hosted configuration
    Configuration configuration = createHosted(name, 'maven2-hosted', writePolicy, true, blobStoreName)
    
    // Set Maven-specific attributes using modern map syntax
    configuration.attributes.maven = [
        versionPolicy: versionPolicy,
        layoutPolicy: layoutPolicy
    ]
    
    // Create and return the repository
    return createRepository(configuration)
  }

  /**
   * Abstract method to create a repository from a configuration.
   * Implementation provided by concrete classes.
   * 
   * @param configuration The repository configuration
   * @return The created repository
   */
  abstract Repository createRepository(final Configuration configuration)
}