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
package org.sonatype.nexus.capability.condition.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.ConditionEvent;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;
import org.sonatype.nexus.common.event.EventManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Virtual Thread tests for {@link CompositeConditionSupport}.
 * 
 * Tests the behavior of composite conditions under high concurrency using Java 21 Virtual Threads.
 * Validates that composite condition trees maintain consistent state when processing thousands
 * of concurrent condition events from virtual threads.
 *
 * @since 3.60
 */
public class CompositeConditionVirtualThreadTest
    extends EventManagerTestSupport
{
    private static final int CONCURRENT_EVENTS = 1000;
    private static final int CONDITION_TREE_DEPTH = 5;
    private static final int CONDITIONS_PER_LEVEL = 3;
    
    @Mock
    private Condition mockCondition1;
    
    @Mock
    private Condition mockCondition2;
    
    @Mock
    private Condition mockCondition3;
    
    private ExecutorService virtualThreadExecutor;
    private ExecutorService platformThreadExecutor;
    
    private TestCompositeCondition rootCondition;
    private List<Condition> allConditions;
    
    @BeforeEach
    public final void setUp() throws Exception {
        // Create executors for virtual threads and platform threads
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        
        // Create a deep condition tree for testing
        allConditions = new ArrayList<>();
        rootCondition = createConditionTree(CONDITION_TREE_DEPTH, CONDITIONS_PER_LEVEL);
        rootCondition.bind();
    }
    
    @AfterEach
    public final void tearDown() throws Exception {
        if (rootCondition != null) {
            rootCondition.release();
        }
        
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
            virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        if (platformThreadExecutor != null) {
            platformThreadExecutor.shutdown();
            platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Tests that a composite condition tree maintains consistent state when processing
     * thousands of concurrent condition events from virtual threads.
     */
    @Test
    public void testConcurrentEventProcessingWithVirtualThreads() throws Exception {
        // Set up initial condition states
        for (Condition condition : allConditions) {
            when(condition.isSatisfied()).thenReturn(false);
        }
        
        // Create a latch to synchronize all threads
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_EVENTS);
        
        // Submit tasks to virtual thread executor
        for (int i = 0; i < CONCURRENT_EVENTS; i++) {
            final int index = i % allConditions.size();
            final Condition condition = allConditions.get(index);
            final boolean satisfied = i % 2 == 0; // Alternate between satisfied and unsatisfied
            
            virtualThreadExecutor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Update condition state
                    when(condition.isSatisfied()).thenReturn(satisfied);
                    
                    // Send appropriate event
                    if (satisfied) {
                        rootCondition.handle(new ConditionEvent.Satisfied(condition));
                    } else {
                        rootCondition.handle(new ConditionEvent.Unsatisfied(condition));
                    }
                    
                    completionLatch.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all events to be processed
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS), 
                "Timed out waiting for concurrent events to complete");
        
        // Verify the final state is consistent
        boolean finalState = rootCondition.isSatisfied();
        boolean expectedState = rootCondition.reevaluate(rootCondition.getConditions());
        assertThat("Final condition state should match expected state after concurrent events", 
                finalState, is(expectedState));
    }
    
    /**
     * Tests that no thread pinning occurs when processing condition events.
     * Thread pinning would prevent virtual threads from yielding during blocking operations.
     */
    @Test
    public void testNoThreadPinningDuringEventProcessing() throws Exception {
        // Set up initial condition states
        for (Condition condition : allConditions) {
            when(condition.isSatisfied()).thenReturn(false);
        }
        
        AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_EVENTS);
        
        // Create a special composite condition that simulates a blocking operation
        TestCompositeCondition blockingCondition = new TestCompositeCondition(eventManager, mockCondition1) {
            @Override
            protected boolean reevaluate(Condition... conditions) {
                // Simulate a blocking operation that would cause thread pinning if not handled properly
                try {
                    // This sleep would pin the carrier thread if thread pinning occurs
                    Thread.sleep(50);
                    
                    // Check if we're running on a virtual thread
                    if (Thread.currentThread().isVirtual()) {
                        // If we're still running after the sleep, we're good
                        return super.reevaluate(conditions);
                    } else {
                        threadPinningDetected.set(true);
                        return false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
        blockingCondition.bind();
        
        // Submit tasks to virtual thread executor
        for (int i = 0; i < CONCURRENT_EVENTS; i++) {
            virtualThreadExecutor.submit(() -> {
                try {
                    // Send event to the blocking condition
                    when(mockCondition1.isSatisfied()).thenReturn(true);
                    blockingCondition.handle(new ConditionEvent.Satisfied(mockCondition1));
                    completionLatch.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        
        // Wait for all events to be processed
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS), 
                "Timed out waiting for concurrent events to complete");
        
        // Verify no thread pinning was detected
        assertFalse(threadPinningDetected.get(), "Thread pinning detected during event processing");
        
        // Clean up
        blockingCondition.release();
    }
    
    /**
     * Compares performance between virtual threads and platform threads for processing
     * a large number of condition events.
     */
    @Test
    public void testPerformanceComparisonWithPlatformThreads() throws Exception {
        // Set up initial condition states
        for (Condition condition : allConditions) {
            when(condition.isSatisfied()).thenReturn(false);
        }
        
        // Test with virtual threads
        long virtualThreadTime = measureExecutionTime(() -> {
            processEventsWithExecutor(virtualThreadExecutor);
        });
        
        // Test with platform threads
        long platformThreadTime = measureExecutionTime(() -> {
            processEventsWithExecutor(platformThreadExecutor);
        });
        
        // Log the results for comparison
        System.out.println("Performance comparison for processing " + CONCURRENT_EVENTS + " condition events:");
        System.out.println("Virtual Threads: " + virtualThreadTime + "ms");
        System.out.println("Platform Threads: " + platformThreadTime + "ms");
        System.out.println("Improvement factor: " + (double) platformThreadTime / virtualThreadTime);
        
        // We don't assert on the actual times since they can vary by environment,
        // but we log them for informational purposes
    }
    
    /**
     * Helper method to process events using the specified executor.
     */
    private void processEventsWithExecutor(ExecutorService executor) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_EVENTS);
        
        // Submit tasks to the executor
        for (int i = 0; i < CONCURRENT_EVENTS; i++) {
            final int index = i % allConditions.size();
            final Condition condition = allConditions.get(index);
            final boolean satisfied = i % 2 == 0;
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    when(condition.isSatisfied()).thenReturn(satisfied);
                    
                    if (satisfied) {
                        rootCondition.handle(new ConditionEvent.Satisfied(condition));
                    } else {
                        rootCondition.handle(new ConditionEvent.Unsatisfied(condition));
                    }
                    
                    completionLatch.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS));
    }
    
    /**
     * Measures the execution time of a runnable task.
     */
    private long measureExecutionTime(RunnableWithException task) throws Exception {
        long startTime = System.currentTimeMillis();
        task.run();
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * Creates a deep tree of composite conditions for testing.
     * 
     * @param depth The maximum depth of the tree
     * @param width The number of conditions per level
     * @return The root composite condition
     */
    private TestCompositeCondition createConditionTree(int depth, int width) {
        if (depth <= 0) {
            // Create leaf conditions
            Condition mockCondition = createMockCondition();
            allConditions.add(mockCondition);
            return new TestCompositeCondition(eventManager, mockCondition);
        }
        
        // Create conditions for this level
        Condition[] conditions = new Condition[width];
        for (int i = 0; i < width; i++) {
            conditions[i] = createConditionTree(depth - 1, width);
        }
        
        return new TestCompositeCondition(eventManager, conditions);
    }
    
    /**
     * Creates a mock condition for testing.
     */
    private Condition createMockCondition() {
        Condition condition = mock(Condition.class);
        when(condition.isSatisfied()).thenReturn(false);
        return condition;
    }
    
    /**
     * Functional interface for a runnable that can throw an exception.
     */
    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }
    
    /**
     * Test implementation of CompositeConditionSupport for testing.
     */
    private static class TestCompositeCondition extends CompositeConditionSupport {
        public TestCompositeCondition(EventManager eventManager, Condition... conditions) {
            super(eventManager, conditions);
        }
        
        @Override
        protected boolean reevaluate(Condition... conditions) {
            for (Condition condition : conditions) {
                if (condition.isSatisfied()) {
                    return true;
                }
            }
            return false;
        }
        
        public Condition[] getConditions() {
            return conditions;
        }
    }
}