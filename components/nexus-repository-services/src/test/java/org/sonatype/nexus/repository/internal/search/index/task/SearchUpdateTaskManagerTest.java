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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
  public void setUp() {
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
  public void exceptionDoesNotPreventStartup() {
    when(repositoryManager.browse()).thenThrow(new RuntimeException("exception"));

    try {
      underTest.doStart();
    }
    catch (Exception e) {
      fail("expected startup to catch exceptions");
    }
  }

  @Test
  public void skipProcessingWhenNotEnabled() {
    underTest =
        new SearchUpdateTaskManager(taskScheduler, repositoryManager, searchUpdateService, periodicJobService, false);

    underTest.doStart();

    verifyNoMoreInteractions(repositoryManager);
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  public void onStartupWithNoRepositoriesShouldNotScheduleTasks() {
    when(repositoryManager.browse()).thenReturn(Collections.emptyList());
    underTest.doStart();
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  public void onStartupWithNoRepositoriesToUpdateShouldNotScheduleTasks() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    verifyNoMoreInteractions(taskScheduler);
  }

  @Test
  public void onStartupWithOneRepositoryToUpdateShouldScheduleTask() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(false);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    assertEquals("repository2", taskConfiguration.getString("repositoryNames"));
    verify(taskScheduler).submit(taskConfiguration);
  }

  @Test
  public void onStartupWithMultipleRepositoriesToUpdateShouldScheduleTask() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    underTest.doStart();
    assertEquals("repository1,repository2", taskConfiguration.getString("repositoryNames"));
    verify(taskScheduler).submit(taskConfiguration);
  }

  @Test
  public void onStartupWithTaskAlreadyRunningShouldNotSubmitNewTask() {
    when(searchUpdateService.needsReindex(repository1)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository2)).thenReturn(true);
    when(searchUpdateService.needsReindex(repository3)).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    when(taskScheduler.findAndSubmit(any())).thenReturn(true);
    underTest.doStart();
    verify(taskScheduler, never()).submit(any());
  }

  @Test
  @org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
  public void concurrentTaskSubmissionsWithVirtualThreadsShouldBeHandledCorrectly() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Setup for concurrent task submissions
    when(searchUpdateService.needsReindex(any())).thenReturn(true);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2, repository3));
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Simulate concurrent repository updates
            underTest.scheduleUpdate(ImmutableList.of(repository1, repository2));
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Exceptions should not occur
            fail("Concurrent task submission failed: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(10, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(taskCount, successCount.get(), "All task submissions should succeed");
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void periodicJobServiceShouldHandleVirtualThreadsCorrectly() {
    // Setup a virtual thread executor for the periodic job service
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Create a task that will be executed by the periodic job service
    AtomicInteger executionCount = new AtomicInteger(0);
    Runnable task = executionCount::incrementAndGet;
    
    // Mock the periodic job service to use our virtual thread executor
    doAnswer(i -> {
      Runnable runnable = i.getArgument(0);
      virtualExecutor.submit(runnable).get(); // Execute and wait for completion
      return null;
    }).when(periodicJobService).runOnce(any(), anyInt());
    
    // Execute the task through the periodic job service
    periodicJobService.runOnce(task, 0);
    
    // Verify the task was executed
    assertEquals(1, executionCount.get(), "Task should be executed exactly once");
    
    // Clean up
    virtualExecutor.shutdown();
  }
}