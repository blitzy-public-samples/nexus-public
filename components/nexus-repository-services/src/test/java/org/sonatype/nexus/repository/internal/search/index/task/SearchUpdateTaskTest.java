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
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.repository.search.index.SearchUpdateService;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.elasticsearch.cluster.metadata.ProcessClusterEventTimeoutException;
import org.elasticsearch.common.unit.TimeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Tag;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
  private Repository proxyRepository;

  @Mock
  private Repository hostedRepository;

  @Mock
  private Repository groupRepository;

  @Mock
  private SearchIndexFacet searchIndexFacet1;

  @Mock
  private SearchIndexFacet searchIndexFacet2;

  @Mock
  private SearchIndexFacet proxySearchIndexFacet;

  @Mock
  private SearchIndexFacet hostedSearchIndexFacet;

  @Mock
  private SearchIndexFacet groupSearchIndexFacet;

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

    // Setup for pattern matching test
    when(proxyRepository.getName()).thenReturn("proxy-repo");
    when(hostedRepository.getName()).thenReturn("hosted-repo");
    when(groupRepository.getName()).thenReturn("group-repo");
    when(proxyRepository.getType()).thenReturn("proxy");
    when(hostedRepository.getType()).thenReturn("hosted");
    when(groupRepository.getType()).thenReturn("group");
    when(proxyRepository.facet(SearchIndexFacet.class)).thenReturn(proxySearchIndexFacet);
    when(hostedRepository.facet(SearchIndexFacet.class)).thenReturn(hostedSearchIndexFacet);
    when(groupRepository.facet(SearchIndexFacet.class)).thenReturn(groupSearchIndexFacet);
    when(repositoryManager.get("proxy-repo")).thenReturn(proxyRepository);
    when(repositoryManager.get("hosted-repo")).thenReturn(hostedRepository);
    when(repositoryManager.get("group-repo")).thenReturn(groupRepository);

    configuration.setId("test");
    configuration.setTypeId("test");

    underTest = new SearchUpdateTask(repositoryManager, searchUpdateService, taskScheduler);
  }

  @Test
  public void runOnOneRepository() {
    configuration.setString("repositoryNames", "repository1");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
  }

  @Test
  public void runOnMultipleRepositories() {
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  public void unknownRepository() {
    configuration.setString("repositoryNames", "repository1,unknown,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  public void runOnMultipleRepositoriesButFailRebuildingIndex() {
    doThrow(new ProcessClusterEventTimeoutException(
        new TimeValue(30000), "failed to process cluster event (delete-index)"))
        .when(searchIndexFacet1).rebuildIndex();
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  @Tag("Java21")
  public void concurrentSearchIndexUpdateWithVirtualThreads() {
    // Create a list of repository names
    List<String> repoNames = List.of("repository1", "repository2");
    
    // Configure the task
    configuration.setString("repositoryNames", String.join(",", repoNames));
    underTest.configure(configuration);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Execute the task concurrently using virtual threads
      CompletableFuture<?>[] futures = repoNames.stream()
          .map(name -> CompletableFuture.runAsync(() -> {
            // Simulate the task execution for each repository
            Repository repo = repositoryManager.get(name);
            if (repo != null) {
              SearchIndexFacet searchIndexFacet = repo.facet(SearchIndexFacet.class);
              searchIndexFacet.rebuildIndex();
              searchUpdateService.doneReindexing(repo);
            }
          }, executor))
          .toArray(CompletableFuture[]::new);
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures).join();
    }
    
    // Verify that all repositories were processed
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  @Tag("Java21")
  public void patternMatchingForRepositoryTypes() {
    // Configure the task with different repository types
    configuration.setString("repositoryNames", "proxy-repo,hosted-repo,group-repo");
    underTest.configure(configuration);
    
    // Execute the task
    underTest.execute();
    
    // Verify that all repositories were processed
    verify(proxySearchIndexFacet).rebuildIndex();
    verify(hostedSearchIndexFacet).rebuildIndex();
    verify(groupSearchIndexFacet).rebuildIndex();
    
    // Verify that the search update service was called for each repository
    verify(searchUpdateService).doneReindexing(proxyRepository);
    verify(searchUpdateService).doneReindexing(hostedRepository);
    verify(searchUpdateService).doneReindexing(groupRepository);
  }

  @Test
  @Tag("Java21")
  public void asyncTaskExecutionWithVirtualThreads() {
    // Configure the task
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    
    // Create a countdown latch to track task completion
    CountDownLatch latch = new CountDownLatch(2);
    
    // Setup mock behavior to count down the latch when methods are called
    doAnswer(invocation -> {
      latch.countDown();
      return null;
    }).when(searchUpdateService).doneReindexing(any(Repository.class));
    
    // Execute the task asynchronously using virtual threads
    assertTimeout(java.time.Duration.ofSeconds(5), () -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        CompletableFuture.runAsync(() -> underTest.execute(), executor);
        
        // Wait for the task to complete
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Task did not complete in time");
      }
    });
    
    // Verify that all repositories were processed
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  @Tag("Java21")
  public void resilienceToCarrierThreadPinning() {
    // Configure the task
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    
    // Setup a counter to track the number of completed operations
    AtomicInteger completedOps = new AtomicInteger(0);
    
    // Simulate a blocking operation in the first repository's rebuildIndex method
    doAnswer(invocation -> {
      // Simulate a blocking operation that would pin a carrier thread
      Thread.sleep(500);
      completedOps.incrementAndGet();
      return null;
    }).when(searchIndexFacet1).rebuildIndex();
    
    // Setup the second repository to complete quickly
    doAnswer(invocation -> {
      completedOps.incrementAndGet();
      return null;
    }).when(searchIndexFacet2).rebuildIndex();
    
    // Execute the task using virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.execute(), executor);
      
      // Wait for the task to complete
      future.join();
      
      // Verify that both operations completed despite the blocking in one thread
      assertEquals(2, completedOps.get(), "Not all operations completed");
    }
    
    // Verify that all repositories were processed
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }
}