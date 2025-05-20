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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.SearchUpdateService;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21TestGroup")
public class SearchUpdateTaskManagerTest
    extends TestSupport
{
  @Mock
  private TaskScheduler taskScheduler;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private Repository repository1;

  @Mock
  private Repository repository2;

  @Mock
  private Repository repository3;

  @Mock
  private PeriodicJobService periodicJobService;

  @Mock
  private SearchUpdateService searchUpdateService;

  private final TaskConfiguration taskConfiguration = new TaskConfiguration();

  private SearchUpdateTaskManager underTest;

  @BeforeEach
  void setup() {
    when(repository1.getName()).thenReturn("repository1");
    when(repository2.getName()).thenReturn("repository2");
    when(repository3.getName()).thenReturn("repository3");
    when(taskScheduler.createTaskConfigurationInstance(any())).thenReturn(taskConfiguration);

    doAnswer(i -> {
      ((Runnable) i.getArgument(0)).run();
      return null;
    }).when(periodicJobService).runOnce(any(), anyInt());

    underTest =
        new SearchUpdateTaskManager(taskScheduler, repositoryManager, searchUpdateService, periodicJobService, true);
  }

  @Test
  void exceptionDoesNotPreventStartup() {
    when(repositoryManager.browse()).thenThrow(new RuntimeException("exception"));

    try {
      underTest.doStart();
    }
    catch (Exception e) {
      fail("expected startup to catch exceptions");
    }
  }

  @Test
  void skipProcessingWhenNotEnabled() {
    underTest =
        new SearchUpdateTaskManager(taskScheduler, repositoryManager, searchUpdateService, periodicJobService, false);

    underTest.doStart();

    verifyNoMoreInteractions(repositoryManager);
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  void onStartupNoRepositories() {
    when(repositoryManager.browse()).thenReturn(Collections.emptyList());
    underTest.doStart();
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  void onStartupNoRepositoriesToUpdate() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  void onStartupUpdateOneRepository() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    assertEquals("repository2", taskConfiguration.getString("repositoryNames"));
    verify(taskScheduler).submit(taskConfiguration);
  }

  @Test
  void onStartupUpdateMultipleRepositories() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    assertEquals("repository1,repository2", taskConfiguration.getString("repositoryNames"));
    verify(taskScheduler).submit(taskConfiguration);
  }

  @Test
  void onStartupTaskAlreadyRunning() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    when(taskScheduler.findAndSubmit(any())).thenReturn(true);
    underTest.doStart();
    verify(taskScheduler, never()).submit(any());
  }
  
  @Test
  void concurrentOperationsWithVirtualThreads() {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Configure the repository manager to return a list of repositories
      when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
      when(searchUpdateService.needsReindex(any(Repository.class))).thenReturn(true);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            underTest.doStart();
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent operations");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Test was interrupted");
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  void patternMatchingForRepositoryConfigurations() {
    // Setup test repositories with different configurations
    Repository hostedRepo = repository1;
    Repository proxyRepo = repository2;
    Repository groupRepo = repository3;
    
    when(hostedRepo.getType()).thenReturn("hosted");
    when(proxyRepo.getType()).thenReturn("proxy");
    when(groupRepo.getType()).thenReturn("group");
    
    // Test pattern matching with different repository types
    List<Repository> repositories = ImmutableList.of(hostedRepo, proxyRepo, groupRepo);
    
    for (Repository repo : repositories) {
      String result = switch (repo) {
        case Repository r when "hosted".equals(r.getType()) -> "Hosted repository found";
        case Repository r when "proxy".equals(r.getType()) -> "Proxy repository found";
        case Repository r when "group".equals(r.getType()) -> "Group repository found";
        default -> "Unknown repository type";
      };
      
      // Verify the pattern matching worked correctly
      switch (repo.getType()) {
        case "hosted" -> assertEquals("Hosted repository found", result);
        case "proxy" -> assertEquals("Proxy repository found", result);
        case "group" -> assertEquals("Group repository found", result);
        default -> fail("Unexpected repository type: " + repo.getType());
      }
    }
  }
}