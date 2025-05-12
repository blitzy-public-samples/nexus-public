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
 * Factory for Raw {@link Repository} {@link Configuration}
 * 
 * @since 3.0
 * @see Repository
 * @see Configuration
 * 
 * <p>Compatible with Java 21 and leverages modern language features for improved test support.</p>
 */
@CompileStatic
trait RawRepoRecipes
    extends ConfigurationRecipes
{

  /**
   * Creates a raw hosted repository with the specified configuration.
   *
   * @param name the repository name
   * @param writePolicy the write policy (default: "ALLOW")
   * @param strictContentTypeValidation whether to enforce strict content type validation (default: true)
   * @param blobStoreName the blob store name (default: BlobStoreManager.DEFAULT_BLOBSTORE_NAME)
   * @return the created repository
   * @since 3.0
   */
  @Nonnull
  Repository createRawHosted(final String name,
                             final String writePolicy = "ALLOW",
                             final boolean strictContentTypeValidation = true,
                             final String blobStoreName = BlobStoreManager.DEFAULT_BLOBSTORE_NAME)
  {
    createRepository(createHosted(name, 'raw-hosted', writePolicy, strictContentTypeValidation, blobStoreName))
  }

  /**
   * Creates a raw proxy repository with the specified configuration.
   *
   * @param name the repository name
   * @param remoteUrl the remote URL to proxy
   * @return the created repository
   * @since 3.0
   */
  @Nonnull
  Repository createRawProxy(final String name,
                            final String remoteUrl)
  {
    createRepository(createProxy(name, 'raw-proxy', remoteUrl))
  }

  /**
   * Creates a raw group repository with the specified configuration.
   *
   * @param name the repository name
   * @param members the member repositories to include in the group
   * @return the created repository
   * @since 3.0
   */
  @Nonnull
  Repository createRawGroup(final String name,
                            final String... members)
  {
    createRepository(createGroup(name, 'raw-group', members))
  }

  /**
   * Creates a repository with the given configuration.
   * This method must be implemented by classes that use this trait.
   *
   * @param configuration the repository configuration
   * @return the created repository
   * @since 3.0
   */
  abstract Repository createRepository(final Configuration configuration)

}