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
package org.sonatype.nexus.testsuite.testsupport.raw;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.testsuite.testsupport.system.RestTestHelper;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Abstract helper class for Raw repository testing.
 * <p>
 * This class provides methods to create RawClient instances for interacting with Raw repositories
 * and defines abstract methods that must be implemented by concrete subclasses to perform
 * repository-specific operations.
 * <p>
 * This implementation leverages Java 21 virtual threads for I/O-bound operations to improve
 * performance and scalability during testing. Virtual threads provide high concurrency with minimal
 * resource overhead, making them ideal for repository testing where many concurrent operations may be needed.
 *
 * @since 3.60.0
 */
public abstract class RawTestHelper
{
  @Inject
  private RestTestHelper rest;
  
  /**
   * Virtual thread executor for I/O-bound operations.
   * This executor creates a new virtual thread for each submitted task, allowing for high concurrency
   * with minimal resource overhead. Virtual threads are particularly well-suited for I/O operations
   * like repository reads and writes.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Create a {@link RawClient} for the given repository, using default admin credentials.
   * 
   * @param repository the repository to create a client for
   * @return a configured RawClient instance
   * @throws Exception if client creation fails
   */
  public RawClient rawClient(final Repository repository) throws Exception {
    checkNotNull(repository);
    return rawClient("/repository/" + repository.getName() + "/", "admin", "admin123");
  }

  /**
   * Create a {@link RawClient} for the given repository using the provided authentication.
   * 
   * @param repository the repository to create a client for
   * @param username the username for authentication
   * @param password the password for authentication
   * @return a configured RawClient instance
   * @throws Exception if client creation fails
   */
  public RawClient rawClient(final Repository repository, final String username, final String password) throws Exception {
    checkNotNull(repository);
    return rawClient("/repository/" + repository.getName() + "/", username, password);
  }

  /**
   * Create a {@link RawClient} for the given path using the provided authentication.
   * 
   * @param path the repository path
   * @param username the username for authentication
   * @param password the password for authentication
   * @return a configured RawClient instance
   * @throws Exception if client creation fails
   */
  public RawClient rawClient(final String path, final String username, final String password) throws Exception {
    return new RawClient(
        rest.client(path, username, password),
        rest.clientContext(),
        rest.resolveNexusPath(path)
    );
  }

  /**
   * Asynchronously read content from a repository using virtual threads.
   * This method provides a non-blocking way to read content from a repository.
   * 
   * @param repository the repository to read from
   * @param path the path to read
   * @return a CompletableFuture that will complete with the content or null if not found
   */
  public CompletableFuture<Content> readAsync(final Repository repository, final String path) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return read(repository, path);
      } catch (IOException e) {
        throw new RuntimeException("Failed to read content from repository", e);
      }
    }, virtualThreadExecutor);
  }

  /**
   * Read content from a repository.
   * 
   * @param repository the repository to read from
   * @param path the path to read
   * @return the content or null if not found
   * @throws IOException if an I/O error occurs
   */
  public abstract Content read(Repository repository, String path) throws IOException;

  /**
   * Assert that a raw component exists in the repository.
   * 
   * @param repository the repository to check
   * @param path the path of the component
   * @param group the group of the component
   */
  public abstract void assertRawComponent(Repository repository, String path, String group);

  /**
   * Create an asset in the repository.
   * 
   * @param repository the repository to create the asset in
   * @param componentName the name of the component
   * @param componentGroup the group of the component
   * @param assetName the name of the asset
   * @return the ID of the created asset
   */
  public abstract EntityId createAsset(Repository repository, String componentName, String componentGroup, String assetName);
  
  /**
   * Asynchronously create an asset in the repository using virtual threads.
   * This method provides a non-blocking way to create assets, which is particularly useful
   * for tests that need to create many assets concurrently.
   * 
   * @param repository the repository to create the asset in
   * @param componentName the name of the component
   * @param componentGroup the group of the component
   * @param assetName the name of the asset
   * @return a CompletableFuture that will complete with the ID of the created asset
   */
  public CompletableFuture<EntityId> createAssetAsync(final Repository repository, 
                                                     final String componentName, 
                                                     final String componentGroup, 
                                                     final String assetName) {
    return CompletableFuture.supplyAsync(
        () -> createAsset(repository, componentName, componentGroup, assetName),
        virtualThreadExecutor
    );
  }
}