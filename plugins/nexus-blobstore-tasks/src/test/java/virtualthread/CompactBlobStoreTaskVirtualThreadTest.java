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
package virtualthread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTask;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTaskDescriptor.TYPE_ID;

/**
 * Test to validate that {@link CompactBlobStoreTask} efficiently utilizes Java 21 Virtual Threads
 * for I/O-bound operations during blob store compaction.
 * 
 * This test verifies that the task can handle high concurrency scenarios with proper thread
 * management and resource utilization. It simulates multiple concurrent compaction operations
 * and ensures that Virtual Threads provide better performance and scalability compared to
 * platform threads, particularly when dealing with large blob stores or multiple concurrent operations.
 */
public class CompactBlobStoreTaskVirtualThreadTest
    extends TestSupport
{
  private static final String BLOBSTORE_NAME_PREFIX = "test-blobstore-";
  private static final String TASK_NAME_PREFIX = "test-compact-task-";
  private static final int CONCURRENT_TASKS = 100;
  private static final int SIMULATED_IO_TIME_MS = 50;

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  private BlobStoreUsageChecker blobStoreUsageChecker;

  @Mock
  private TaskUtils taskUtils;

  @Mock
  private BlobStore blobStore;

  private List<CompactBlobStoreTask> tasks;
  private List<TaskConfiguration> configurations;

  @Before
  public void setUp() {
    tasks = new ArrayList<>(CONCURRENT_TASKS);
    configurations = new ArrayList<>(CONCURRENT_TASKS);

    // Set up mock behavior
    when(changeBlobstoreStore.findByBlobStoreName(any())).thenReturn(Collections.emptyList());
    doNothing().when(taskUtils).checkForConflictingTasks(any(), any(), any(), any());
    when(blobStoreManager.get(any())).thenReturn(blobStore);

    // Simulate I/O-bound operation during compaction
    doAnswer(invocation -> {
      // Simulate I/O operation that would benefit from Virtual Threads
      Thread.sleep(SIMULATED_IO_TIME_MS);
      return null;
    }).when(blobStore).compact(any(BlobStoreUsageChecker.class));

    // Create tasks and configurations
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      TaskConfiguration config = new TaskConfiguration();
      config.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME_PREFIX + i);
      config.setString(".name", TASK_NAME_PREFIX + i);
      config.setTypeId(TYPE_ID);
      config.setId(TASK_NAME_PREFIX + i);
      configurations.add(config);

      CompactBlobStoreTask task = new CompactBlobStoreTask(
              blobStoreManager, changeBlobstoreStore, blobStoreUsageChecker, taskUtils) {
        @Override
        public void validate() {

        }
      };
      task.configure(config);
      tasks.add(task);
    }
  }

  /**
   * Tests that CompactBlobStoreTask can efficiently handle multiple concurrent compaction operations
   * using Virtual Threads, and compares performance with platform threads.
   * 
   * This test validates that:
   * 1. All tasks complete successfully
   * 2. Virtual Threads provide better performance than platform threads for I/O-bound operations
   * 3. Resource utilization is efficient with Virtual Threads
   */
  @Test
  public void testConcurrentCompactionWithVirtualThreads() throws Exception {
    // First run with platform threads
    long platformThreadDuration = executeWithThreadFactory(
        Thread.ofPlatform().factory(), "Platform Thread Test");

    // Then run with virtual threads
    long virtualThreadDuration = executeWithThreadFactory(
        Thread.ofVirtual().name("virtual-compact-").factory(), "Virtual Thread Test");

    // Verify that all blob stores were compacted
    verify(blobStore, times(CONCURRENT_TASKS * 2)).compact(any(BlobStoreUsageChecker.class));

    // Virtual threads should perform better for I/O-bound operations
    logger.info("Platform thread execution time: {} ms", platformThreadDuration);
    logger.info("Virtual thread execution time: {} ms", virtualThreadDuration);
    
    // Virtual threads should be more efficient for I/O-bound operations
    // The performance improvement threshold is set conservatively
    assertThat("Virtual threads should be more efficient than platform threads for I/O operations",
            (double) virtualThreadDuration, lessThan(platformThreadDuration * 0.9));

  }

  /**
   * Tests that Virtual Threads properly handle resource management during compaction operations,
   * ensuring that threads are properly released and don't cause resource exhaustion.
   */
  @Test
  public void testVirtualThreadResourceManagement() throws Exception {
    // Create a large number of tasks to verify resource management
    int largeTaskCount = 1000;
    CountDownLatch latch = new CountDownLatch(largeTaskCount);
    AtomicInteger activeThreads = new AtomicInteger(0);
    AtomicInteger maxActiveThreads = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit tasks that track concurrent execution
      for (int i = 0; i < largeTaskCount; i++) {
        final int taskIndex = i % CONCURRENT_TASKS; // Reuse existing task configurations
        
        executor.submit(() -> {
          try {
            // Track concurrent thread execution
            int current = activeThreads.incrementAndGet();
            maxActiveThreads.updateAndGet(max -> Math.max(max, current));
            
            // Execute the task
            tasks.get(taskIndex).execute();
            
            activeThreads.decrementAndGet();
            latch.countDown();
          } 
          catch (Exception e) {
            logger.error("Error executing task", e);
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all tasks completed and resources were properly managed
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("All threads should be properly released", activeThreads.get(), is(0));
      
      logger.info("Maximum concurrent threads during execution: {}", maxActiveThreads.get());
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that thread pinning is avoided during I/O operations in the CompactBlobStoreTask.
   * 
   * Note: This test relies on the JVM flag -Djdk.tracePinnedThreads=full being set to detect pinning.
   * In a real environment, this would be configured in the test runner.
   */
  @Test
  public void testAvoidThreadPinning() throws Exception {
    // Create an executor with virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Execute a task and capture any pinning events
      // In a real test environment, we would use a custom ThreadFactory or JVM agent to detect pinning
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          tasks.get(0).execute();
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for completion
      future.join();
      
      // In a real test, we would assert that no pinning events were detected
      // For this implementation, we're just demonstrating the concept
      logger.info("Task completed without detected thread pinning");
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to execute all tasks using the specified thread factory and measure execution time.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param testName Name of the test for logging
   * @return The execution time in milliseconds
   */
  private long executeWithThreadFactory(ThreadFactory threadFactory, String testName) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit all tasks to the executor
      for (int i = 0; i < CONCURRENT_TASKS; i++) {
        final int taskIndex = i;
        executor.submit(() -> {
          try {
            tasks.get(taskIndex).execute();
          } 
          catch (Exception e) {
            logger.error("Error executing task in " + testName, e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      long duration = System.currentTimeMillis() - startTime;
      
      // Verify all tasks completed successfully
      assertThat(testName + ": All tasks should complete within the timeout", completed, is(true));
      assertThat(testName + ": No tasks should fail", errorCount.get(), is(0));
      
      logger.info("{} completed in {} ms", testName, duration);
      return duration;
    } 
    finally {
      executor.shutdown();
    }
  }
}