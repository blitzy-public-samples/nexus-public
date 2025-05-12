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
package org.sonatype.nexus.testsuite.testsupport.system;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testsuite.testsupport.system.repository.AptFormatRepositoryTestSystem;
import org.sonatype.nexus.testsuite.testsupport.system.repository.CocoapodsFormatRepositoryTestSystem;
import org.sonatype.nexus.testsuite.testsupport.system.repository.CondaFormatRepositoryTestSystem;
import org.sonatype.nexus.testsuite.testsupport.system.repository.FormatRepositoryTestSystem;
import org.sonatype.nexus.testsuite.testsupport.system.repository.GolangFormatRepositoryTestSystem;
import org.sonatype.nexus.testsuite.testsupport.system.repository.MavenFormatRepositoryTestSystem;
import org.sonatype.nexus.testsuite.testsupport.system.repository.RFormatRepositoryTestSystem;
import org.sonatype.nexus.testsuite.testsupport.system.repository.RawFormatRepositoryTestSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Streams.stream;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

/**
 * Repository test system that manages repository lifecycle for tests.
 * <p>
 * This implementation leverages Java 21 virtual threads for concurrent repository cleanup
 * operations, providing improved performance for I/O-bound repository operations.
 */
@FeatureFlag(name = "nexus.test.base")
@Named
@Singleton
public class RepositoryTestSystem
    extends TestSystemSupport
{
  public static final String FORMAT_APT = "apt";

  public static final String FORMAT_COCOAPODS = "cocoapods";

  public static final String FORMAT_CONDA = "conda";

  public static final String FORMAT_GOLANG = "go";

  public static final String FORMAT_MAVEN = "maven2";

  public static final String FORMAT_RAW = "raw";

  public static final String FORMAT_R = "r";

  private final Logger log = LoggerFactory.getLogger(getClass());

  final RepositoryManager repositoryManager;

  final Set<String> repositoriesBeforeTest = new HashSet<>();

  final Map<String, FormatRepositoryTestSystem> formatRepositoryTestSystemMap;

  /**
   * Default timeout for repository cleanup operations (in seconds).
   */
  private static final int CLEANUP_TIMEOUT_SECONDS = 60;

  @Inject
  public RepositoryTestSystem(
      final RepositoryManager repositoryManager,
      final EventManager eventManager,
      final Map<String, FormatRepositoryTestSystem> formatRepositoryTestSystemMap)
  {
    super(eventManager);
    this.repositoryManager = checkNotNull(repositoryManager);
    this.formatRepositoryTestSystemMap = checkNotNull(formatRepositoryTestSystemMap);
  }

  @Override
  protected void doBefore() {
    repositoriesBeforeTest.addAll(stream(repositoryManager.browse()).map(Repository::getName).collect(toList()));
  }

  @Override
  protected void doAfter() {
    // Get repositories that need to be deleted (created during test)
    List<Repository> repositoriesToDelete = stream(repositoryManager.browse())
        .filter(repository -> !repositoriesBeforeTest.contains(repository.getName()))
        .collect(toList());

    if (repositoriesToDelete.isEmpty()) {
      return; // No repositories to clean up
    }

    // Use virtual threads for concurrent repository deletion
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a CompletableFuture for each repository deletion task
      List<CompletableFuture<Void>> deletionTasks = repositoriesToDelete.stream()
          .map(repository -> CompletableFuture.runAsync(() -> {
            try {
              log.debug("Deleting repository {} using virtual thread", repository.getName());
              repositoryManager.delete(repository.getName());
            }
            catch (Exception e) {
              log.error("Attempt to auto-delete {} failed", repository.getName(), e);
            }
          }, executor))
          .collect(toList());

      // Wait for all deletion tasks to complete
      try {
        CompletableFuture.allOf(deletionTasks.toArray(new CompletableFuture[0]))
            .orTimeout(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .join();
        log.debug("Successfully cleaned up {} test repositories", repositoriesToDelete.size());
      }
      catch (Exception e) {
        log.error("Repository cleanup failed", e);
      }
    }
  }

  public AptFormatRepositoryTestSystem apt() {
    return (AptFormatRepositoryTestSystem) formatRepositoryTestSystemMap.get(FORMAT_APT);
  }

  public CocoapodsFormatRepositoryTestSystem cocoapods() {
    return (CocoapodsFormatRepositoryTestSystem) formatRepositoryTestSystemMap.get(FORMAT_COCOAPODS);
  }

  public CondaFormatRepositoryTestSystem conda() {
    return (CondaFormatRepositoryTestSystem) formatRepositoryTestSystemMap.get(FORMAT_CONDA);
  }

  public GolangFormatRepositoryTestSystem golang() {
    return (GolangFormatRepositoryTestSystem) formatRepositoryTestSystemMap.get(FORMAT_GOLANG);
  }

  public MavenFormatRepositoryTestSystem maven() {
    return (MavenFormatRepositoryTestSystem) formatRepositoryTestSystemMap.get(FORMAT_MAVEN);
  }

  public RawFormatRepositoryTestSystem raw() {
    return (RawFormatRepositoryTestSystem) formatRepositoryTestSystemMap.get(FORMAT_RAW);
  }

  public RFormatRepositoryTestSystem r() {
    return (RFormatRepositoryTestSystem) formatRepositoryTestSystemMap.get(FORMAT_R);
  }

  /**
   * Delete the specified repositories.
   *
   * @param repository the repositories to delete
   * @throws Exception if deletion fails
   */
  public void delete(final Repository ...repository) throws Exception {
    delete(Arrays.asList(repository));
  }

  /**
   * Delete the specified repositories.
   *
   * @param repositories the repositories to delete
   * @throws Exception if deletion fails
   */
  public void delete(final Iterable<Repository> repositories) throws Exception {
    for (Repository repository : repositories) {
      repositoryManager.delete(repository.getName());
    }
  }

  /**
   * Get all repositories.
   *
   * @return list of all repositories
   */
  public List<Repository> getRepositories() {
    return stream(repositoryManager.browse()).collect(toList());
  }

  /**
   * Get a repository by name.
   *
   * @param repositoryName the repository name
   * @return the repository or null if not found
   */
  public Repository getRepository(final String repositoryName) {
    return repositoryManager.get(repositoryName);
  }

  /**
   * Get repositories of the specified types.
   *
   * @param types the repository types to filter by (empty for all types)
   * @return list of matching repositories
   */
  public List<Repository> getRepositories(final Type... types) {
    return stream(repositoryManager.browse()).filter(repository -> isType(repository, types)).collect(toList());
  }

  /**
   * Get the format repository test system map.
   *
   * @return the format repository test system map
   */
  protected Map<String, FormatRepositoryTestSystem> getFormatRepositoryTestSystemMap() {
    return formatRepositoryTestSystemMap;
  }

  private boolean isType(final Repository repository, final Type... types) {
    return types == null || types.length == 0 || asList(types).contains(repository.getType());
  }
}