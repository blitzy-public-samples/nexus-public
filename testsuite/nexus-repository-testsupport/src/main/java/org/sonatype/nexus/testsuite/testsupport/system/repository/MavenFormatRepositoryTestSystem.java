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
package org.sonatype.nexus.testsuite.testsupport.system.repository;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.MavenGroupRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.MavenHostedRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.MavenProxyRepositoryConfig;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_MAVEN;

/**
 * Maven format repository test system implementation.
 * <p>
 * This implementation leverages Java 21 virtual threads for I/O-bound operations
 * to improve performance and scalability for repository provisioning and configuration.
 * 
 * @since 3.0
 */
@Named(FORMAT_MAVEN)
@Singleton
public class MavenFormatRepositoryTestSystem
    extends FormatRepositoryTestSystemSupport
                <MavenHostedRepositoryConfig,
                    MavenProxyRepositoryConfig,
                    MavenGroupRepositoryConfig>
    implements FormatRepositoryTestSystem
{
  public static final String ATTRIBUTES_MAP_KEY_MAVEN = "maven";

  public static final String ATTRIBUTES_KEY_VERSION_POLICY = "versionPolicy";

  public static final String ATTRIBUTES_KEY_LAYOUT_POLICY = "layoutPolicy";
  
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public MavenFormatRepositoryTestSystem(final RepositoryManager repositoryManager) {
    super(repositoryManager);
    // Create a virtual thread per task executor for I/O-bound operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates a new Maven hosted repository configuration.
   *
   * @param name the repository name
   * @return the repository configuration
   */
  public MavenHostedRepositoryConfig hosted(final String name) {
    return new MavenHostedRepositoryConfig(this::createHosted).withName(name);
  }

  /**
   * Creates a Maven hosted repository using virtual threads for I/O-bound operations.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createHosted(final MavenHostedRepositoryConfig config) {
    // Use CompletableFuture with virtual threads for I/O-bound operations
    try {
      return CompletableFuture.supplyAsync(
          () -> doCreate(applyMavenAttributes(
              createHostedConfiguration(config), 
              config.getVersionPolicy(), 
              config.getLayoutPolicy())),
          virtualThreadExecutor)
          .join();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create Maven hosted repository", e);
    }
  }

  /**
   * Creates a new Maven proxy repository configuration.
   *
   * @param name the repository name
   * @return the repository configuration
   */
  public MavenProxyRepositoryConfig proxy(final String name) {
    return new MavenProxyRepositoryConfig(this::createProxy)
         .withName(name);
  }

  /**
   * Creates a Maven proxy repository using virtual threads for I/O-bound operations.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createProxy(final MavenProxyRepositoryConfig config) {
    // Use CompletableFuture with virtual threads for I/O-bound operations
    try {
      Configuration cfg = applyMavenAttributes(
          createProxyConfiguration(config), 
          config.getVersionPolicy(), 
          config.getLayoutPolicy());
      return CompletableFuture.supplyAsync(
          () -> doCreate(cfg),
          virtualThreadExecutor)
          .join();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create Maven proxy repository", e);
    }
  }

  /**
   * Creates a new Maven group repository configuration.
   *
   * @param name the repository name
   * @return the repository configuration
   */
  public MavenGroupRepositoryConfig group(final String name) {
    return new MavenGroupRepositoryConfig(this::createGroup)
        .withName(name);
  }

  /**
   * Creates a Maven group repository using virtual threads for I/O-bound operations.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createGroup(final MavenGroupRepositoryConfig config) {
    // Use CompletableFuture with virtual threads for I/O-bound operations
    try {
      return CompletableFuture.supplyAsync(
          () -> doCreate(applyMavenAttributes(
              createGroupConfiguration(config), 
              config.getVersionPolicy(), 
              config.getLayoutPolicy())),
          virtualThreadExecutor)
          .join();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create Maven group repository", e);
    }
  }

  /**
   * Applies Maven-specific attributes to the repository configuration.
   *
   * @param configuration the repository configuration
   * @param versionPolicy the version policy
   * @param layoutPolicy the layout policy
   * @return the updated configuration
   */
  private Configuration applyMavenAttributes(
      final Configuration configuration,
      final VersionPolicy versionPolicy,
      final LayoutPolicy layoutPolicy)
  {
    NestedAttributesMap maven = configuration.attributes(ATTRIBUTES_MAP_KEY_MAVEN);
    addConfigIfNotNull(maven, ATTRIBUTES_KEY_VERSION_POLICY, versionPolicy);
    addConfigIfNotNull(maven, ATTRIBUTES_KEY_LAYOUT_POLICY, layoutPolicy);
    return configuration;
  }
}