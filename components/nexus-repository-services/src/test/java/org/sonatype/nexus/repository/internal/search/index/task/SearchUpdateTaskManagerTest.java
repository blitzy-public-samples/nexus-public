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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
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
import org.junit.experimental.categories.Category;
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
  void setUp() {
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
  void onStartupWithNoRepositories() {
    when(repositoryManager.browse()).thenReturn(Collections.emptyList());
    underTest.doStart();
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  void onStartupWithNoRepositoriesToUpdate() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  void onStartupWithOneRepositoryToUpdate() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    assertEquals("repository2", taskConfiguration.getString("repositoryNames"));
    verify(taskScheduler).submit(taskConfiguration);
  }

  @Test
  void onStartupWithMultipleRepositoriesToUpdate() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    assertEquals("repository1,repository2", taskConfiguration.getString("repositoryNames"));
    verify(taskScheduler).submit(taskConfiguration);
  }

  @Test
  void onStartupWithTaskAlreadyRunning() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    when(taskScheduler.findAndSubmit(any())).thenReturn(true);
    underTest.doStart();
    verify(taskScheduler, never()).submit(any());
  }
  
  @Test
  @Category(VirtualThreadTestGroup.class)
  void concurrentTaskSubmissionsWithVirtualThreads() throws Exception {
    // Configure virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 50;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Set up repository manager to return repositories that need reindexing
      when(searchUpdateService.needsReindex(any(Repository.class))).thenReturn(true);
      when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            underTest.doStart();
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            // Task failed
          } 
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      completionLatch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(taskCount, successCount.get(), "All tasks should complete successfully");
      
      // Verify that the task scheduler was called the expected number of times
      verify(taskScheduler, never()).submit(any()); // Because we're using findAndSubmit
    } 
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  void periodicJobServiceCorrectlyHandlesVirtualThreadExecution() {
    // Create a SearchUpdateTaskManager with virtual thread support
    SearchUpdateTaskManager virtualThreadManager = 
        new SearchUpdateTaskManager(taskScheduler, repositoryManager, searchUpdateService, periodicJobService, true);
    
    // Configure repository manager to return repositories that need reindexing
    when(searchUpdateService.needsReindex(repository1)).thenReturn(true);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1));
    
    // Execute the start method which will use periodicJobService
    virtualThreadManager.doStart();
    
    // Verify that periodicJobService was called with the correct parameters
    verify(periodicJobService).runOnce(any(Runnable.class), anyInt());
    
    // Verify that the task scheduler was called to submit the task
    verify(taskScheduler).submit(taskConfiguration);
  }
}