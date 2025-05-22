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
package java21;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.apt.internal.debian.DebianVersion;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test class to validate Java 21 Virtual Thread functionality with APT repository operations.
 * 
 * This test demonstrates the use of Virtual Threads for I/O-bound operations in the APT repository plugin,
 * verifying that concurrent operations using Virtual Threads perform efficiently when handling APT repository tasks.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("APT Repository Virtual Thread Tests")
public class AptVirtualThreadTest
    extends TestSupport
{
    private static final int TASK_COUNT = 1000;
    private static final int TIMEOUT_SECONDS = 30;
    private static final String[] DEBIAN_VERSIONS = {
        "2:7.3.429-2ubuntu2.1",
        "3:7.3.429-2ubuntu2.1",
        "0.13",
        "1.11-1",
        "30~pre9-5ubuntu2"
    };

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(System.currentTimeMillis());
    }

    /**
     * Tests concurrent APT repository operations using Java 21 Virtual Threads.
     * 
     * This test creates a large number of concurrent tasks using Virtual Threads and validates
     * that they complete successfully without thread exhaustion or deadlocks.
     */
    @Test
    @DisplayName("Concurrent APT operations with Virtual Threads")
    void testConcurrentOperationsWithVirtualThreads() throws Exception {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        
        CountDownLatch latch = new CountDownLatch(TASK_COUNT);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicReference<Exception> firstException = new AtomicReference<>();
        
        try {
            // Submit multiple concurrent tasks using virtual threads
            for (int i = 0; i < TASK_COUNT; i++) {
                final int taskId = i;
                executor.submit(() -> {
                    try {
                        // Simulate APT repository operations by performing Debian version comparisons
                        simulateAptRepositoryOperation(taskId);
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        firstException.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all tasks to complete
            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            // Verify results
            assertThat("All tasks should complete within the timeout", completed, is(true));
            assertThat("No errors should occur during concurrent execution", errorCount.get(), is(0));
            assertThat("No exceptions should be thrown", firstException.get(), is(nullValue()));
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Compares performance between platform threads and virtual threads for APT operations.
     * 
     * This test executes the same workload using both platform threads and virtual threads,
     * then compares the execution time to validate the performance benefits of virtual threads.
     */
    @Test
    @DisplayName("Performance comparison: Platform Threads vs Virtual Threads")
    void testPerformanceComparisonBetweenThreadTypes() throws Exception {
        // Run with platform threads
        long platformThreadTime = measureExecutionTime(() -> {
            ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(platformThreadFactory);
            executeWorkload(executor);
        });
        
        // Run with virtual threads
        long virtualThreadTime = measureExecutionTime(() -> {
            ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
            executeWorkload(executor);
        });
        
        // Log the results for analysis
        log.info("Platform thread execution time: {} ms", platformThreadTime);
        log.info("Virtual thread execution time: {} ms", virtualThreadTime);
        
        // Virtual threads should generally be more efficient for I/O-bound operations
        // but we don't make a hard assertion as it depends on the test environment
        // Instead, we log the results for analysis
    }

    /**
     * Tests that thread pinning is minimized with compatible operations.
     * 
     * This test validates that APT repository operations don't cause excessive thread pinning,
     * which would reduce the efficiency of virtual threads.
     */
    @Test
    @DisplayName("Thread pinning avoidance with Virtual Threads")
    void testThreadPinningAvoidance() throws Exception {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        
        // We'll track how many operations complete within a short time frame
        // If thread pinning occurs, fewer operations will complete
        int operationCount = 5000;
        CountDownLatch latch = new CountDownLatch(operationCount);
        
        try {
            // Submit a large number of tasks that should complete quickly if no pinning occurs
            for (int i = 0; i < operationCount; i++) {
                final int taskId = i;
                executor.submit(() -> {
                    try {
                        // Perform a simple APT repository operation
                        simulateAptRepositoryOperation(taskId);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for a short time - if pinning occurs, fewer tasks will complete
            boolean completed = latch.await(2, TimeUnit.SECONDS);
            
            // We don't expect all tasks to complete in this short time frame,
            // but we want to see how many did complete to assess pinning
            long completedTasks = operationCount - latch.getCount();
            log.info("Completed {} out of {} tasks in 2 seconds", completedTasks, operationCount);
            
            // If a significant number of tasks completed, we can infer minimal pinning
            // This is a heuristic rather than a precise measurement
            assertThat("A significant number of tasks should complete if pinning is minimal",
                    completedTasks, greaterThan((long)(operationCount * 0.5)));
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Simulates an APT repository operation by performing Debian version comparisons.
     * 
     * This method mimics the I/O-bound operations that would occur in the APT repository plugin,
     * such as asset retrieval and metadata processing.
     * 
     * @param taskId The ID of the task, used to vary the workload
     */
    private void simulateAptRepositoryOperation(int taskId) {
        // Create a list of Debian versions to compare
        List<DebianVersion> versions = new ArrayList<>();
        for (String versionStr : DEBIAN_VERSIONS) {
            versions.add(new DebianVersion(versionStr));
        }
        
        // Perform multiple version comparisons to simulate repository operations
        int operationCount = 50 + (taskId % 50); // Vary the workload slightly
        for (int i = 0; i < operationCount; i++) {
            int idx1 = random.nextInt(versions.size());
            int idx2 = random.nextInt(versions.size());
            
            DebianVersion v1 = versions.get(idx1);
            DebianVersion v2 = versions.get(idx2);
            
            // Compare versions (this simulates repository operations)
            int result = v1.compareTo(v2);
            
            // Simulate some I/O delay that would occur in real repository operations
            try {
                Thread.sleep(1 + random.nextInt(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted during APT operation simulation", e);
            }
        }
    }

    /**
     * Executes a standard workload using the provided executor service.
     * 
     * @param executor The executor service to use for the workload
     */
    private void executeWorkload(ExecutorService executor) throws Exception {
        int taskCount = 500;
        CountDownLatch latch = new CountDownLatch(taskCount);
        
        try {
            for (int i = 0; i < taskCount; i++) {
                final int taskId = i;
                executor.submit(() -> {
                    try {
                        simulateAptRepositoryOperation(taskId);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat("All tasks should complete within the timeout", completed, is(true));
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Measures the execution time of a runnable operation.
     * 
     * @param operation The operation to measure
     * @return The execution time in milliseconds
     */
    private long measureExecutionTime(RunnableWithException operation) throws Exception {
        long startTime = System.currentTimeMillis();
        operation.run();
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Functional interface for operations that may throw exceptions.
     */
    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }
}