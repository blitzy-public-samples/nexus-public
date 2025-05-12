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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.GroupRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.HostedRepositoryConfig;
import org.sonatype.nexus.testsuite.testsupport.system.repository.config.ProxyRepositoryConfig;

/**
 * Support class for repository format testing that provides methods for creating
 * hosted, proxy, and group repositories with a simple, fluent API.
 * <p>
 * This implementation supports both traditional synchronous repository creation
 * and Java 21 virtual thread-based asynchronous repository creation for improved
 * test performance with high concurrency.
 *
 * @param <HOSTED> the hosted repository configuration type
 * @param <PROXY> the proxy repository configuration type
 * @param <GROUP> the group repository configuration type
 * 
 * @since 3.0
 */
public abstract class SimpleFormatRepositoryTestSystemSupport
    <HOSTED extends HostedRepositoryConfig<?>,
        PROXY extends ProxyRepositoryConfig<?>,
        GROUP extends GroupRepositoryConfig<?>>
    extends FormatRepositoryTestSystemSupport<HOSTED, PROXY, GROUP>
{
  private final Class<HOSTED> hostedClass;

  private final Class<PROXY> proxyClass;

  private final Class<GROUP> groupClass;
  
  /**
   * Executor for virtual thread operations, using Java 21's virtual threads for
   * high-concurrency I/O operations with minimal overhead.
   */
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates a new instance with the specified repository manager and configuration classes.
   *
   * @param repositoryManager the repository manager
   * @param hostedClass the hosted repository configuration class
   * @param proxyClass the proxy repository configuration class
   * @param groupClass the group repository configuration class
   */
  public SimpleFormatRepositoryTestSystemSupport(
      final RepositoryManager repositoryManager,
      final Class<HOSTED> hostedClass,
      final Class<PROXY> proxyClass,
      final Class<GROUP> groupClass)
  {
    super(repositoryManager);
    this.hostedClass = hostedClass;
    this.proxyClass = proxyClass;
    this.groupClass = groupClass;
  }

  /**
   * Creates a hosted repository with the specified configuration.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createHosted(final HOSTED config) {
    return doCreate(createHostedConfiguration(config));
  }

  /**
   * Creates a proxy repository with the specified configuration.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createProxy(final PROXY config) {
    return doCreate(createProxyConfiguration(config));
  }

  /**
   * Creates a group repository with the specified configuration.
   *
   * @param config the repository configuration
   * @return the created repository
   */
  public Repository createGroup(final GROUP config) {
    return doCreate(createGroupConfiguration(config));
  }
  
  /**
   * Asynchronously creates a hosted repository with the specified configuration using virtual threads.
   * This method leverages Java 21 virtual threads for improved concurrency with minimal overhead.
   *
   * @param config the repository configuration
   * @return a CompletableFuture that will complete with the created repository
   * @since 3.60
   */
  public CompletableFuture<Repository> createHostedAsync(final HOSTED config) {
    return CompletableFuture.supplyAsync(
        () -> doCreate(createHostedConfiguration(config)),
        virtualThreadExecutor);
  }

  /**
   * Asynchronously creates a proxy repository with the specified configuration using virtual threads.
   * This method leverages Java 21 virtual threads for improved concurrency with minimal overhead.
   *
   * @param config the repository configuration
   * @return a CompletableFuture that will complete with the created repository
   * @since 3.60
   */
  public CompletableFuture<Repository> createProxyAsync(final PROXY config) {
    return CompletableFuture.supplyAsync(
        () -> doCreate(createProxyConfiguration(config)),
        virtualThreadExecutor);
  }

  /**
   * Asynchronously creates a group repository with the specified configuration using virtual threads.
   * This method leverages Java 21 virtual threads for improved concurrency with minimal overhead.
   *
   * @param config the repository configuration
   * @return a CompletableFuture that will complete with the created repository
   * @since 3.60
   */
  public CompletableFuture<Repository> createGroupAsync(final GROUP config) {
    return CompletableFuture.supplyAsync(
        () -> doCreate(createGroupConfiguration(config)),
        virtualThreadExecutor);
  }

  /**
   * Creates a hosted repository configuration with the specified name.
   *
   * @param name the repository name
   * @return the repository configuration
   */
  @SuppressWarnings("unchecked")
  public HOSTED hosted(final String name) {
    return (HOSTED) create(hostedClass, this::createHosted)
        .withName(name);
  }

  /**
   * Creates a proxy repository configuration with the specified name.
   *
   * @param name the repository name
   * @return the repository configuration
   */
  @SuppressWarnings("unchecked")
  public PROXY proxy(final String name) {
    return (PROXY) create(proxyClass, this::createProxy)
        .withName(name);
  }

  /**
   * Creates a group repository configuration with the specified name.
   *
   * @param name the repository name
   * @return the repository configuration
   */
  @SuppressWarnings("unchecked")
  public GROUP group(final String name) {
    return (GROUP) create(groupClass, this::createGroup)
        .withName(name);
  }
  
  /**
   * Creates a hosted repository configuration with the specified name,
   * configured for asynchronous creation using virtual threads.
   *
   * @param name the repository name
   * @return the repository configuration
   * @since 3.60
   */
  @SuppressWarnings("unchecked")
  public HOSTED hostedAsync(final String name) {
    return (HOSTED) create(hostedClass, this::createHostedAsync)
        .withName(name);
  }

  /**
   * Creates a proxy repository configuration with the specified name,
   * configured for asynchronous creation using virtual threads.
   *
   * @param name the repository name
   * @return the repository configuration
   * @since 3.60
   */
  @SuppressWarnings("unchecked")
  public PROXY proxyAsync(final String name) {
    return (PROXY) create(proxyClass, this::createProxyAsync)
        .withName(name);
  }

  /**
   * Creates a group repository configuration with the specified name,
   * configured for asynchronous creation using virtual threads.
   *
   * @param name the repository name
   * @return the repository configuration
   * @since 3.60
   */
  @SuppressWarnings("unchecked")
  public GROUP groupAsync(final String name) {
    return (GROUP) create(groupClass, this::createGroupAsync)
        .withName(name);
  }

  /**
   * Creates a repository configuration instance using the specified factory.
   *
   * @param <E> the repository configuration type
   * @param clazz the repository configuration class
   * @param factory the factory function for creating repositories
   * @return the repository configuration
   */
  private static <E, R> E create(final Class<E> clazz, final Function<E, R> factory) {
    try {
      return clazz.getConstructor(Function.class).newInstance(factory);
    }
    catch (Exception e) {
      throw new RuntimeException("Failed to create repository configuration instance", e);
    }
  }
}