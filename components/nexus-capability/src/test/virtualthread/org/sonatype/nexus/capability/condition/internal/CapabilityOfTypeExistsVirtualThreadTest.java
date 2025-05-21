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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityDescriptor;
import org.sonatype.nexus.capability.CapabilityDescriptorRegistry;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.capability.CapabilityType.capabilityType;

/**
 * Tests {@link CapabilityOfTypeExistsCondition} under high concurrency using Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@EnabledForJreRange(min = JRE.JAVA_21)
public class CapabilityOfTypeExistsVirtualThreadTest
    extends EventManagerTestSupport
{
  private static final int CONCURRENT_EVENTS = 10_000;
  private static final int WARMUP_EVENTS = 1_000;
  
  @Mock
  private CapabilityReference ref1;

  @Mock
  private CapabilityReference ref2;

  @Mock
  private CapabilityRegistry capabilityRegistry;

  private CapabilityOfTypeExistsCondition underTest;
  
  private CapabilityType capabilityType;
  
  private ThreadPinningDetector threadPinningDetector;

  @BeforeEach
  public final void setUpCapabilityOfTypeExistsCondition()
      throws Exception
  {
    capabilityType = capabilityType(this.getClass().getName());

    when(ref1.context()).thenReturn(mock(CapabilityContext.class));
    when(ref1.context().type()).thenReturn(capabilityType);

    when(ref2.context()).thenReturn(mock(CapabilityContext.class));
    when(ref2.context().type()).thenReturn(capabilityType);

    final CapabilityDescriptorRegistry descriptorRegistry = mock(CapabilityDescriptorRegistry.class);
    final CapabilityDescriptor descriptor = mock(CapabilityDescriptor.class);

    when(descriptor.name()).thenReturn(this.getClass().getSimpleName());
    when(descriptorRegistry.get(capabilityType)).thenReturn(descriptor);

    underTest = new CapabilityOfTypeExistsCondition(
        eventManager, descriptorRegistry, capabilityRegistry, capabilityType
    );
    underTest.bind();

    verify(eventManager).register(underTest);
    
    threadPinningDetector = new ThreadPinningDetector();
  }

  /**
   * Tests that the condition correctly handles thousands of concurrent capability events
   * using Virtual Threads, ensuring thread safety and correct state transitions.
   */
  @Test
  public void handlesConcurrentCapabilityEventsWithVirtualThreads() throws Exception {
    // Initially condition should be unsatisfied
    assertThat(underTest.isSatisfied(), is(false));
    
    // Create a large number of mock capability references
    List<CapabilityReference> references = createMockReferences(CONCURRENT_EVENTS);
    
    // Start thread pinning detection
    threadPinningDetector.start();
    
    try {
      // Process events using virtual threads
      processConcurrentEvents(references, true);
      
      // Verify final state - should be satisfied since we have capabilities
      assertTrue(underTest.isSatisfied());
      
      // Now remove all capabilities
      doReturn(Collections.emptyList()).when(capabilityRegistry).getAll();
      underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, references.get(0)));
      
      // Verify final state - should be unsatisfied since all capabilities are removed
      assertFalse(underTest.isSatisfied());
      
      // Verify no thread pinning occurred
      assertFalse(threadPinningDetector.isPinned(), 
          "Thread pinning detected during virtual thread execution");
    } finally {
      threadPinningDetector.stop();
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads when processing
   * capability events.
   */
  @Test
  public void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    // Create references for testing
    List<CapabilityReference> references = createMockReferences(CONCURRENT_EVENTS);
    
    // Warm up to avoid JIT compilation effects
    List<CapabilityReference> warmupReferences = createMockReferences(WARMUP_EVENTS);
    processConcurrentEvents(warmupReferences, true);
    processConcurrentEvents(warmupReferences, false);
    
    // Reset condition state
    doReturn(Collections.emptyList()).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, warmupReferences.get(0)));
    assertFalse(underTest.isSatisfied());
    
    // Measure platform threads performance
    long platformStart = System.nanoTime();
    processConcurrentEvents(references, false);
    long platformDuration = System.nanoTime() - platformStart;
    
    // Reset condition state
    doReturn(Collections.emptyList()).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, references.get(0)));
    assertFalse(underTest.isSatisfied());
    
    // Measure virtual threads performance
    long virtualStart = System.nanoTime();
    processConcurrentEvents(references, true);
    long virtualDuration = System.nanoTime() - virtualStart;
    
    // Log performance comparison
    System.out.println(STR."Platform threads processing time: \{Duration.ofNanos(platformDuration)}");
    System.out.println(STR."Virtual threads processing time: \{Duration.ofNanos(virtualDuration)}");
    System.out.println(STR."Performance ratio (platform/virtual): \{(double) platformDuration / virtualDuration}");
  }
  
  /**
   * Tests that the condition correctly maintains state consistency when processing
   * events in a specific order with virtual threads.
   */
  @Test
  public void maintainsStateConsistencyWithOrderedEvents() throws Exception {
    // Initially condition should be unsatisfied
    assertThat(underTest.isSatisfied(), is(false));
    
    // Create references and track state changes
    List<CapabilityReference> references = createMockReferences(100);
    AtomicBoolean stateInconsistencyDetected = new AtomicBoolean(false);
    AtomicInteger activeCapabilities = new AtomicInteger(0);
    
    // Use a concurrent map to track expected state at each point
    ConcurrentHashMap<Integer, Boolean> expectedStates = new ConcurrentHashMap<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(references.size() * 2); // create + remove events
      
      // First add all capabilities
      for (int i = 0; i < references.size(); i++) {
        final int index = i;
        final CapabilityReference ref = references.get(i);
        
        executor.submit(() -> {
          try {
            // Update registry state
            List<CapabilityReference> currentRefs = new ArrayList<>();
            for (int j = 0; j <= index; j++) {
              currentRefs.add(references.get(j));
            }
            doReturn(currentRefs).when(capabilityRegistry).getAll();
            
            // Process event
            underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref));
            
            // Track state
            int active = activeCapabilities.incrementAndGet();
            boolean shouldBeSatisfied = active > 0;
            expectedStates.put(active, shouldBeSatisfied);
            
            // Check for state inconsistency
            if (underTest.isSatisfied() != shouldBeSatisfied) {
              stateInconsistencyDetected.set(true);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Then remove all capabilities in reverse order
      for (int i = references.size() - 1; i >= 0; i--) {
        final int index = i;
        final CapabilityReference ref = references.get(i);
        
        executor.submit(() -> {
          try {
            // Update registry state
            List<CapabilityReference> currentRefs = new ArrayList<>();
            for (int j = 0; j < index; j++) {
              currentRefs.add(references.get(j));
            }
            doReturn(currentRefs).when(capabilityRegistry).getAll();
            
            // Process event
            underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, ref));
            
            // Track state
            int active = activeCapabilities.decrementAndGet();
            boolean shouldBeSatisfied = active > 0;
            expectedStates.put(active, shouldBeSatisfied);
            
            // Check for state inconsistency
            if (underTest.isSatisfied() != shouldBeSatisfied) {
              stateInconsistencyDetected.set(true);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all events to be processed
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for events to process");
    }
    
    // Verify no state inconsistencies were detected
    assertFalse(stateInconsistencyDetected.get(), "State inconsistency detected during event processing");
    
    // Final state should be unsatisfied (all capabilities removed)
    assertFalse(underTest.isSatisfied());
  }

  /**
   * Creates mock capability references for testing.
   */
  private List<CapabilityReference> createMockReferences(int count) {
    List<CapabilityReference> references = new ArrayList<>(count);
    
    for (int i = 0; i < count; i++) {
      CapabilityReference ref = mock(CapabilityReference.class);
      CapabilityContext context = mock(CapabilityContext.class);
      
      when(ref.context()).thenReturn(context);
      when(context.type()).thenReturn(capabilityType);
      when(context.isActive()).thenReturn(true);
      
      // Use a unique ID for each reference
      String id = UUID.randomUUID().toString();
      when(ref.toString()).thenReturn(STR."CapabilityReference[\{id}]");
      
      references.add(ref);
    }
    
    return references;
  }
  
  /**
   * Processes capability events concurrently using either platform or virtual threads.
   */
  private void processConcurrentEvents(List<CapabilityReference> references, boolean useVirtualThreads) 
      throws Exception {
    ExecutorService executor = useVirtualThreads ? 
        Executors.newVirtualThreadPerTaskExecutor() : 
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), 
            new ThreadFactory() {
              private final AtomicInteger counter = new AtomicInteger();
              
              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName(STR."platform-thread-\{counter.incrementAndGet()}");
                return t;
              }
            });
    
    try {
      CountDownLatch latch = new CountDownLatch(references.size());
      
      // Submit tasks to process events
      for (CapabilityReference ref : references) {
        executor.submit(() -> {
          try {
            // Update registry state to include this reference
            doReturn(Arrays.asList(ref)).when(capabilityRegistry).getAll();
            
            // Process event
            underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref));
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all events to be processed
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for events to process");
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}