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

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Provider

import org.sonatype.nexus.common.app.BaseUrlHolder
import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.config.Configuration
import org.sonatype.nexus.repository.manager.RepositoryManager

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.junit.rules.ExternalResource

import static com.google.common.base.Preconditions.checkNotNull

/**
 * JUnit rule for managing test repositories.
 * <p>
 * This class provides functionality to create and track repositories during tests,
 * automatically cleaning them up after test execution.
 * <p>
 * Compatible with Java 21 and leverages Virtual Threads for improved performance
 * in repository operations when running on Java 21.
 *
 * @since 3.60.0 Updated for Java 21 compatibility with Virtual Thread support
 */
@Slf4j
@CompileStatic
class RepositoryRule
    extends ExternalResource
    implements MavenRepoRecipes, RawRepoRecipes, AptRepoRecipes, GolangRepoRecipes,
        CocoapodsRepoRecipes, CondaRepoRecipes
{
  Provider<RepositoryManager> repositoryManagerProvider

  final List<Repository> repositories = []
  
  /**
   * ExecutorService using Virtual Threads when running on Java 21+, or a standard thread pool otherwise.
   * Used for concurrent repository operations to improve performance.
   */
  private final ExecutorService executorService
  
  /**
   * Creates a new RepositoryRule with the specified repository manager provider.
   * Initializes the executor service with Virtual Threads if running on Java 21+.
   *
   * @param repositoryManagerProvider the provider for the repository manager
   */
  RepositoryRule(final Provider<RepositoryManager> repositoryManagerProvider) {
    this.repositoryManagerProvider = checkNotNull(repositoryManagerProvider)
    // Use Virtual Threads if running on Java 21+
    this.executorService = createExecutorService()
  }
  
  /**
   * Creates an appropriate ExecutorService based on the Java version.
   * Uses Virtual Threads on Java 21+ for improved performance with I/O operations.
   *
   * @return an ExecutorService instance
   */
  private ExecutorService createExecutorService() {
    try {
      // Try to use Virtual Threads (Java 21+)
      return Executors.newVirtualThreadPerTaskExecutor()
    } catch (Exception e) {
      // Fall back to a standard thread pool on older Java versions
      log.debug('Virtual Threads not available, using standard thread pool', e)
      return Executors.newCachedThreadPool()
    }
  }

  /**
   * Browse all repositories in the repository manager.
   *
   * @return an iterable of all repositories
   */
  Iterable<Repository> browse() {
    return repositoryManagerProvider.get().browse()
  }

  /**
   * Cleanup method called after test execution.
   * Deletes all tracked repositories concurrently using Virtual Threads when available.
   */
  @Override
  public void after() {
    def repositoryManager = repositoryManagerProvider.get()
    try {
      // Create a list of futures for concurrent repository deletion
      def futures = repositories.collect { Repository repository ->
        CompletableFuture.runAsync(() -> {
          if (repositoryManager.exists(repository.name)) {
            log.debug 'Deleting test repository: {}', repository.name
            try {
              repositoryManager.delete(repository.name)
            }
            catch (Exception e) {
              log.info("Failed to remove repository {}", repository.name, e)
            }
          }
        }, executorService)
      }
      
      // Wait for all deletions to complete
      CompletableFuture.allOf(futures as CompletableFuture[]).join()
    } finally {
      repositories.clear()
    }
  }

  /**
   * Create a repository that will automatically be deleted at the end of a test.
   * 
   * @param configuration the repository configuration
   * @return the created repository
   */
  @Override
  Repository createRepository(final Configuration configuration) {
    log.debug 'Creating and tracking new Repository: {}', configuration.repositoryName

    boolean baseUrlSet = BaseUrlHolder.isSet()

    try {
      if (!baseUrlSet) {
        BaseUrlHolder.set('http://localhost:1234', '')
      }
      Repository repository = repositoryManagerProvider.get().create(configuration)
      repositories << repository
      return repository
    }
    finally {
      if (!baseUrlSet) {
        BaseUrlHolder.unset()
      }
    }
  }

  /**
   * Delete a Repository previously created by this class.
   * 
   * @param repository the repository to delete
   */
  void deleteRepository(Repository repository) {
    assert repositories.remove(repository)
    log.debug 'Deleting test repository: {}', repository.name
    CompletableFuture.runAsync(() -> {
      repositoryManagerProvider.get().delete(repository.name)
    }, executorService).join() // Wait for completion
  }
  
  /**
   * Shutdown hook to clean up resources when this rule is no longer needed.
   * Ensures the executor service is properly shut down.
   */
  @Override
  protected void finalize() throws Throwable {
    try {
      if (executorService != null && !executorService.isShutdown()) {
        executorService.shutdown()
      }
    } finally {
      super.finalize()
    }
  }
}
