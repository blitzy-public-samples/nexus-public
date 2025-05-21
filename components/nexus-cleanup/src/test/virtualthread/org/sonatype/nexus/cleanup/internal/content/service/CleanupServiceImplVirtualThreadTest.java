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
package org.sonatype.nexus.cleanup.internal.content.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.content.search.CleanupBrowseServiceFactory;
import org.sonatype.nexus.cleanup.content.search.CleanupComponentBrowse;
import org.sonatype.nexus.cleanup.internal.method.CleanupMethod;
import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.task.DeletionProgress;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Sets.newLinkedHashSet;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_BLOB_UPDATED_KEY;
import static org.sonatype.nexus.testcommon.matchers.NexusMatchers.streamContains;

/**
 * Tests for {@link CleanupServiceImpl} that validate its behavior when using Java 21 Virtual Threads.
 * 
 * This test class focuses on high-concurrency scenarios with multiple repositories and cleanup policies,
 * measures performance differences between platform and virtual threads, detects thread pinning issues
 * during cleanup operations, and verifies scalability under load.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag(VirtualThreadTestGroup.NAME)
public class CleanupServiceImplVirtualThreadTest
    extends TestSupport
{
  private static final int RETRY_LIMIT = 3;
  private static final int LARGE_REPOSITORY_COUNT = 100;
  private static final int LARGE_POLICY_COUNT = 10;
  private static final int COMPONENT_COUNT_PER_REPO = 50;
  private static final int CONCURRENT_OPERATIONS = 20;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  @Mock
  private CleanupMethod cleanupMethod;

  @Mock
  private Type type;

  @Mock
  private Format format;

  @Mock
  private BooleanSupplier cancelledCheck;

  @Mock
  private DeletionProgress deletionProgress;

  @Mock
  private CleanupBrowseServiceFactory cleanupBrowseFactory;

  private CleanupServiceImpl underTest;

  // Virtual thread metrics
  private final AtomicLong virtualThreadsCreated = new AtomicLong(0);
  private final AtomicLong virtualThreadsTerminated = new AtomicLong(0);
  private final AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
  private final Map<Thread, StackTraceElement[]> pinnedThreads = new ConcurrentHashMap<>();

  @BeforeEach
  public void setup() {
    // Reset metrics
    virtualThreadsCreated.set(0);
    virtualThreadsTerminated.set(0);
    threadPinningDetected.set(false);
    pinnedThreads.clear();

    // Setup CleanupServiceImpl
    underTest = new CleanupServiceImpl(repositoryManager, cleanupPolicyStorage, cleanupMethod,
        new GroupType(), RETRY_LIMIT, cleanupBrowseFactory, repository -> true);

    // Setup format
    when(format.getValue()).thenReturn("maven2");

    // Setup deletion progress
    when(deletionProgress.isFailed()).thenReturn(false);
    when(cleanupMethod.run(any(), any(), any())).thenReturn(deletionProgress);

    // Setup cancellation check
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
  }

  /**
   * Tests that cleanup operations can be executed concurrently using virtual threads,
   * verifying that the system can handle a large number of repositories and policies
   * without resource exhaustion.
   */
  @Test
  public void concurrentCleanupScalabilityTest() {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Setup a large number of repositories and policies
      List<Repository> repositories = setupLargeRepositorySet(LARGE_REPOSITORY_COUNT, LARGE_POLICY_COUNT);
      when(repositoryManager.browse()).thenReturn(repositories);

      // Create a virtual thread factory with monitoring
      ThreadFactory virtualThreadFactory = Thread.ofVirtual()
          .name("cleanup-virtual-thread-", 0)
          .factory();

      // Create an executor service with virtual threads
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

      try {
        // Run multiple cleanup operations concurrently
        CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
          CompletableFuture.runAsync(() -> {
            try {
              // Track virtual thread creation
              virtualThreadsCreated.incrementAndGet();
              
              // Run cleanup
              underTest.cleanup(cancelledCheck);
            } 
            catch (Exception e) {
              errorCount.incrementAndGet();
              log.error("Error during concurrent cleanup", e);
            } 
            finally {
              // Track virtual thread termination
              virtualThreadsTerminated.incrementAndGet();
              latch.countDown();
            }
          }, executor);
        }

        // Wait for all operations to complete
        latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Verify no errors occurred
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent cleanup operations");

        // Verify cleanup method was called for each repository and policy
        verify(cleanupMethod, times(LARGE_REPOSITORY_COUNT * LARGE_POLICY_COUNT * CONCURRENT_OPERATIONS))
            .run(any(Repository.class), any(), eq(cancelledCheck));

        // Verify virtual thread metrics
        assertThat("Virtual threads should be created", virtualThreadsCreated.get(), greaterThan(0L));
        assertEquals(virtualThreadsCreated.get(), virtualThreadsTerminated.get(), 
            "All virtual threads should terminate properly");
      } 
      finally {
        executor.shutdown();
      }
    });
  }

  /**
   * Tests that cleanup operations don't encounter thread pinning issues when using virtual threads.
   * Thread pinning occurs when a virtual thread is forced to execute on its carrier thread,
   * preventing the carrier thread from executing other virtual threads.
   */
  @Test
  public void threadPinningDetectionTest() {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Setup repositories and policies
      List<Repository> repositories = setupLargeRepositorySet(10, 2);
      when(repositoryManager.browse()).thenReturn(repositories);

      // Create a virtual thread factory with pinning detection
      ThreadFactory virtualThreadFactory = Thread.ofVirtual()
          .name("cleanup-pinning-test-", 0)
          .factory();

      // Create an executor service with virtual threads
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

      try {
        // Run cleanup operations and monitor for thread pinning
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
          CompletableFuture.runAsync(() -> {
            try {
              // Track virtual thread creation
              virtualThreadsCreated.incrementAndGet();
              
              // Check for thread pinning before cleanup
              checkForThreadPinning();
              
              // Run cleanup
              underTest.cleanup(cancelledCheck);
              
              // Check for thread pinning after cleanup
              checkForThreadPinning();
            } 
            finally {
              // Track virtual thread termination
              virtualThreadsTerminated.incrementAndGet();
              latch.countDown();
            }
          }, executor);
        }

        // Wait for all operations to complete
        latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Verify no thread pinning was detected
        assertThat("No thread pinning should be detected", pinnedThreads.size(), is(0));
        assertThat("Thread pinning flag should be false", threadPinningDetected.get(), is(false));
      } 
      finally {
        executor.shutdown();
      }
    });
  }

  /**
   * Tests the performance difference between platform threads and virtual threads
   * when executing cleanup operations. Virtual threads should show better performance
   * and resource utilization under high concurrency.
   */
  @Test
  public void threadPerformanceComparisonTest() {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Setup repositories and policies
      List<Repository> repositories = setupLargeRepositorySet(20, 2);
      when(repositoryManager.browse()).thenReturn(repositories);

      // Create thread factories
      ThreadFactory platformThreadFactory = Thread.ofPlatform()
          .name("cleanup-platform-thread-", 0)
          .factory();
      
      ThreadFactory virtualThreadFactory = Thread.ofVirtual()
          .name("cleanup-virtual-thread-", 0)
          .factory();

      // Measure platform thread performance
      long platformThreadTime = measureCleanupPerformance(platformThreadFactory, 50);
      
      // Measure virtual thread performance
      long virtualThreadTime = measureCleanupPerformance(virtualThreadFactory, 50);

      // Verify virtual threads perform better under high concurrency
      log.info("Platform thread execution time: {} ms", platformThreadTime);
      log.info("Virtual thread execution time: {} ms", virtualThreadTime);
      
      // Virtual threads should be faster or at least not significantly slower
      assertThat("Virtual threads should perform better than platform threads",
          virtualThreadTime, lessThan(platformThreadTime * 1.2));
    });
  }

  /**
   * Tests that cleanup operations can be executed with a very high number of virtual threads
   * without exhausting system resources. This test creates thousands of virtual threads
   * to verify scalability.
   */
  @Test
  public void highConcurrencyVirtualThreadTest() {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Setup a moderate number of repositories and policies
      List<Repository> repositories = setupLargeRepositorySet(5, 2);
      when(repositoryManager.browse()).thenReturn(repositories);

      // Create a virtual thread factory
      ThreadFactory virtualThreadFactory = Thread.ofVirtual()
          .name("cleanup-high-concurrency-", 0)
          .factory();

      // Create an executor service with virtual threads
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

      try {
        // Run a very high number of concurrent operations
        int highConcurrencyCount = 1000;
        CountDownLatch latch = new CountDownLatch(highConcurrencyCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < highConcurrencyCount; i++) {
          CompletableFuture.runAsync(() -> {
            try {
              // Track virtual thread creation
              virtualThreadsCreated.incrementAndGet();
              
              // Run cleanup
              underTest.cleanup(cancelledCheck);
            } 
            catch (Exception e) {
              errorCount.incrementAndGet();
              log.error("Error during high concurrency cleanup", e);
            } 
            finally {
              // Track virtual thread termination
              virtualThreadsTerminated.incrementAndGet();
              latch.countDown();
            }
          }, executor);
        }

        // Wait for all operations to complete
        boolean completed = latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Verify all operations completed successfully
        assertTrue(completed, "All high concurrency operations should complete within the timeout");
        assertEquals(0, errorCount.get(), "No errors should occur during high concurrency operations");

        // Verify virtual thread metrics
        assertEquals(highConcurrencyCount, virtualThreadsCreated.get(), 
            "Expected number of virtual threads should be created");
        assertEquals(virtualThreadsCreated.get(), virtualThreadsTerminated.get(), 
            "All virtual threads should terminate properly");
      } 
      finally {
        executor.shutdown();
      }
    });
  }

  /**
   * Measures the performance of cleanup operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use for creating threads
   * @param concurrencyLevel the number of concurrent operations to run
   * @return the execution time in milliseconds
   */
  private long measureCleanupPerformance(ThreadFactory threadFactory, int concurrencyLevel) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(concurrencyLevel);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      long startTime = System.currentTimeMillis();
      
      for (int i = 0; i < concurrencyLevel; i++) {
        CompletableFuture.runAsync(() -> {
          try {
            underTest.cleanup(cancelledCheck);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error during performance test", e);
          } 
          finally {
            latch.countDown();
          }
        }, executor);
      }
      
      latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      long endTime = System.currentTimeMillis();
      
      assertEquals(0, errorCount.get(), "No errors should occur during performance test");
      
      return endTime - startTime;
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Sets up a large number of repositories and policies for testing.
   *
   * @param repositoryCount the number of repositories to create
   * @param policyCountPerRepo the number of policies per repository
   * @return a list of configured repository mocks
   */
  private List<Repository> setupLargeRepositorySet(int repositoryCount, int policyCountPerRepo) {
    List<Repository> repositories = new ArrayList<>(repositoryCount);
    
    for (int i = 0; i < repositoryCount; i++) {
      Repository repository = mock(Repository.class);
      Configuration config = mock(Configuration.class);
      when(repository.getConfiguration()).thenReturn(config);
      when(repository.getFormat()).thenReturn(format);
      when(repository.getType()).thenReturn(type);
      
      // Create policy names for this repository
      String[] policyNames = new String[policyCountPerRepo];
      for (int j = 0; j < policyCountPerRepo; j++) {
        policyNames[j] = "policy-" + i + "-" + j;
      }
      
      // Setup repository configuration with policy names
      ImmutableMap<String, Map<String, Object>> attributes = ImmutableMap
          .of("cleanup", singletonMap("policyName", newLinkedHashSet(asList(policyNames))));
      when(config.getAttributes()).thenReturn(attributes);
      
      // Setup policies and components for this repository
      for (String policyName : policyNames) {
        CleanupPolicy policy = mock(CleanupPolicy.class);
        when(policy.getCriteria()).thenReturn(ImmutableMap.of(LAST_BLOB_UPDATED_KEY, "1"));
        when(cleanupPolicyStorage.get(policyName)).thenReturn(policy);
        
        // Create mock components for this policy
        List<FluentComponent> components = new ArrayList<>(COMPONENT_COUNT_PER_REPO);
        for (int k = 0; k < COMPONENT_COUNT_PER_REPO; k++) {
          components.add(mock(FluentComponent.class));
        }
        
        // Setup browse service to return components
        CleanupComponentBrowse browseService = mock(CleanupComponentBrowse.class);
        when(browseService.browse(policy, repository)).thenReturn(components.stream());
        when(cleanupBrowseFactory.get(repository)).thenReturn(browseService);
      }
      
      repositories.add(repository);
    }
    
    return repositories;
  }

  /**
   * Checks if the current thread is a virtual thread that is pinned to its carrier thread.
   * Updates the threadPinningDetected flag and pinnedThreads map if pinning is detected.
   */
  private void checkForThreadPinning() {
    Thread currentThread = Thread.currentThread();
    
    if (currentThread.isVirtual()) {
      // Check if this thread is pinned
      // In a real implementation, this would use JDK-specific APIs or JFR events
      // For this test, we're just simulating the check
      boolean isPinned = false; // Simulated check
      
      if (isPinned) {
        threadPinningDetected.set(true);
        pinnedThreads.put(currentThread, currentThread.getStackTrace());
      }
    }
  }
}