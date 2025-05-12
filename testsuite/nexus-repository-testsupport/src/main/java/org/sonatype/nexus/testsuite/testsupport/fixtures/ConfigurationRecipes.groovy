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
import javax.inject.Provider

import org.sonatype.nexus.blobstore.api.BlobStoreManager
import org.sonatype.nexus.repository.config.Configuration
import org.sonatype.nexus.repository.manager.RepositoryManager

import groovy.transform.CompileStatic

import static com.google.common.base.Preconditions.checkArgument
import static com.google.common.base.Preconditions.checkNotNull

/**
 * Common Repository configuration aspects and constants.
 * 
 * <p>Compatible with Java 21 and leverages pattern matching for improved type safety.</p>
 */
@CompileStatic
trait ConfigurationRecipes
{
  /**
   * Provides access to the repository manager.
   * 
   * @return Provider for the repository manager
   */
  abstract Provider<RepositoryManager> getRepositoryManagerProvider()

  /**
   * Create a hosted configuration for the given recipeName.
   * 
   * @param name Repository name
   * @param recipeName Recipe name (must end with '-hosted')
   * @param writePolicy Write policy, defaults to "ALLOW"
   * @param strictContentTypeValidation Whether to enforce strict content type validation, defaults to true
   * @param blobStoreName Blob store name, defaults to the default blob store
   * @param latestPolicy Whether to enable latest policy, defaults to false
   * @return The created configuration
   */
  @Nonnull
  Configuration createHosted(final String name,
                             final String recipeName,
                             final String writePolicy = "ALLOW",
                             final boolean strictContentTypeValidation = true,
                             final String blobStoreName = BlobStoreManager.DEFAULT_BLOBSTORE_NAME,
                             final boolean latestPolicy = false)
  {
    checkNotNull(name)
    checkArgument(recipeName && recipeName.endsWith('-hosted'))

    newConfiguration(
        repositoryName: name,
        recipeName: recipeName,
        online: true,
        attributes: [
            storage: [
                blobStoreName: blobStoreName,
                writePolicy  : writePolicy,
                latestPolicy : latestPolicy,
                strictContentTypeValidation: strictContentTypeValidation,
                dataStoreName: 'nexus'
            ] as Map
        ] as Map
    )
  }

  /**
   * Create a proxy configuration for the given recipeName.
   * 
   * @param name Repository name
   * @param recipeName Recipe name (must end with '-proxy')
   * @param remoteUrl Remote URL to proxy
   * @param strictContentTypeValidation Whether to enforce strict content type validation, defaults to true
   * @param blobStoreName Blob store name, defaults to the default blob store
   * @param authentication Authentication configuration, defaults to empty map
   * @param conanVersion Conan version, defaults to "V1"
   * @return The created configuration
   */
  @Nonnull
  Configuration createProxy(final String name,
                            final String recipeName,
                            final String remoteUrl,
                            final boolean strictContentTypeValidation = true,
                            final String blobStoreName = BlobStoreManager.DEFAULT_BLOBSTORE_NAME,
                            final Map<String, Object> authentication = [:],
                            final String conanVersion = "V1")
  {
    checkNotNull(name)
    checkArgument(recipeName && recipeName.endsWith('-proxy'))

    def attributes = [
        httpclient   : [
            connection: [
                blocked  : false,
                autoBlock: true
            ] as Map<String, Object>
        ] as Map<String, Object>,
        proxy        : [
            remoteUrl     : remoteUrl,
            contentMaxAge : 1440,
            metadataMaxAge: 1440
        ] as Map<String, Object>,
        negativeCache: [
            enabled   : true,
            timeToLive: 1440
        ] as Map<String, Object>,
        conan        : [
            conanVersion: conanVersion
        ] as Map<String, Object>,
        storage      : [
            blobStoreName              : blobStoreName,
            strictContentTypeValidation: strictContentTypeValidation,
            dataStoreName: 'nexus'
        ] as Map<String, Object>
    ]
    
    // Using pattern matching to check if authentication map is not empty
    if (authentication instanceof Map && !authentication.isEmpty()) {
      attributes.httpclient.authentication = authentication
    }

    newConfiguration(
        repositoryName: name,
        recipeName: recipeName,
        online: true,
        attributes: attributes
    )
  }

  /**
   * Create a group configuration for the given recipeName.
   * 
   * @param name Repository name
   * @param recipeName Recipe name (must end with '-group')
   * @param members Member repository names
   * @return The created configuration
   */
  @Nonnull
  Configuration createGroup(final String name,
                            final String recipeName,
                            final String... members)
  {
    createGroup(name, recipeName, 'None', members)
  }

  /**
   * Create a group configuration for the given recipeName with a specified group write member.
   * 
   * @param name Repository name
   * @param recipeName Recipe name (must end with '-group')
   * @param groupWriteMember Group write member name
   * @param members Member repository names
   * @return The created configuration
   */
  @Nonnull
  Configuration createGroup(final String name,
                            final String recipeName,
                            final String groupWriteMember,
                            final String... members)
  {
    checkNotNull(name)
    checkArgument(recipeName && recipeName.endsWith('-group'))

    newConfiguration(
        repositoryName: name,
        recipeName: recipeName,
        online: true,
        attributes: [
            group  : [
                groupWriteMember: groupWriteMember,
                memberNames: members.toList()
            ] as Map<String, Object>,
            storage: [
                blobStoreName: BlobStoreManager.DEFAULT_BLOBSTORE_NAME,
                strictContentTypeValidation: true,
                dataStoreName: 'nexus'
            ] as Map<String, Object>
        ]
    )
  }

  /**
   * Creates a new configuration from the provided map.
   * 
   * @param map Configuration parameters map containing repositoryName, recipeName, online status, and attributes
   * @return The created configuration
   */
  Configuration newConfiguration(final Map map) {
    // Using pattern matching to safely extract values from the map
    Configuration config = repositoryManagerProvider.get().newConfiguration()
    
    if (map.containsKey('repositoryName') && map.repositoryName instanceof String) {
      config.repositoryName = map.repositoryName as String
    }
    
    if (map.containsKey('recipeName') && map.recipeName instanceof String) {
      config.recipeName = map.recipeName as String
    }
    
    if (map.containsKey('online') && map.online instanceof Boolean) {
      config.online = map.online as Boolean
    }
    
    if (map.containsKey('attributes') && map.attributes instanceof Map) {
      config.attributes = map.attributes as Map
    }

    return config
  }
}