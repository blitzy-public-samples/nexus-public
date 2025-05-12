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
import jakarta.annotation.Nullable

import org.sonatype.nexus.blobstore.api.BlobStoreManager
import org.sonatype.nexus.common.entity.EntityId
import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.config.Configuration
import org.sonatype.nexus.repository.config.WritePolicy

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

/**
 * Docker repository recipes for test fixtures.
 * Updated for Java 21 compatibility with record patterns and sequenced collections.
 */
@CompileStatic
trait DockerRepoRecipes
    extends ConfigurationRecipes
{
  // TODO: Docker group

  /**
   * Creates a hosted Docker repository with the specified configuration.
   *
   * @param repoName Repository name
   * @param httpPort HTTP port for Docker registry, or null if not used
   * @param httpsPort HTTPS port for Docker registry, or null if not used
   * @param v1Enabled Whether Docker V1 API is enabled
   * @param writePolicy Write policy for the repository
   * @param latestPolicy Whether latest tag policy is enabled
   * @param strictContentTypeValidation Whether strict content type validation is enabled
   * @return The created repository
   */
  @Nonnull
  Repository createDockerHosted(final String repoName,
                                @Nullable int httpPort,
                                @Nullable int httpsPort,
                                final boolean v1Enabled = true,
                                final WritePolicy writePolicy = WritePolicy.ALLOW,
                                final boolean latestPolicy = false,
                                final boolean strictContentTypeValidation = true
  )
  {
    Configuration configuration =
        createHosted(repoName, 'docker-hosted', writePolicy, true, BlobStoreManager.DEFAULT_BLOBSTORE_NAME,
            latestPolicy)
    configuration.attributes.docker = configureDockerAttributes(httpPort, httpsPort, v1Enabled)
    createRepository(configuration)
  }

  /**
   * Creates a proxy Docker repository with the specified configuration.
   *
   * @param name Repository name
   * @param remoteUrl Remote URL to proxy
   * @param indexType Index type for Docker registry
   * @param indexUrl Index URL for Docker registry, or null if not used
   * @param httpPort HTTP port for Docker registry, or null if not used
   * @param httpsPort HTTPS port for Docker registry, or null if not used
   * @param v1Enabled Whether Docker V1 API is enabled
   * @param strictContentTypeValidation Whether strict content type validation is enabled
   * @param cacheForeignLayers Whether to cache foreign layers
   * @param whitelist List of foreign layer URL patterns to whitelist
   * @return The created repository
   */
  @Nonnull
  @CompileDynamic
  Repository createDockerProxy(final String name,
                               final String remoteUrl,
                               final String indexType,
                               @Nullable final String indexUrl,
                               @Nullable int httpPort,
                               @Nullable int httpsPort,
                               final boolean v1Enabled = true,
                               final boolean strictContentTypeValidation = true,
                               final boolean cacheForeignLayers = false,
                               final List<String> whitelist = []
  )
  {
    Configuration configuration = createProxy(name, 'docker-proxy', remoteUrl, strictContentTypeValidation)
    configuration.attributes.docker = configureDockerAttributes(httpPort, httpsPort, v1Enabled)
    configuration.attributes.dockerProxy = [
        indexType               : indexType,
        indexUrl                : indexUrl,
        cacheForeignLayers      : cacheForeignLayers,
        foreignLayerUrlWhitelist: whitelist
    ]
    configuration.attributes.httpclient.connection.useTrustStore = true
    createRepository(configuration)
  }

  /**
   * Creates a proxy Docker repository with the specified configuration and routing rule.
   *
   * @param name Repository name
   * @param remoteUrl Remote URL to proxy
   * @param indexType Index type for Docker registry
   * @param routingRuleId Routing rule ID to apply
   * @param indexUrl Index URL for Docker registry, or null if not used
   * @param httpPort HTTP port for Docker registry, or null if not used
   * @param httpsPort HTTPS port for Docker registry, or null if not used
   * @param v1Enabled Whether Docker V1 API is enabled
   * @param strictContentTypeValidation Whether strict content type validation is enabled
   * @return The created repository
   */
  @Nonnull
  @CompileDynamic
  Repository createDockerProxy(final String name,
                               final String remoteUrl,
                               final String indexType,
                               final EntityId routingRuleId,
                               @Nullable final String indexUrl,
                               @Nullable int httpPort,
                               @Nullable int httpsPort,
                               final boolean v1Enabled = true,
                               final boolean strictContentTypeValidation = true
  )
  {
    Configuration configuration = createProxy(name, 'docker-proxy', remoteUrl, strictContentTypeValidation)
    configuration.attributes.docker = configureDockerAttributes(httpPort, httpsPort, v1Enabled)
    configuration.attributes.dockerProxy = [
        indexType: indexType,
        indexUrl : indexUrl
    ]
    configuration.attributes.httpclient.connection.useTrustStore = true
    configuration.routingRuleId = routingRuleId
    createRepository(configuration)
  }

  /**
   * Creates a proxy Docker repository with the specified configuration and authentication.
   *
   * @param name Repository name
   * @param remoteUrl Remote URL to proxy
   * @param indexType Index type for Docker registry
   * @param indexUrl Index URL for Docker registry, or null if not used
   * @param httpPort HTTP port for Docker registry, or null if not used
   * @param httpsPort HTTPS port for Docker registry, or null if not used
   * @param username Username for authentication
   * @param password Password for authentication
   * @param v1Enabled Whether Docker V1 API is enabled
   * @param strictContentTypeValidation Whether strict content type validation is enabled
   * @return The created repository
   */
  @Nonnull
  @CompileDynamic
  Repository createDockerProxy(final String name,
                               final String remoteUrl,
                               final String indexType,
                               @Nullable final String indexUrl,
                               @Nullable int httpPort,
                               @Nullable int httpsPort,
                               final String username,
                               final String password,
                               final boolean v1Enabled = true,
                               final boolean strictContentTypeValidation = true
  )
  {
    Configuration configuration = createProxy(name, 'docker-proxy', remoteUrl, strictContentTypeValidation)
    configuration.attributes.docker = configureDockerAttributes(httpPort, httpsPort, v1Enabled)
    configuration.attributes.dockerProxy = [
        indexType: indexType,
        indexUrl : indexUrl
    ]
    configuration.attributes.httpclient.connection.useTrustStore = true
    configuration.attributes.httpclient.authentication = [
        type    : 'username',
        username: username,
        password: password
    ]
    createRepository(configuration)
  }

  /**
   * Configures Docker attributes for repository configuration.
   *
   * @param httpPort HTTP port for Docker registry, or null if not used
   * @param httpsPort HTTPS port for Docker registry, or null if not used
   * @param v1Enabled Whether Docker V1 API is enabled
   * @return Map of Docker attributes
   */
  private Map configureDockerAttributes(int httpPort, int httpsPort, boolean v1Enabled) {
    def docker = [:]
    if (httpPort) {
      docker.httpPort = httpPort
    }
    if (httpsPort) {
      docker.httpsPort = httpsPort
    }
    docker.v1Enabled = v1Enabled
    return docker
  }

  /**
   * Creates a repository with the specified configuration.
   *
   * @param configuration Repository configuration
   * @return The created repository
   */
  abstract Repository createRepository(final Configuration configuration)

}