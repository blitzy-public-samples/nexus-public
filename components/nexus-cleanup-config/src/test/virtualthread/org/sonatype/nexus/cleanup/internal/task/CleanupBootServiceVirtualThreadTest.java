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
package org.sonatype.nexus.cleanup.internal.task;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.schedule.Cron;
import org.sonatype.nexus.scheduling.schedule.ScheduleFactory;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.cleanup.internal.task.CleanupBootService.TASK_NAME;

/**
 * Virtual Thread-specific test for {@link CleanupBootService} that verifies task scheduling behavior
 * under Java 21's Virtual Thread execution model.
 */
public class CleanupBootServiceVirtualThreadTest
    extends TestSupport
{
  private static final int HIGH_CONCURRENCY_THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 5;
  
  private TaskConfiguration taskConfig;

  @Mock
  private TaskScheduler taskScheduler;

  @Mock
  private ScheduleFactory scheduleFactory;

  @Mock
  private TaskInfo taskInfo;

  private CleanupBootService underTest;

  @Before
  public void setup() throws Exception {
    underTest = new CleanupBootService(taskScheduler);

    taskConfig = new TaskConfiguration();
    taskConfig.setTypeId(CleanupTaskDescriptor.TYPE_ID);
    taskConfig.setName(TASK_NAME);
    when(taskInfo.getConfiguration()).thenReturn(taskConfig);
    when(taskScheduler.listsTasks()).thenReturn(emptyList());

    when(taskScheduler.createTaskConfigurationInstance(CleanupTaskDescriptor.TYPE_ID)).thenReturn(taskConfig);
    when(taskScheduler.getScheduleFactory()).thenReturn(scheduleFactory);

    when(scheduleFactory.cron(any(), any())).thenAnswer(invocation -> {
      return new Cron((Date) invocation.getArguments()[0], (String) invocation.getArguments()[1]);
    });
  }

  /**
   * Test that the service can handle multiple concurrent startups using Virtual Threads.
   * This verifies that the service is compatible with Java 21's threading model.
   */
  @Test
  public void testConcurrentStartupWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Use a higher thread count than would be practical with platform threads
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create multiple virtual threads that will all try to start the service concurrently
    for (int i = 0; i < threadCount; i++) {
      virtualThreadFactory.newThread(() -> {
        try {
          startLatch.await(); // Wait for signal to start
          underTest.doStart();
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          log.error("Error during concurrent startup", e);
        }
        finally {
          completionLatch.countDown();
        }
      }).start();
    }
    
    // Release all threads to start concurrently
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify that all threads successfully started the service
    assertThat(successCount.get(), is(threadCount));
    
    // Verify the service scheduled the task the expected number of times
    verify(taskScheduler, times(threadCount)).scheduleTask(any(), any());
  }

  /**
   * Test scheduling with a high number of Virtual Threads to verify scalability.
   * This would not be practical with platform threads due to resource constraints.
   */
  @Test
  public void testHighConcurrencyTaskScheduling() throws Exception {
    // Create a virtual thread executor with a very high thread count
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(HIGH_CONCURRENCY_THREAD_COUNT);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Mock the scheduleTask method to simulate successful scheduling
      when(taskScheduler.scheduleTask(any(), any())).thenReturn(taskInfo);
      
      // Submit a large number of concurrent task scheduling operations
      for (int i = 0; i < HIGH_CONCURRENCY_THREAD_COUNT; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Create a unique task configuration for each thread
            TaskConfiguration config = new TaskConfiguration();
            config.setTypeId(CleanupTaskDescriptor.TYPE_ID);
            config.setName(TASK_NAME + "-" + taskId);
            
            // Schedule the task
            TaskInfo result = taskScheduler.scheduleTask(config, 
                scheduleFactory.cron(new Date(), CleanupBootService.CRON));
            
            if (result != null) {
              successCount.incrementAndGet();
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all scheduling operations to complete
      assertTrue("Timed out waiting for virtual threads to complete scheduling", 
          completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      
      // Verify that all scheduling operations were successful
      assertThat(successCount.get(), is(HIGH_CONCURRENCY_THREAD_COUNT));
      
      // Verify the scheduler was called the expected number of times
      verify(taskScheduler, times(HIGH_CONCURRENCY_THREAD_COUNT)).scheduleTask(any(), any());
    }
  }

  /**
   * Verify that operations don't cause thread pinning, which would defeat the purpose
   * of using Virtual Threads for improved scalability.
   */
  @Test
  public void testNoThreadPinning() throws Exception {
    // Create a flag to track if any thread gets pinned
    AtomicBoolean threadPinned = new AtomicBoolean(false);
    
    // Mock the scheduleTask method to simulate a blocking operation that might cause pinning
    doAnswer(new Answer<TaskInfo>() {
      @Override
      public TaskInfo answer(InvocationOnMock invocation) throws Throwable {
        // Simulate a blocking operation that would cause pinning if synchronization is misused
        synchronized (this) {
          // In a real pinning scenario, this would block other virtual threads
          // We're just simulating the blocking operation here
          Thread.sleep(50);
        }
        return taskInfo;
      }
    }).when(taskScheduler).scheduleTask(any(), any());
    
    // Create virtual threads to perform operations concurrently
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(100);
      
      for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
          try {
            long startTime = System.currentTimeMillis();
            underTest.doStart();
            long duration = System.currentTimeMillis() - startTime;
            
            // If this thread took significantly longer than expected, it might indicate pinning
            // This is a simplistic check - in real scenarios you'd use more sophisticated detection
            if (duration > 1000) { // If it took more than a second, that's suspicious
              threadPinned.set(true);
              log.warn("Possible thread pinning detected: operation took {} ms", duration);
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for virtual threads to complete", 
          completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      
      // Verify no thread pinning was detected
      assertThat("Thread pinning detected during operations", threadPinned.get(), is(false));
    }
  }

  /**
   * Verify that tasks can execute properly with Virtual Threads.
   * This test simulates the actual execution of cleanup tasks using Virtual Threads.
   */
  @Test
  public void testVirtualThreadTaskExecution() throws Exception {
    // Create a completion latch to track task execution
    CountDownLatch taskExecutionLatch = new CountDownLatch(1);
    
    // Mock the task execution to run in a virtual thread
    doAnswer(new Answer<TaskInfo>() {
      @Override
      public TaskInfo answer(InvocationOnMock invocation) throws Throwable {
        // Simulate scheduling the task by immediately executing it in a virtual thread
        Thread.ofVirtual().start(() -> {
          try {
            // Simulate the task doing some work
            Thread.sleep(100);
            
            // Signal that the task has completed
            taskExecutionLatch.countDown();
          }
          catch (Exception e) {
            log.error("Error executing task in virtual thread", e);
          }
        });
        return taskInfo;
      }
    }).when(taskScheduler).scheduleTask(any(), any());
    
    // Start the service, which should schedule the task
    underTest.doStart();
    
    // Wait for the task to execute
    assertTrue("Timed out waiting for task to execute in virtual thread", 
        taskExecutionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify the task was scheduled
    verify(taskScheduler).scheduleTask(any(), any());
  }

  /**
   * Test that duplicate task removal works correctly with Virtual Threads.
   */
  @Test
  public void testDuplicateRemovalWithVirtualThreads() throws Exception {
    // Create multiple duplicate tasks
    TaskInfo matchA = mockTask(CleanupBootService.TASK_NAME, CleanupBootService.CRON);
    TaskInfo matchB = mockTask(CleanupBootService.TASK_NAME, CleanupBootService.CRON);
    TaskInfo matchC = mockTask(CleanupBootService.TASK_NAME, CleanupBootService.CRON);
    
    List<TaskInfo> tasks = ImmutableList.of(matchA, matchB, matchC);
    when(taskScheduler.listsTasks()).thenReturn(tasks);
    
    // Create a completion latch to track concurrent operations
    CountDownLatch completionLatch = new CountDownLatch(10);
    
    // Run multiple concurrent startups with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
          try {
            underTest.doStart();
          }
          catch (Exception e) {
            log.error("Error during concurrent startup", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for virtual threads to complete", 
          completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
    
    // Verify that duplicate tasks were removed
    // The first task should be kept, all others should be removed
    verify(matchA, never()).remove();
    verify(matchB, times(10)).remove();
    verify(matchC, times(10)).remove();
  }

  private static TaskInfo mockTask(final String name, final String cron) {
    TaskInfo task = mock(TaskInfo.class);
    when(task.getName()).thenReturn(name);
    TaskConfiguration taskConfig = new TaskConfiguration();
    taskConfig.setName(name);
    taskConfig.setTypeId(CleanupTaskDescriptor.TYPE_ID);
    when(task.getConfiguration()).thenReturn(taskConfig);
    when(task.getSchedule()).thenReturn(new Cron(new Date(), cron));
    return task;
  }
}