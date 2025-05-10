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
package org.sonatype.nexus.repository.internal.search.index.task;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.repository.search.index.SearchUpdateService;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;

import org.elasticsearch.cluster.metadata.ProcessClusterEventTimeoutException;
import org.elasticsearch.common.unit.TimeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SearchUpdateTaskTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private SearchUpdateService searchUpdateService;

  @Mock
  private Repository repository1;

  @Mock
  private Repository repository2;

  @Mock
  private SearchIndexFacet searchIndexFacet1;

  @Mock
  private SearchIndexFacet searchIndexFacet2;

  @Mock
  private TaskScheduler taskScheduler;

  private final TaskConfiguration configuration = new TaskConfiguration();

  private SearchUpdateTask underTest;

  @BeforeEach
  public void setup() {
    when(repository1.getName()).thenReturn("repository1");
    when(repository2.getName()).thenReturn("repository1");
    when(repository1.facet(SearchIndexFacet.class)).thenReturn(searchIndexFacet1);
    when(repository2.facet(SearchIndexFacet.class)).thenReturn(searchIndexFacet2);
    when(repositoryManager.get("repository1")).thenReturn(repository1);
    when(repositoryManager.get("repository2")).thenReturn(repository2);

    configuration.setId("test");
    configuration.setTypeId("test");

    underTest = new SearchUpdateTask(repositoryManager, searchUpdateService, taskScheduler);
  }

  @Test
  public void shouldRebuildIndexWhenRunOnOneRepository() {
    configuration.setString("repositoryNames", "repository1");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
  }

  @Test
  public void shouldRebuildIndexWhenRunOnMultipleRepositories() {
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  public void shouldIgnoreUnknownRepositoryWhenExecuting() {
    configuration.setString("repositoryNames", "repository1,unknown,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  public void shouldContinueExecutionWhenOneRepositoryFailsRebuildingIndex() {
    doThrow(new ProcessClusterEventTimeoutException(
        new TimeValue(30000), "failed to process cluster event (delete-index)"))
        .when(searchIndexFacet1).rebuildIndex();
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository2);
  }

  /**
   * Test to verify that SearchUpdateTask can handle virtual threads properly
   * when executing with high concurrency.
   */
  @Test
  @org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
  public void shouldHandleConcurrentIndexUpdatesWithVirtualThreads() throws Exception {
    // Setup repositories for testing
    Repository[] repositories = new Repository[10];
    SearchIndexFacet[] facets = new SearchIndexFacet[10];
    
    for (int i = 0; i < 10; i++) {
      Repository repo = org.mockito.Mockito.mock(Repository.class);
      SearchIndexFacet facet = org.mockito.Mockito.mock(SearchIndexFacet.class);
      String repoName = "virtual-repo-" + i;
      
      when(repo.getName()).thenReturn(repoName);
      when(repo.facet(SearchIndexFacet.class)).thenReturn(facet);
      when(repositoryManager.get(repoName)).thenReturn(repo);
      
      repositories[i] = repo;
      facets[i] = facet;
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 50;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      for (int i = 0; i < taskCount; i++) {
        final int index = i % 10; // Cycle through the repositories
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Configure a task for this repository
            TaskConfiguration config = new TaskConfiguration();
            config.setId("virtual-test-" + index);
            config.setTypeId("test");
            config.setString("repositoryNames", repositories[index].getName());
            
            SearchUpdateTask task = new SearchUpdateTask(repositoryManager, searchUpdateService, taskScheduler);
            task.configure(config);
            task.execute();
            
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "All tasks should complete within the timeout period");
      assertEquals(taskCount, successCount.get(), "All tasks should complete successfully");
      
      // Verify that each repository's index was rebuilt the expected number of times
      for (int i = 0; i < 10; i++) {
        verify(facets[i], times(taskCount / 10)).rebuildIndex();
        verify(searchUpdateService, times(taskCount / 10)).doneReindexing(repositories[i]);
      }
    }
  }

  /**
   * Test to verify that Elasticsearch operations maintain thread continuity
   * during both normal operation and error handling with Java 21 virtual threads.
   */
  @Test
  @org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
  public void shouldMaintainThreadContinuityWithElasticsearchOperations() throws Exception {
    // Setup test repositories with different behaviors
    Repository successRepo = org.mockito.Mockito.mock(Repository.class);
    Repository failureRepo = org.mockito.Mockito.mock(Repository.class);
    SearchIndexFacet successFacet = org.mockito.Mockito.mock(SearchIndexFacet.class);
    SearchIndexFacet failureFacet = org.mockito.Mockito.mock(SearchIndexFacet.class);
    
    when(successRepo.getName()).thenReturn("success-repo");
    when(failureRepo.getName()).thenReturn("failure-repo");
    when(successRepo.facet(SearchIndexFacet.class)).thenReturn(successFacet);
    when(failureRepo.facet(SearchIndexFacet.class)).thenReturn(failureFacet);
    when(repositoryManager.get("success-repo")).thenReturn(successRepo);
    when(repositoryManager.get("failure-repo")).thenReturn(failureRepo);
    
    // Configure the failure repository to throw an exception
    doThrow(new ProcessClusterEventTimeoutException(
        new TimeValue(5000), "simulated failure for virtual thread test"))
        .when(failureFacet).rebuildIndex();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(2);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger failureHandledCount = new AtomicInteger(0);
      
      // Test successful repository
      CompletableFuture<?> successFuture = CompletableFuture.runAsync(() -> {
        try {
          TaskConfiguration config = new TaskConfiguration();
          config.setId("success-test");
          config.setTypeId("test");
          config.setString("repositoryNames", "success-repo");
          
          SearchUpdateTask task = new SearchUpdateTask(repositoryManager, searchUpdateService, taskScheduler);
          task.configure(config);
          task.execute();
          
          successCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, executor);
      
      // Test failure repository
      CompletableFuture<?> failureFuture = CompletableFuture.runAsync(() -> {
        try {
          TaskConfiguration config = new TaskConfiguration();
          config.setId("failure-test");
          config.setTypeId("test");
          config.setString("repositoryNames", "failure-repo");
          
          SearchUpdateTask task = new SearchUpdateTask(repositoryManager, searchUpdateService, taskScheduler);
          task.configure(config);
          task.execute(); // Should handle the exception internally
          
          failureHandledCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, executor);
      
      // Wait for both tasks to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "All tasks should complete within the timeout period");
      
      // Verify results
      assertEquals(1, successCount.get(), "Success task should complete successfully");
      assertEquals(1, failureHandledCount.get(), "Failure task should handle the exception and complete");
      
      // Verify interactions
      verify(successFacet).rebuildIndex();
      verify(searchUpdateService).doneReindexing(successRepo);
      verify(failureFacet).rebuildIndex();
      verify(searchUpdateService, never()).doneReindexing(failureRepo); // Should not be called due to exception
    }
  }

  /**
   * Test to verify proper behavior when SearchUpdateTask is executed with high concurrency
   * using virtual threads, processing repositories using record patterns.
   */
  @Test
  @org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
  public void shouldHandleHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a list of repository data using records for pattern matching
    record RepositoryData(String name, Repository repository, SearchIndexFacet facet, boolean shouldFail) {}
    
    List<RepositoryData> repositoryDataList = List.of(
        new RepositoryData("repo-1", org.mockito.Mockito.mock(Repository.class), org.mockito.Mockito.mock(SearchIndexFacet.class), false),
        new RepositoryData("repo-2", org.mockito.Mockito.mock(Repository.class), org.mockito.Mockito.mock(SearchIndexFacet.class), true),
        new RepositoryData("repo-3", org.mockito.Mockito.mock(Repository.class), org.mockito.Mockito.mock(SearchIndexFacet.class), false),
        new RepositoryData("repo-4", org.mockito.Mockito.mock(Repository.class), org.mockito.Mockito.mock(SearchIndexFacet.class), false),
        new RepositoryData("repo-5", org.mockito.Mockito.mock(Repository.class), org.mockito.Mockito.mock(SearchIndexFacet.class), true)
    );
    
    // Setup repositories using record pattern matching
    for (RepositoryData data : repositoryDataList) {
      // Using record pattern matching (Java 21 feature)
      if (data instanceof RepositoryData(String name, Repository repository, SearchIndexFacet facet, boolean shouldFail)) {
        when(repository.getName()).thenReturn(name);
        when(repository.facet(SearchIndexFacet.class)).thenReturn(facet);
        when(repositoryManager.get(name)).thenReturn(repository);
        
        if (shouldFail) {
          doThrow(new ProcessClusterEventTimeoutException(
              new TimeValue(3000), "simulated failure for high concurrency test"))
              .when(facet).rebuildIndex();
        }
      }
    }
    
    // Create a virtual thread executor with high concurrency
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 100; // High concurrency
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger completedCount = new AtomicInteger(0);
      
      // Submit tasks with high concurrency
      for (int i = 0; i < taskCount; i++) {
        final int index = i % repositoryDataList.size();
        CompletableFuture.runAsync(() -> {
          try {
            // Get repository data using pattern matching
            RepositoryData data = repositoryDataList.get(index);
            if (data instanceof RepositoryData(String name, Repository repository, SearchIndexFacet facet, boolean shouldFail)) {
              // Configure and execute task
              TaskConfiguration config = new TaskConfiguration();
              config.setId("concurrent-test-" + name);
              config.setTypeId("test");
              config.setString("repositoryNames", name);
              
              SearchUpdateTask task = new SearchUpdateTask(repositoryManager, searchUpdateService, taskScheduler);
              task.configure(config);
              task.execute();
              
              completedCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "All tasks should complete within the timeout period");
      assertEquals(taskCount, completedCount.get(), "All tasks should complete, even those with failures");
      
      // Verify interactions for each repository using pattern matching
      for (RepositoryData data : repositoryDataList) {
        if (data instanceof RepositoryData(String name, Repository repository, SearchIndexFacet facet, boolean shouldFail)) {
          // Calculate expected invocation count for this repository
          int expectedCount = taskCount / repositoryDataList.size();
          
          // Verify rebuildIndex was called the expected number of times
          verify(facet, times(expectedCount)).rebuildIndex();
          
          // Verify doneReindexing was called only for successful repositories
          if (!shouldFail) {
            verify(searchUpdateService, times(expectedCount)).doneReindexing(repository);
          } else {
            verify(searchUpdateService, never()).doneReindexing(repository);
          }
        }
      }
    }
  }
}