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

import java.util.function.Function;

import org.sonatype.nexus.repository.Repository;

/**
 * Support class for configuring proxy repositories in tests.
 * 
 * <p>This class is compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.</p>
 * 
 * <p>When used with Java 21, this configuration can be used to test proxy repositories that leverage Virtual Threads
 * for improved I/O operations performance, particularly for remote connections and content transfers.</p>
 * 
 * @param <THIS> Self-referential type parameter for fluent API pattern
 */
public abstract class ProxyRepositoryConfigSupport<THIS>
    extends RepositoryConfigSupport<THIS>
    implements ProxyRepositoryConfig<THIS>
{
  /** Suffix used for proxy repository recipes */
  private static final String PROXY_RECIPE_SUFFIX = "-proxy";

  /** Remote URL for the proxy repository */
  private String remoteUrl;

  /** Flag to enable preemptive pull replication */
  private boolean preemptivePullEnabled = false;

  /** Regular expression for filtering assets */
  private String assetPathRegex;

  /** Flag indicating if the repository is blocked */
  private Boolean blocked = false;

  /** Flag indicating if the repository should be auto-blocked on errors */
  private Boolean autoBlocked = true;

  /** Maximum age in minutes for content cache */
  private Integer contentMaxAge = 1440;

  /** Maximum age in minutes for metadata cache */
  private Integer metadataMaxAge = 1440;

  /** Flag to enable negative cache */
  private Boolean negativeCacheEnabled = true;

  /** Time to live in minutes for negative cache entries */
  private Integer negativeCacheTimeToLive = 1440;

  /** Username for remote authentication */
  private String username;

  /** Password for remote authentication */
  private String password;

  /**
   * Constructor.
   *
   * @param factory Function to create a repository from this configuration
   */
  public ProxyRepositoryConfigSupport(final Function<THIS, Repository> factory) {
    super(factory);
  }

  /**
   * Gets the recipe name for this proxy repository.
   * 
   * @return The recipe name, which is the format name plus "-proxy" suffix
   */
  @Override
  public String getRecipe() {
    return getFormat() + PROXY_RECIPE_SUFFIX;
  }

  /**
   * Sets the remote URL for this proxy repository.
   * 
   * @param remoteUrl The remote URL to proxy
   * @return this instance for fluent API
   */
  @Override
  public THIS withRemoteUrl(final String remoteUrl) {
    this.remoteUrl = remoteUrl;
    return toTHIS();
  }

  /**
   * Gets the remote URL for this proxy repository.
   * 
   * @return The remote URL
   */
  @Override
  public String getRemoteUrl() {
    return remoteUrl;
  }

  /**
   * Enables preemptive pull replication for this proxy repository.
   * 
   * <p>When using Java 21, this can leverage Virtual Threads for improved performance
   * during pull replication operations.</p>
   * 
   * @return this instance for fluent API
   */
  @Override
  public THIS withPullReplication(){
    this.preemptivePullEnabled = true;
    return toTHIS();
  }

  /**
   * Checks if preemptive pull replication is enabled.
   * 
   * @return true if preemptive pull replication is enabled, false otherwise
   */
  @Override
  public Boolean isPreemptivePullEnabled(){
    return this.preemptivePullEnabled;
  }

  /**
   * Sets the asset path regex for filtering assets in this proxy repository.
   * 
   * @param assetPathRegex The regex pattern for asset paths
   * @return this instance for fluent API
   */
  @Override
  public THIS withAssetPathRegex(final String assetPathRegex){
    this.assetPathRegex = assetPathRegex;
    return toTHIS();
  }

  /**
   * Gets the asset path regex for this proxy repository.
   * 
   * @return The regex pattern for asset paths
   */
  @Override
  public String getAssetPathRegex() {
    return assetPathRegex;
  }

  /**
   * Sets whether this proxy repository is blocked.
   * 
   * @param blocked true to block the repository, false otherwise
   * @return this instance for fluent API
   */
  @Override
  public THIS withBlocked(final Boolean blocked) {
    this.blocked = blocked;
    return toTHIS();
  }

  /**
   * Checks if this proxy repository is blocked.
   * 
   * @return true if the repository is blocked, false otherwise
   */
  @Override
  public Boolean isBlocked() {
    return blocked;
  }

  /**
   * Sets whether this proxy repository should be auto-blocked on errors.
   * 
   * @param autoBlocked true to enable auto-blocking, false otherwise
   * @return this instance for fluent API
   */
  @Override
  public THIS withAutoBlocked(final Boolean autoBlocked) {
    this.autoBlocked = autoBlocked;
    return toTHIS();
  }

  /**
   * Checks if auto-blocking is enabled for this proxy repository.
   * 
   * @return true if auto-blocking is enabled, false otherwise
   */
  @Override
  public Boolean isAutoBlocked() {
    return autoBlocked;
  }

  /**
   * Sets the maximum age in minutes for content cache in this proxy repository.
   * 
   * @param contentMaxAge The maximum age in minutes
   * @return this instance for fluent API
   */
  @Override
  public THIS withContentMaxAge(final Integer contentMaxAge) {
    this.contentMaxAge = contentMaxAge;
    return toTHIS();
  }

  /**
   * Gets the maximum age in minutes for content cache in this proxy repository.
   * 
   * @return The maximum age in minutes
   */
  @Override
  public Integer getContentMaxAge() {
    return contentMaxAge;
  }

  /**
   * Sets the maximum age in minutes for metadata cache in this proxy repository.
   * 
   * @param metadataMaxAge The maximum age in minutes
   * @return this instance for fluent API
   */
  @Override
  public THIS withMetadataMaxAge(final Integer metadataMaxAge) {
    this.metadataMaxAge = metadataMaxAge;
    return toTHIS();
  }

  /**
   * Gets the maximum age in minutes for metadata cache in this proxy repository.
   * 
   * @return The maximum age in minutes
   */
  @Override
  public Integer getMetadataMaxAge() {
    return metadataMaxAge;
  }

  /**
   * Sets whether negative caching is enabled for this proxy repository.
   * 
   * @param negativeCacheEnabled true to enable negative caching, false otherwise
   * @return this instance for fluent API
   */
  @Override
  public THIS withNegativeCacheEnabled(final Boolean negativeCacheEnabled) {
    this.negativeCacheEnabled = negativeCacheEnabled;
    return toTHIS();
  }

  /**
   * Checks if negative caching is enabled for this proxy repository.
   * 
   * @return true if negative caching is enabled, false otherwise
   */
  @Override
  public Boolean isNegativeCacheEnabled() {
    return negativeCacheEnabled;
  }

  /**
   * Sets the time to live in minutes for negative cache entries in this proxy repository.
   * 
   * @param negativeCacheTimeToLive The time to live in minutes
   * @return this instance for fluent API
   */
  @Override
  public THIS withNegativeCacheTimeToLive(final Integer negativeCacheTimeToLive) {
    this.negativeCacheTimeToLive = negativeCacheTimeToLive;
    return toTHIS();
  }

  /**
   * Gets the time to live in minutes for negative cache entries in this proxy repository.
   * 
   * @return The time to live in minutes
   */
  @Override
  public Integer getNegativeCacheTimeToLive() {
    return negativeCacheTimeToLive;
  }

  /**
   * Sets the username for remote authentication in this proxy repository.
   * 
   * @param username The username for authentication
   * @return this instance for fluent API
   */
  @Override
  public THIS withUsername(final String username) {
    this.username = username;
    return toTHIS();
  }

  /**
   * Gets the username for remote authentication in this proxy repository.
   * 
   * @return The username for authentication
   */
  @Override
  public String getUsername() {
    return username;
  }

  /**
   * Sets the password for remote authentication in this proxy repository.
   * 
   * @param password The password for authentication
   * @return this instance for fluent API
   */
  @Override
  public THIS withPassword(final String password) {
    this.password = password;
    return toTHIS();
  }

  /**
   * Gets the password for remote authentication in this proxy repository.
   * 
   * @return The password for authentication
   */
  @Override
  public String getPassword() {
    return password;
  }
}
