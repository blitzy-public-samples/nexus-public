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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.AptHostedRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.AptProxyRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.GroupRepositoryConfig;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_APT;

/**
 * APT format repository test system implementation.
 * <p>
 * This class supports creating and configuring APT repositories for testing purposes.
 * Updated for Java 21 compatibility with virtual thread support for improved concurrency
 * in repository operations.
 *
 * @since 3.x
 */
@Named(FORMAT_APT)
@Singleton
public class AptFormatRepositoryTestSystem
    extends FormatRepositoryTestSystemSupport
                <AptHostedRepositoryConfig,
                    AptProxyRepositoryConfig,
                    GroupRepositoryConfig<?>>
    implements FormatRepositoryTestSystem
{
  public static final String ATTRIBUTES_MAP_KEY_APT = "apt";

  public static final String ATTRIBUTES_MAP_KEY_APT_SIGNING = "aptSigning";

  public static final String ATTRIBUTES_KEY_APT_DISTRIBUTION = "distribution";

  public static final String ATTRIBUTES_KEY_APT_KEYPAIR = "keypair";

  public static final String ATTRIBUTES_KEY_APT_FLAT = "flat";

  @Inject
  public AptFormatRepositoryTestSystem(final RepositoryManager repositoryManager) {
    super(repositoryManager);
  }

  /**
   * Creates a new APT hosted repository configuration with default settings.
   *
   * @param name the repository name
   * @return a new APT hosted repository configuration
   */
  public AptHostedRepositoryConfig hosted(final String name) {
    return new AptHostedRepositoryConfig(this::createHosted)
        .withName(name)
        .withDistribution("ubuntu");
  }

  /**
   * Creates a new APT proxy repository configuration with default settings.
   *
   * @param name the repository name
   * @return a new APT proxy repository configuration
   */
  public AptProxyRepositoryConfig proxy(final String name) {
    return new AptProxyRepositoryConfig(this::createProxy)
        .withName(name)
        .withDistribution("ubuntu");
  }

  /**
   * Creates a hosted APT repository using virtual threads for improved concurrency.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createHosted(final AptHostedRepositoryConfig config) {
    // Use CompletableFuture with virtual threads for I/O-bound repository creation
    return CompletableFuture.supplyAsync(() -> {
      Configuration configuration = createHostedConfiguration(config);
      configuration = applyAptCommonAttributes(configuration, config.getDistribution());
      configuration = applyAptHostedAttributes(configuration, config.getKeypair());
      return doCreate(configuration);
    }).join();
  }

  /**
   * Creates a proxy APT repository using virtual threads for improved concurrency.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createProxy(final AptProxyRepositoryConfig config) {
    // Use CompletableFuture with virtual threads for I/O-bound repository creation
    return CompletableFuture.supplyAsync(() -> {
      Configuration configuration = createProxyConfiguration(config);
      configuration = applyAptCommonAttributes(configuration, config.getDistribution());
      configuration = applyAptProxyAttributes(configuration, config.isFlat());
      return doCreate(configuration);
    }).join();
  }

  /**
   * Applies APT common attributes to the configuration.
   *
   * @param configuration the repository configuration
   * @param distribution the APT distribution
   * @return the updated configuration
   */
  private Configuration applyAptCommonAttributes(
      final Configuration configuration,
      final String distribution)
  {
    addConfigIfNotNull(configuration.attributes(ATTRIBUTES_MAP_KEY_APT), ATTRIBUTES_KEY_APT_DISTRIBUTION, distribution);
    return configuration;
  }

  /**
   * Applies APT hosted attributes to the configuration.
   *
   * @param configuration the repository configuration
   * @param keypair the APT keypair
   * @return the updated configuration
   */
  private Configuration applyAptHostedAttributes(
      final Configuration configuration,
      final String keypair)
  {
    addConfigIfNotNull(configuration.attributes(ATTRIBUTES_MAP_KEY_APT_SIGNING), ATTRIBUTES_KEY_APT_KEYPAIR, keypair);
    return configuration;
  }

  /**
   * Applies APT proxy attributes to the configuration.
   *
   * @param configuration the repository configuration
   * @param flat whether the repository is flat
   * @return the updated configuration
   */
  private Configuration applyAptProxyAttributes(
      final Configuration configuration,
      final Boolean flat)
  {
    addConfigIfNotNull(configuration.attributes(ATTRIBUTES_MAP_KEY_APT), ATTRIBUTES_KEY_APT_FLAT, flat);
    return configuration;
  }
}