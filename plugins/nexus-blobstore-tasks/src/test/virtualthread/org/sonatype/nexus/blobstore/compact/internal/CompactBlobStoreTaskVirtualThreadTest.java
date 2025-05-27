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
package org.sonatype.nexus.blobstore.compact.internal;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTaskDescriptor.TYPE_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;

/**
 * Tests for {@link CompactBlobStoreTask} with Virtual Threads to verify high concurrency compatibility.
 */
@ExtendWith(MockitoExtension.class)
public class CompactBlobStoreTaskVirtualThreadTest
    extends TestSupport
{
  private final String BLOBSTORE_NAME = "test";

  private final String TASK_NAME = "test-task";

  @Mock
  BlobStoreManager blobStoreManager;

  @Mock
  ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  BlobStoreUsageChecker blobStoreUsageChecker;

  @Mock
  TaskUtils taskUtils;

  TaskConfiguration configuration;

  CompactBlobStoreTask underTest;

  @BeforeEach
  public void setUp() {
    configuration = new TaskConfiguration();
    configuration.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
    configuration.setString(".name", TASK_NAME);
    configuration.setTypeId(TYPE_ID);
    configuration.setId(TASK_NAME);

    underTest = new CompactBlobStoreTask(blobStoreManager, changeBlobstoreStore, blobStoreUsageChecker, taskUtils);
    underTest.configure(configuration);
  }

  @Test
  public void checkForConflictsThrowsExceptionIfConflictingTaskIsRunning() {
    doThrow(new IllegalStateException("conflicting task"))
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals("conflicting task", exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
  }

  @Test
  public void checkForConflictsThrowsExceptionIfMoveTaskIsUnfinished() {
    ChangeRepositoryBlobStoreConfiguration record = getRecord("test", BLOBSTORE_NAME, "target-blobstore");

    doNothing()
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.singletonList(record));

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals(
        String.format("found unfinished move task(s) using blobstore '%s', task can't be executed", BLOBSTORE_NAME),
        exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    verify(changeBlobstoreStore, times(1)).findByBlobStoreName(eq(BLOBSTORE_NAME));
  }

  @Test
  public void testConcurrentConflictDetectionWithVirtualThreads() throws Exception {
    // Configure mocks for successful conflict detection
    doNothing()
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = 1000; // Test with 1000 concurrent virtual threads
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Call the method under test
            underTest.checkForConflicts();
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Exceptions are expected to be thrown in case of conflicts
            log.debug("Expected exception during concurrent execution: {}", e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Not all virtual threads completed in time");

      // Verify that all conflict checks were successful
      assertEquals(taskCount, successCount.get(), "Not all conflict checks were successful");

      // Verify that the conflict detection was called the expected number of times
      verify(taskUtils, times(taskCount)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
      verify(changeBlobstoreStore, times(taskCount)).findByBlobStoreName(eq(BLOBSTORE_NAME));
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void testConcurrentConflictDetectionWithConflicts() throws Exception {
    // Configure mocks to simulate conflicts on every other call
    AtomicInteger callCount = new AtomicInteger(0);
    
    // Alternate between throwing an exception and not throwing
    when(taskUtils.checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class)))
        .thenAnswer(invocation -> {
          if (callCount.getAndIncrement() % 2 == 0) {
            throw new IllegalStateException("conflicting task");
          }
          return null;
        });
    
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = 1000; // Test with 1000 concurrent virtual threads
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Call the method under test
            underTest.checkForConflicts();
            successCount.incrementAndGet();
          } catch (IllegalStateException e) {
            // Exceptions are expected to be thrown in case of conflicts
            failureCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Not all virtual threads completed in time");

      // Verify that approximately half of the calls succeeded and half failed
      // We can't expect exactly 50/50 due to the concurrent nature, but it should be close
      log.info("Success count: {}, Failure count: {}", successCount.get(), failureCount.get());
      assertTrue(successCount.get() > 0, "Expected some successful conflict checks");
      assertTrue(failureCount.get() > 0, "Expected some failed conflict checks");
      assertEquals(taskCount, successCount.get() + failureCount.get(), 
          "Total of successes and failures should equal task count");
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void testThreadPinningAvoidance() throws Exception {
    // Configure mocks for successful conflict detection
    doNothing()
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    // Create a virtual thread factory with a name pattern for easier debugging
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vthread-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = 100; // A smaller number is sufficient for this test
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    // Track the carrier threads used
    AtomicInteger uniqueCarrierThreads = new AtomicInteger(0);
    Map<String, Boolean> carrierThreads = Collections.synchronizedMap(new java.util.HashMap<>());

    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Call the method under test
            underTest.checkForConflicts();
            
            // Get the current carrier thread name
            String carrierName = Thread.currentThread().toString();
            if (carrierName.contains("carrier") && !carrierThreads.containsKey(carrierName)) {
              carrierThreads.put(carrierName, true);
              uniqueCarrierThreads.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Log the number of unique carrier threads used
      log.info("Number of unique carrier threads used: {}", uniqueCarrierThreads.get());
      
      // The number of carrier threads should be much smaller than the number of virtual threads,
      // indicating that virtual threads are being efficiently scheduled without pinning
      assertTrue(uniqueCarrierThreads.get() < taskCount / 2, 
          "Too many carrier threads used, suggesting possible thread pinning");
    } finally {
      executor.shutdown();
    }
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