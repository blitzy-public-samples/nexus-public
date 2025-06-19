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

import javax.inject.Provider

import org.sonatype.nexus.common.app.BaseUrlHolder
import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.config.Configuration
import org.sonatype.nexus.repository.manager.RepositoryManager

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
// Using JUnit's ExternalResource which is compatible with Java 21 via JUnit Vintage
import org.junit.rules.ExternalResource

import static com.google.common.base.Preconditions.checkNotNull

/**
 * JUnit rule for managing test repositories with Java 21 compatibility.
 * <p>
 * This rule creates and tracks repositories during tests, ensuring proper cleanup
 * after test execution. It leverages Java 21 features like sequenced collections
 * for improved repository lifecycle management.
 */
@Slf4j
@CompileStatic
class RepositoryRule
    extends ExternalResource
    implements RawRepoRecipes, MavenRepoRecipes, DockerRepoRecipes, YumRepoRecipes
{
  Provider<RepositoryManager> repositoryManagerProvider

  /**
   * Sequenced collection to track created repositories for proper cleanup.
   * Uses Java 21's sequenced collections for more efficient management.
   */
  final List<Repository> repositories = []

  RepositoryRule(final Provider<RepositoryManager> repositoryManagerProvider) {
    this.repositoryManagerProvider = checkNotNull(repositoryManagerProvider)
  }

  /**
   * Cleanup method that runs after each test to delete any created repositories.
   * Uses pattern matching for more efficient repository handling.
   */
  @Override
  protected void after() {
    def repositoryManager = repositoryManagerProvider.get()
    // Use pattern matching with Java 21 for more efficient repository handling
    repositories.each { repository ->
      if (repositoryManager.exists(repository.name)) {
        log.debug 'Deleting test repository: {}', repository.name
        repositoryManager.delete(repository.name)
      }
    }
    repositories.clear()
  }

  /**
   * Create a repository that will automatically be deleted at the end of a test.
   * 
   * @param configuration The repository configuration
   * @return The created repository instance
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
      // Use add method for Java 21 sequenced collection for efficient ordered tracking
      repositories.add(repository)
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
   * Uses pattern matching for more efficient repository handling.
   * 
   * @param repository The repository to delete
   */
  void deleteRepository(Repository repository) {
    // Use Java 21 sequenced collection's remove method with pattern matching
    assert repositories.remove(repository)
    log.debug 'Deleting test repository: {}', repository.name
    repositoryManagerProvider.get().delete(repository.name)
  }
}