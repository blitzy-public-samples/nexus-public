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
package org.sonatype.nexus.testsuite.testsupport.system.repository.config;

/**
 * Configuration interface for proxy repositories in test support.
 * <p>
 * This interface provides methods to configure proxy repository properties for testing purposes.
 * Implementations of this interface are used in integration tests to set up proxy repositories
 * with specific configurations.
 * <p>
 * With Java 21, proxy repository operations benefit from Virtual Threads for improved performance
 * in I/O-bound operations such as remote content fetching, which is particularly relevant for
 * proxy repositories that interact with external systems.
 *
 * @param <THIS> Self-referential type parameter for fluent API pattern
 * @since 3.60.0
 * @see org.sonatype.nexus.testsuite.testsupport.system.repository.config.RepositoryConfig
 */
public interface ProxyRepositoryConfig<THIS>
    extends RepositoryConfig<THIS>
{
  /**
   * Sets whether this proxy repository is blocked.
   *
   * @param blocked true if the repository should be blocked, false otherwise
   * @return this instance for method chaining
   */
  THIS withBlocked(final Boolean blocked);

  /**
   * Checks if this proxy repository is blocked.
   *
   * @return true if the repository is blocked, false otherwise
   */
  Boolean isBlocked();

  /**
   * Sets whether this proxy repository is auto-blocked.
   *
   * @param autoBlocked true if the repository should be auto-blocked, false otherwise
   * @return this instance for method chaining
   */
  THIS withAutoBlocked(final Boolean autoBlocked);

  /**
   * Checks if this proxy repository is auto-blocked.
   *
   * @return true if the repository is auto-blocked, false otherwise
   */
  Boolean isAutoBlocked();

  /**
   * Sets the username for authentication to the remote repository.
   *
   * @param username the username to use for authentication
   * @return this instance for method chaining
   */
  THIS withUsername(final String username);

  /**
   * Gets the username for authentication to the remote repository.
   *
   * @return the username
   */
  String getUsername();

  /**
   * Sets the password for authentication to the remote repository.
   *
   * @param password the password to use for authentication
   * @return this instance for method chaining
   */
  THIS withPassword(final String password);

  /**
   * Gets the password for authentication to the remote repository.
   *
   * @return the password
   */
  String getPassword();

  /**
   * Sets the remote URL for this proxy repository.
   * <p>
   * In Java 21, remote URL connections benefit from Virtual Threads for improved
   * I/O performance and reduced resource consumption during concurrent operations.
   *
   * @param remoteUrl the URL of the remote repository to proxy
   * @return this instance for method chaining
   */
  THIS withRemoteUrl(final String remoteUrl);

  /**
   * Gets the remote URL for this proxy repository.
   *
   * @return the remote URL
   */
  String getRemoteUrl();

  /**
   * Enables pull replication for this proxy repository.
   * <p>
   * With Java 21's Virtual Threads, pull replication operations can be more efficient
   * with improved concurrency for multiple simultaneous replication tasks.
   *
   * @return this instance for method chaining
   */
  THIS withPullReplication();

  /**
   * Checks if preemptive pull is enabled for this proxy repository.
   *
   * @return true if preemptive pull is enabled, false otherwise
   */
  Boolean isPreemptivePullEnabled();

  /**
   * Sets the asset path regex for this proxy repository.
   *
   * @param assetPathRegex the regex pattern for asset paths
   * @return this instance for method chaining
   */
  THIS withAssetPathRegex(String assetPathRegex);

  /**
   * Gets the asset path regex for this proxy repository.
   *
   * @return the asset path regex
   */
  String getAssetPathRegex();

  /**
   * Sets the content max age for this proxy repository.
   *
   * @param contentMaxAge the maximum age of content in seconds
   * @return this instance for method chaining
   */
  THIS withContentMaxAge(final Integer contentMaxAge);

  /**
   * Gets the content max age for this proxy repository.
   *
   * @return the content max age in seconds
   */
  Integer getContentMaxAge();

  /**
   * Sets the metadata max age for this proxy repository.
   *
   * @param metadataMaxAge the maximum age of metadata in seconds
   * @return this instance for method chaining
   */
  THIS withMetadataMaxAge(final Integer metadataMaxAge);

  /**
   * Gets the metadata max age for this proxy repository.
   *
   * @return the metadata max age in seconds
   */
  Integer getMetadataMaxAge();

  /**
   * Sets whether negative cache is enabled for this proxy repository.
   *
   * @param negativeCacheEnabled true if negative cache should be enabled, false otherwise
   * @return this instance for method chaining
   */
  THIS withNegativeCacheEnabled(final Boolean negativeCacheEnabled);

  /**
   * Checks if negative cache is enabled for this proxy repository.
   *
   * @return true if negative cache is enabled, false otherwise
   */
  Boolean isNegativeCacheEnabled();

  /**
   * Sets the negative cache time to live for this proxy repository.
   *
   * @param negativeCacheTimeToLive the time to live for negative cache entries in seconds
   * @return this instance for method chaining
   */
  THIS withNegativeCacheTimeToLive(final Integer negativeCacheTimeToLive);

  /**
   * Gets the negative cache time to live for this proxy repository.
   *
   * @return the negative cache time to live in seconds
   */
  Integer getNegativeCacheTimeToLive();
}
