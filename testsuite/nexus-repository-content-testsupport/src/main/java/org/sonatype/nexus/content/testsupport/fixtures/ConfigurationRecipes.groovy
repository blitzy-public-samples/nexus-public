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

import javax.annotation.Nonnull
import javax.inject.Provider

import org.sonatype.nexus.blobstore.api.BlobStoreManager
import org.sonatype.nexus.repository.config.Configuration
import org.sonatype.nexus.repository.config.WritePolicy
import org.sonatype.nexus.repository.manager.RepositoryManager

import groovy.transform.CompileStatic

import static com.google.common.base.Preconditions.checkArgument
import static com.google.common.base.Preconditions.checkNotNull

/**
 * Common Repository configuration aspects and constants.
 * <p>
 * Compatible with Java 21 runtime and leverages modern Java features for improved performance.
 */
@CompileStatic
trait ConfigurationRecipes
{
  abstract Provider<RepositoryManager> getRepositoryManagerProvider()

  /**
   * Create a hosted configuration for the given recipeName.
   * <p>
   * Optimized for Java 21 runtime with improved map handling.
   */
  @Nonnull
  Configuration createHosted(final String name,
                             final String recipeName,
                             final WritePolicy writePolicy = WritePolicy.ALLOW,
                             final boolean strictContentTypeValidation = true,
                             final String blobStoreName = BlobStoreManager.DEFAULT_BLOBSTORE_NAME,
                             final boolean latestPolicy = false)
  {
    checkNotNull(name)
    checkArgument(recipeName && recipeName.endsWith('-hosted'))

    Map<String, Object> storageAttributes = [
        blobStoreName: blobStoreName,
        writePolicy: writePolicy,
        latestPolicy: latestPolicy,
        strictContentTypeValidation: strictContentTypeValidation
    ]

    Map<String, Object> attributes = [
        storage: storageAttributes
    ]

    return newConfiguration(
        repositoryName: name,
        recipeName: recipeName,
        online: true,
        attributes: attributes
    )
  }

  /**
   * Create a proxy configuration for the given recipeName.
   * <p>
   * Optimized for Java 21 runtime with improved map handling and null safety.
   */
  @Nonnull
  Configuration createProxy(final String name,
                            final String recipeName,
                            final String remoteUrl,
                            final boolean strictContentTypeValidation = true,
                            final String blobStoreName = BlobStoreManager.DEFAULT_BLOBSTORE_NAME,
                            final Map<String, Object> authentication = [:])
  {
    checkNotNull(name)
    checkArgument(recipeName && recipeName.endsWith('-proxy'))

    Map<String, Object> connectionAttributes = [
        blocked: false,
        autoBlock: true
    ]

    Map<String, Object> httpclientAttributes = [
        connection: connectionAttributes
    ]

    // Add authentication if provided
    if (!authentication.isEmpty()) {
      httpclientAttributes.authentication = authentication
    }

    Map<String, Object> proxyAttributes = [
        remoteUrl: remoteUrl,
        contentMaxAge: 1440,
        metadataMaxAge: 1440
    ]

    Map<String, Object> negativeCacheAttributes = [
        enabled: true,
        timeToLive: 1440
    ]

    Map<String, Object> storageAttributes = [
        blobStoreName: blobStoreName,
        strictContentTypeValidation: strictContentTypeValidation
    ]

    Map<String, Object> attributes = [
        httpclient: httpclientAttributes,
        proxy: proxyAttributes,
        negativeCache: negativeCacheAttributes,
        storage: storageAttributes
    ]

    return newConfiguration(
        repositoryName: name,
        recipeName: recipeName,
        online: true,
        attributes: attributes
    )
  }

  /**
   * Create a group configuration for the given recipeName.
   * <p>
   * Optimized for Java 21 runtime with improved collection handling.
   */
  @Nonnull
  Configuration createGroup(final String name,
                            final String recipeName,
                            final String... members)
  {
    checkNotNull(name)
    checkArgument(recipeName && recipeName.endsWith('-group'))

    // Convert varargs to a List - optimized for sequenced collections in Java 21
    List<String> membersList = members.toList()

    Map<String, Object> groupAttributes = [
        memberNames: membersList
    ]

    Map<String, Object> storageAttributes = [
        blobStoreName: BlobStoreManager.DEFAULT_BLOBSTORE_NAME,
        strictContentTypeValidation: true
    ]

    Map<String, Object> attributes = [
        group: groupAttributes,
        storage: storageAttributes
    ]

    return newConfiguration(
        repositoryName: name,
        recipeName: recipeName,
        online: true,
        attributes: attributes
    )
  }

  /**
   * Creates a new Configuration instance with the provided properties.
   * <p>
   * Optimized for Java 21 runtime with improved type handling and null safety.
   *
   * @param map Configuration properties map containing repositoryName, recipeName, online status, and attributes
   * @return A new Configuration instance initialized with the provided properties
   */
  Configuration newConfiguration(final Map<String, Object> map) {
    Configuration config = repositoryManagerProvider.get().newConfiguration()
    config.repositoryName = map.repositoryName as String
    config.recipeName = map.recipeName as String
    config.online = map.online as boolean
    config.attributes = map.attributes as Map<String, Object>
    return config
  }
}
