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
package org.sonatype.virtualthread;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTask;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTaskDescriptor.TYPE_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;

/**
 * Tests the {@link CompactBlobStoreTask} component with Java 21 Virtual Threads to verify that
 * blob compaction operations can efficiently utilize the lightweight threading model.
 *
 * @since 3.60
 */
public class CompactBlobStoreTaskVirtualThreadTest
    extends TestSupport
{
  private static final String BLOBSTORE_NAME = "test";

  private static final String TASK_NAME = "test-task";

  @Mock
  BlobStoreManager blobStoreManager;

  @Mock
  ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  BlobStoreUsageChecker blobStoreUsageChecker;

  @Mock
  TaskUtils taskUtils;

  @Mock
  BlobStore blobStore;

  TaskConfiguration configuration;

  CompactBlobStoreTask underTest;

  @Before
  public void setUp() {
    configuration = new TaskConfiguration();
    configuration.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
    configuration.setString(".name", TASK_NAME);
    configuration.setTypeId(TYPE_ID);
    configuration.setId(TASK_NAME);

    underTest = new CompactBlobStoreTask(blobStoreManager, changeBlobstoreStore, blobStoreUsageChecker, taskUtils);
    when(blobStoreManager.get(BLOBSTORE_NAME)).thenReturn(blobStore);
  }

  /**
   * Verifies that the CompactBlobStoreTask can be executed using Virtual Threads
   * and that the task completes successfully.
   */
  @Test
  public void testCompactBlobStoreTaskWithVirtualThreads() throws Exception {
    underTest.configure(configuration);
    doNothing().when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());
    
    // Create a virtual thread to run the task
    Thread virtualThread = Thread.ofVirtual().name("compact-task-thread").start(() -> {
      try {
        underTest.execute();
      }
      catch (Exception e) {
        fail("Task execution failed: " + e.getMessage());
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the task executed correctly
    verify(blobStoreManager).get(BLOBSTORE_NAME);
  }

  /**
   * Tests that multiple concurrent compaction operations can be executed efficiently
   * using Virtual Threads, verifying that I/O-bound operations benefit from the
   * improved concurrency model.
   */
  @Test
  public void testConcurrentCompactionWithVirtualThreads() throws Exception {
    // Configure the task
    underTest.configure(configuration);
    doNothing().when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());
    
    // Simulate I/O operations in the blob store compact method
    final CountDownLatch latch = new CountDownLatch(1);
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Simulate I/O operation with a sleep
        Thread.sleep(100);
        latch.countDown();
        return null;
      }
    }).when(blobStore).compact();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit the task for execution
      Future<?> future = executor.submit(() -> {
        try {
          underTest.execute();
        }
        catch (Exception e) {
          fail("Task execution failed: " + e.getMessage());
        }
      });
      
      // Wait for the task to complete
      future.get(5, TimeUnit.SECONDS);
      
      // Verify the latch was counted down, indicating the compact method was called
      assertTrue("Compact operation did not complete", latch.await(0, TimeUnit.MILLISECONDS));
    }
    
    // Verify the task executed correctly
    verify(blobStoreManager).get(BLOBSTORE_NAME);
    verify(blobStore).compact();
  }

  /**
   * Tests that high concurrency scenarios with many virtual threads can be handled
   * efficiently, verifying that the system can scale to handle many concurrent
   * compaction operations.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Number of concurrent tasks to run
    final int concurrentTasks = 100;
    
    // Configure the task
    underTest.configure(configuration);
    doNothing().when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());
    
    // Track the number of completed tasks
    final AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Simulate I/O operations in the blob store compact method
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Simulate I/O operation with a sleep
        Thread.sleep(50);
        completedTasks.incrementAndGet();
        return null;
      }
    }).when(blobStore).compact();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Create a map to store futures
      Map<Integer, Future<?>> futures = new ConcurrentHashMap<>();
      
      // Submit multiple tasks for execution
      for (int i = 0; i < concurrentTasks; i++) {
        final int taskId = i;
        futures.put(taskId, executor.submit(() -> {
          try {
            // Create a new task for each execution to avoid conflicts
            CompactBlobStoreTask task = new CompactBlobStoreTask(
                blobStoreManager, changeBlobstoreStore, blobStoreUsageChecker, taskUtils);
            
            TaskConfiguration config = new TaskConfiguration();
            config.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
            config.setString(".name", TASK_NAME + "-" + taskId);
            config.setTypeId(TYPE_ID);
            config.setId(TASK_NAME + "-" + taskId);
            
            task.configure(config);
            task.execute();
          }
          catch (Exception e) {
            fail("Task execution failed: " + e.getMessage());
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures.values()) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
    
    // Verify all tasks completed successfully
    assertEquals("Not all tasks completed", concurrentTasks, completedTasks.get());
    verify(blobStoreManager, times(concurrentTasks)).get(BLOBSTORE_NAME);
    verify(blobStore, times(concurrentTasks)).compact();
  }

  /**
   * Tests that task conflict detection works correctly with Virtual Threads,
   * verifying that the system can properly detect and handle conflicts between
   * concurrent tasks.
   */
  @Test
  public void testTaskConflictDetectionWithVirtualThreads() throws Exception {
    underTest.configure(configuration);
    
    // Simulate a conflict with another task
    doThrow(new IllegalStateException("conflicting task"))
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());
    
    // Create a virtual thread to run the task
    final CountDownLatch exceptionLatch = new CountDownLatch(1);
    final AtomicInteger exceptionCount = new AtomicInteger(0);
    
    Thread virtualThread = Thread.ofVirtual().name("conflict-test-thread").start(() -> {
      try {
        underTest.checkForConflicts();
        fail("Expected IllegalStateException was not thrown");
      }
      catch (IllegalStateException e) {
        // Expected exception
        assertEquals("conflicting task", e.getMessage());
        exceptionCount.incrementAndGet();
        exceptionLatch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the exception was thrown and caught
    assertTrue("Exception was not thrown", exceptionLatch.await(0, TimeUnit.MILLISECONDS));
    assertEquals("Exception count mismatch", 1, exceptionCount.get());
    
    // Verify the conflict check was called
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
  }

  /**
   * Tests that unfinished move tasks are detected correctly when using Virtual Threads,
   * verifying that the system can properly detect and handle conflicts with move tasks.
   */
  @Test
  public void testUnfinishedMoveTaskDetectionWithVirtualThreads() throws Exception {
    ChangeRepositoryBlobStoreConfiguration record = getRecord("test", BLOBSTORE_NAME, "target-blobstore");

    underTest.configure(configuration);

    doNothing()
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.singletonList(record));

    // Create a virtual thread to run the task
    final CountDownLatch exceptionLatch = new CountDownLatch(1);
    final AtomicInteger exceptionCount = new AtomicInteger(0);
    
    Thread virtualThread = Thread.ofVirtual().name("move-task-test-thread").start(() -> {
      try {
        underTest.checkForConflicts();
        fail("Expected IllegalStateException was not thrown");
      }
      catch (IllegalStateException e) {
        // Expected exception
        assertEquals(
            String.format("found unfinished move task(s) using blobstore '%s', task can't be executed", BLOBSTORE_NAME),
            e.getMessage());
        exceptionCount.incrementAndGet();
        exceptionLatch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the exception was thrown and caught
    assertTrue("Exception was not thrown", exceptionLatch.await(0, TimeUnit.MILLISECONDS));
    assertEquals("Exception count mismatch", 1, exceptionCount.get());
    
    // Verify the conflict check was called
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    verify(changeBlobstoreStore, times(1)).findByBlobStoreName(eq(BLOBSTORE_NAME));
  }

  private ChangeRepositoryBlobStoreConfiguration getRecord(final String name, final String sourceBlobStoreName, final String targetBlobStoreName) {
    return new ChangeRepositoryBlobStoreConfiguration()
    {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public void setName(final String name) {

      }

      @Override
      public String getTargetBlobStoreName() {
        return targetBlobStoreName;
      }

      @Override
      public void setTargetBlobStoreName(final String targetBlobStoreName) {

      }

      @Override
      public String getSourceBlobStoreName() {
        return sourceBlobStoreName;
      }

      @Override
      public void setSourceBlobStoreName(final String sourceBlobStoreName) {

      }

      @Override
      public OffsetDateTime getStarted() {
        return null;
      }

      @Override
      public void setStarted(final OffsetDateTime processStartDate) {

      }
    };
  }
}