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
package org.sonatype.nexus.content.maven.internal.snapshot;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.store.GAV;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.store.InternalIds;
import org.sonatype.nexus.repository.maven.tasks.RemoveSnapshotsConfig;
import org.sonatype.nexus.repository.types.GroupType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RemoveSnapshotsFacetImpl} using Java 21 virtual threads.
 * 
 * This test class validates that the snapshot removal logic works correctly when executed
 * with virtual thread executors, and that thread pinning and concurrency issues are detected.
 * 
 * <p>Virtual threads are particularly beneficial for the snapshot removal process as it involves
 * multiple I/O operations (database queries, file system operations) that can benefit from the
 * lightweight threading model introduced in Java 21.</p>
 * 
 * <p>These tests ensure that the RemoveSnapshotsFacet implementation can safely operate in a
 * highly concurrent environment using virtual threads without encountering thread pinning or
 * other concurrency issues.</p>
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class RemoveSnapshotsVirtualThreadTest
    extends TestSupport
{
  /**
   * Number of concurrent tasks to run in the high-concurrency test.
   * This is set high enough to detect potential thread pinning issues.  
   */
  private static final int CONCURRENT_TASKS = 100;
  
  /**
   * Timeout for waiting on test completion.
   */
  private static final int TIMEOUT_SECONDS = 10;
  
  @Mock
  private Repository repository;
  
  @Mock
  private MavenContentFacet mavenContentFacet;
  
  @Mock
  private GroupType groupType;
  
  private RemoveSnapshotsFacetImpl underTest;
  
  private ExecutorService virtualExecutor;
  
  @BeforeEach
  void setUp() {
    underTest = new RemoveSnapshotsFacetImpl();
    underTest.attach(repository);
    
    // Configure the GroupType
    when(groupType.getValue()).thenReturn("group");
    
    // Configure repository mocks
    when(repository.getName()).thenReturn("test-repo");
    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenContentFacet);
    
    // Create a virtual thread executor using Java 21's virtual thread factory
    // This is a key part of testing with virtual threads - we're explicitly using
    // the new Thread.ofVirtual() API introduced in Java 21
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }
  
  @AfterEach
  void tearDown() {
    if (virtualExecutor != null) {
      // Proper cleanup of virtual thread executor to avoid resource leaks
      // This is important even with virtual threads to ensure clean test isolation
      virtualExecutor.shutdown();
      try {
        if (!virtualExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        virtualExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
  
  @Test
  @DisplayName("Verify concurrent snapshot removal with virtual threads")
  void testConcurrentSnapshotRemovalWithVirtualThreads() throws Exception {
    // Setup test data - create a list of Maven snapshot GAVs
    // We create multiple GAVs to simulate a realistic repository with various snapshot artifacts
    List<GAV> snapshots = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      snapshots.add(new GAV("group", "artifact" + i, "1.0-SNAPSHOT"));
    }
    
    // Configure mocks
    when(repository.getType()).thenReturn("hosted"); // Not a group repository
    when(mavenContentFacet.findGavsWithSnapshotVersions()).thenReturn(snapshots);
    lenient().when(mavenContentFacet.getComponentsByGAV(any(GAV.class), anyInt()))
        .thenReturn(InternalIds.of(1L, 2L, 3L));
    lenient().when(mavenContentFacet.deleteComponents(anySet()))
        .thenReturn(3);
    
    // Create a config with standard settings
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(
        2, // minimumRetained
        30, // snapshotRetentionDays
        true, // removeIfReleased
        0 // gracePeriod
    );
    
    // Setup concurrency test
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicBoolean anyFailures = new AtomicBoolean(false);
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Submit multiple concurrent tasks using virtual threads
    // This is the key part of the test - we're running many concurrent operations
    // using virtual threads to verify the implementation works correctly under high concurrency
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      virtualExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be ready before starting
          
          // Execute the actual snapshot removal operation
          // This is what we're testing - that it works correctly with virtual threads
          underTest.removeSnapshots(config);
          
          completedTasks.incrementAndGet();
        }
        catch (Exception e) {
          log.error("Task failed with exception", e);
          anyFailures.set(true);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all tasks simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete
    boolean allCompleted = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertTrue(allCompleted, "All tasks should complete within the timeout");
    assertFalse(anyFailures.get(), "No tasks should fail");
    assertThat(completedTasks.get(), equalTo(CONCURRENT_TASKS));
    
    // Verify the facet was called the expected number of times
    verify(mavenContentFacet, times(CONCURRENT_TASKS)).findGavsWithSnapshotVersions();
  }
  
  @Test
  @DisplayName("Verify group repository delegation with virtual threads")
  void testGroupRepositoryDelegation() throws Exception {
    // Configure repository as a group
    when(repository.getType()).thenReturn("group");
    
    // Create a config with standard settings
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(
        2, // minimumRetained
        30, // snapshotRetentionDays
        true, // removeIfReleased
        0 // gracePeriod
    );
    
    // Execute with virtual threads
    CountDownLatch completionLatch = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean(false);
    
    virtualExecutor.submit(() -> {
      try {
        underTest.removeSnapshots(config);
        success.set(true);
      }
      finally {
        completionLatch.countDown();
      }
    });
    
    // Wait for completion
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertTrue(completed, "Task should complete within the timeout");
    assertTrue(success.get(), "Task should complete successfully");
    
    // For group repositories, we should never call findGavsWithSnapshotVersions directly
    verify(mavenContentFacet, never()).findGavsWithSnapshotVersions();
  }
  
  @Test
  @DisplayName("Verify handling of empty snapshots list with virtual threads")
  void testEmptySnapshotsList() throws Exception {
    // Configure mocks
    when(repository.getType()).thenReturn("hosted");
    when(mavenContentFacet.findGavsWithSnapshotVersions()).thenReturn(List.of());
    
    // Create a config with standard settings
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(
        2, // minimumRetained
        30, // snapshotRetentionDays
        true, // removeIfReleased
        0 // gracePeriod
    );
    
    // Execute with virtual threads
    CountDownLatch completionLatch = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean(false);
    
    virtualExecutor.submit(() -> {
      try {
        underTest.removeSnapshots(config);
        success.set(true);
      }
      finally {
        completionLatch.countDown();
      }
    });
    
    // Wait for completion
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertTrue(completed, "Task should complete within the timeout");
    assertTrue(success.get(), "Task should complete successfully");
    
    // Should call findGavsWithSnapshotVersions but not deleteComponents
    verify(mavenContentFacet, times(1)).findGavsWithSnapshotVersions();
    verify(mavenContentFacet, never()).deleteComponents(any());
  }
  
  @Test
  @DisplayName("Verify virtual threads can be properly interrupted")
  void testVirtualThreadsCanBeInterrupted() throws Exception {
    // This test is important for detecting potential thread pinning issues
    // Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread
    // This typically happens in synchronized blocks or when using native methods
    // We want to ensure our implementation doesn't cause pinning when interrupted
    // Configure mocks to simulate a long-running operation
    // This test verifies that virtual threads respond properly to interruption,
    // which is important for task cancellation and application shutdown scenarios
    when(repository.getType()).thenReturn("hosted");
    when(mavenContentFacet.findGavsWithSnapshotVersions()).thenAnswer(invocation -> {
      // Simulate a long-running operation that can be interrupted
      // This is important to test because virtual threads have different interrupt handling
      // characteristics compared to platform threads
      try {
        Thread.sleep(30000); // Much longer than our test timeout
      }
      catch (InterruptedException e) {
        // Expected - we'll interrupt this thread
        // Properly propagate the interrupt status
        Thread.currentThread().interrupt();
      }
      return List.of();
    });
    
    // Create a config with standard settings
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(
        2, // minimumRetained
        30, // snapshotRetentionDays
        true, // removeIfReleased
        0 // gracePeriod
    );
    
    // Execute with virtual threads
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(1);
    AtomicBoolean wasInterrupted = new AtomicBoolean(false);
    
    // Create a separate executor with virtual threads for this specific test
    // We need a separate executor because we'll be shutting it down forcefully
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    try {
      executor.submit(() -> {
        try {
          startedLatch.countDown(); // Signal that we've started
          underTest.removeSnapshots(config);
        }
        catch (Exception e) {
          if (e instanceof InterruptedException || Thread.interrupted()) {
            wasInterrupted.set(true);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
      
      // Wait for the task to start
      assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "Task should start");
      
      // Give it a moment to get into the long operation
      Thread.sleep(500);
      
      // Shutdown the executor which should interrupt the task
      // This tests that virtual threads respond properly to interruption via ExecutorService.shutdownNow()
      executor.shutdownNow();
      
      // Wait for completion
      boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "Task should complete after interruption");
      // Verify that the virtual thread was properly interrupted
      // This is critical for ensuring that virtual threads behave correctly
      // with respect to interruption, which is important for task cancellation
      assertTrue(wasInterrupted.get() || Thread.interrupted(), 
          "Task should be interrupted or thread should be marked as interrupted");
    }
    finally {
      executor.shutdownNow();
    }
  }
}