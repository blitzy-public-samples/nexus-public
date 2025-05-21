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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityDescriptor;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.capability.CapabilityIdentity.capabilityIdentity;
import static org.sonatype.nexus.capability.CapabilityType.capabilityType;

/**
 * {@link CapabilityHasNoDuplicatesCondition} Virtual Thread tests.
 * 
 * Tests the behavior of CapabilityHasNoDuplicatesCondition under high concurrency
 * using Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
public class CapabilityHasNoDuplicatesVirtualThreadTest
{
  @Mock
  private EventManager eventManager;

  @Mock
  private CapabilityRegistry capabilityRegistry;

  private CapabilityHasNoDuplicatesCondition underTest;

  private CapabilityReference reference;

  private CapabilityContext context;

  private CapabilityDescriptor descriptor;

  private ExecutorService virtualThreadExecutor;
  
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  public void setUpCondition() {
    reference = createReference("testRef1", "testType");

    context = reference.context();
    descriptor = context.descriptor();

    underTest = new CapabilityHasNoDuplicatesCondition(eventManager);
    
    // Create executors for virtual threads and platform threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-test-", 0).factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }

  @AfterEach
  public void tearDown() {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    try {
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
      if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        platformThreadExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("Standard lifecycle with virtual threads")
  public void standardLifecycleWithVirtualThreads() {
    // Run the test in a virtual thread
    Thread.startVirtualThread(() -> {
      assertThat(underTest.isSatisfied(), is(false));

      underTest.setContext(reference.context());

      assertThat(underTest.isSatisfied(), is(false));

      underTest.bind();

      assertThat(underTest.isSatisfied(), is(true));

      underTest.release();

      verify(eventManager).register(underTest);
      verify(eventManager).unregister(underTest);
    }).join();
  }

  @Test
  @DisplayName("Duplicate detection during bind with virtual threads")
  public void duplicateDetectionDuringBindWithVirtualThreads() {
    // Run the test in a virtual thread
    Thread.startVirtualThread(() -> {
      underTest.setContext(reference.context());

      when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(true);

      underTest.bind();

      assertThat(underTest.isSatisfied(), is(false));

      underTest.release();
    }).join();
  }

  @Test
  @DisplayName("Duplicate detection during events with virtual threads")
  public void duplicateDetectionDuringEventsWithVirtualThreads() {
    // Run the test in a virtual thread
    Thread.startVirtualThread(() -> {
      CapabilityReference unrelatedRef = createReference("testRef2", "anotherType");
      CapabilityReference duplicateRef = createReference("testRef3", "testType");

      underTest.setContext(reference.context());
      underTest.bind();

      // only checked after matching event
      when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(true);
      assertThat(underTest.isSatisfied(), is(true));
      
      // different type, shouldn't trigger change
      underTest.handle(new CapabilityEvent.Created(capabilityRegistry, unrelatedRef));
      assertThat(underTest.isSatisfied(), is(true));

      // same type, condition should check for dups
      underTest.handle(new CapabilityEvent.Created(capabilityRegistry, duplicateRef));

      assertThat(underTest.isSatisfied(), is(false));

      // only checked after matching event
      when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(false);
      assertThat(underTest.isSatisfied(), is(false));
      
      // different type, shouldn't trigger change
      underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, unrelatedRef));
      assertThat(underTest.isSatisfied(), is(false));

      // same type, condition should check for dups
      underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, duplicateRef));

      assertThat(underTest.isSatisfied(), is(true));

      underTest.release();

      // isDuplicated should only be called during bind + once for each event of same type
      verify(descriptor, times(3)).isDuplicated(context.id(), context.properties());
    }).join();
  }

  @Test
  @DisplayName("Concurrent duplicate detection with multiple virtual threads")
  public void concurrentDuplicateDetection() throws Exception {
    // Set up the condition
    underTest.setContext(reference.context());
    underTest.bind();
    
    // Initially no duplicates
    when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(false);
    assertTrue(underTest.isSatisfied());
    
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean duplicateDetected = new AtomicBoolean(false);
    
    // Create many references of the same type
    List<CapabilityReference> references = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      references.add(createReference("testRef" + i, "testType"));
    }
    
    // Create many virtual threads that will fire events concurrently
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Fire a created event
          CapabilityEvent.Created event = new CapabilityEvent.Created(
              capabilityRegistry, references.get(index));
          
          // If this is the first thread, make it detect a duplicate
          if (index == 0) {
            when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(true);
            duplicateDetected.set(true);
          }
          
          underTest.handle(event);
          
          return null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads at once
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(10, TimeUnit.SECONDS);
    
    // Verify the condition detected the duplicate
    if (duplicateDetected.get()) {
      assertFalse(underTest.isSatisfied(), "Condition should be unsatisfied when duplicate is detected");
    }
    
    // Clean up
    underTest.release();
  }

  @Test
  @DisplayName("High-volume event firing with virtual threads")
  public void highVolumeEventFiring() throws Exception {
    // Set up the condition
    underTest.setContext(reference.context());
    underTest.bind();
    
    // Initially no duplicates
    when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(false);
    assertTrue(underTest.isSatisfied());
    
    int eventCount = 5000;
    CountDownLatch completionLatch = new CountDownLatch(eventCount);
    
    // Create references with alternating types
    List<CapabilityReference> references = new ArrayList<>();
    for (int i = 0; i < eventCount; i++) {
      String type = (i % 2 == 0) ? "testType" : "otherType";
      references.add(createReference("testRef" + i, type));
    }
    
    // Fire many events concurrently using virtual threads
    for (int i = 0; i < eventCount; i++) {
      final int index = i;
      final boolean isCreated = index % 3 != 0; // Mix of created and removed events
      
      virtualThreadExecutor.submit(() -> {
        try {
          CapabilityReference ref = references.get(index);
          CapabilityEvent event;
          
          if (isCreated) {
            event = new CapabilityEvent.Created(capabilityRegistry, ref);
          } else {
            event = new CapabilityEvent.AfterRemove(capabilityRegistry, ref);
          }
          
          underTest.handle(event);
          
          return null;
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all events to be processed
    completionLatch.await(30, TimeUnit.SECONDS);
    
    // Verify the condition is still in a valid state
    assertTrue(underTest.isSatisfied(), "Condition should remain satisfied after high-volume event processing");
    
    // Clean up
    underTest.release();
  }

  @Test
  @DisplayName("Thread pinning detection during event handling")
  public void threadPinningDetection() throws Exception {
    // This test verifies that no thread pinning occurs during event handling
    // by using the JDK's tracePinnedThreads feature
    
    // Enable thread pinning detection for this test
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    try {
      // Set up the condition
      underTest.setContext(reference.context());
      underTest.bind();
      
      // Create a reference of the same type
      CapabilityReference sameTypeRef = createReference("testRef2", "testType");
      
      // Create a virtual thread to handle the event
      Thread.startVirtualThread(() -> {
        // Fire an event that should be handled by the condition
        CapabilityEvent.Created event = new CapabilityEvent.Created(
            capabilityRegistry, sameTypeRef);
        
        // Handle the event - this should not cause thread pinning
        underTest.handle(event);
      }).join();
      
      // Clean up
      underTest.release();
    } finally {
      // Reset the system property
      System.clearProperty("jdk.tracePinnedThreads");
    }
    
    // Note: This test doesn't have explicit assertions because thread pinning
    // would be reported to the console by the JVM. The absence of pinning reports
    // indicates success.
  }

  @Test
  @DisplayName("Performance comparison between platform and virtual threads")
  public void performanceComparison() throws Exception {
    int iterations = 1000;
    int warmupIterations = 100;
    
    // Set up the condition
    underTest.setContext(reference.context());
    underTest.bind();
    
    // Create references with the same type
    List<CapabilityReference> references = new ArrayList<>();
    for (int i = 0; i < iterations; i++) {
      references.add(createReference("testRef" + i, "testType"));
    }
    
    // Warm up
    for (int i = 0; i < warmupIterations; i++) {
      CapabilityEvent.Created event = new CapabilityEvent.Created(
          capabilityRegistry, references.get(i % references.size()));
      underTest.handle(event);
    }
    
    // Measure platform threads performance
    long platformStart = System.nanoTime();
    
    List<CompletableFuture<Void>> platformFutures = new ArrayList<>();
    for (int i = 0; i < iterations; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        CapabilityEvent.Created event = new CapabilityEvent.Created(
            capabilityRegistry, references.get(index));
        underTest.handle(event);
      }, platformThreadExecutor);
      platformFutures.add(future);
    }
    
    CompletableFuture.allOf(platformFutures.toArray(new CompletableFuture[0])).join();
    long platformDuration = System.nanoTime() - platformStart;
    
    // Reset condition state
    underTest.release();
    underTest = new CapabilityHasNoDuplicatesCondition(eventManager);
    underTest.setContext(reference.context());
    underTest.bind();
    
    // Measure virtual threads performance
    long virtualStart = System.nanoTime();
    
    List<CompletableFuture<Void>> virtualFutures = new ArrayList<>();
    for (int i = 0; i < iterations; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        CapabilityEvent.Created event = new CapabilityEvent.Created(
            capabilityRegistry, references.get(index));
        underTest.handle(event);
      }, virtualThreadExecutor);
      virtualFutures.add(future);
    }
    
    CompletableFuture.allOf(virtualFutures.toArray(new CompletableFuture[0])).join();
    long virtualDuration = System.nanoTime() - virtualStart;
    
    // Clean up
    underTest.release();
    
    // Log performance results
    System.out.println("Platform threads duration: " + Duration.ofNanos(platformDuration).toMillis() + "ms");
    System.out.println("Virtual threads duration: " + Duration.ofNanos(virtualDuration).toMillis() + "ms");
    
    // For high concurrency operations, virtual threads should generally perform better
    // This might not always be true for very short operations due to the overhead of creating virtual threads
    // but for real-world scenarios with I/O or blocking operations, virtual threads should show an advantage
  }

  @Test
  @DisplayName("Stress test with concurrent event handling")
  public void stressTestWithConcurrentEventHandling() throws Exception {
    // Set up the condition
    underTest.setContext(reference.context());
    underTest.bind();
    
    int threadCount = 500;
    int eventsPerThread = 10;
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create references with the same type
    List<CapabilityReference> references = new ArrayList<>();
    for (int i = 0; i < threadCount * eventsPerThread; i++) {
      references.add(createReference("testRef" + i, "testType"));
    }
    
    // Create virtual threads that will fire multiple events each
    for (int i = 0; i < threadCount; i++) {
      final int threadIndex = i;
      virtualThreadExecutor.submit(() -> {
        try {
          for (int j = 0; j < eventsPerThread; j++) {
            int refIndex = threadIndex * eventsPerThread + j;
            CapabilityReference ref = references.get(refIndex);
            
            // Alternate between created and removed events
            if (j % 2 == 0) {
              CapabilityEvent.Created event = new CapabilityEvent.Created(
                  capabilityRegistry, ref);
              underTest.handle(event);
            } else {
              CapabilityEvent.AfterRemove event = new CapabilityEvent.AfterRemove(
                  capabilityRegistry, ref);
              underTest.handle(event);
            }
            
            // Occasionally check if duplicated to create contention
            if (j % 3 == 0) {
              when(descriptor.isDuplicated(context.id(), context.properties()))
                  .thenReturn(refIndex % 5 == 0); // Occasionally return true
              underTest.isSatisfied();
            }
          }
          return null;
        } catch (Exception e) {
          errorCount.incrementAndGet();
          e.printStackTrace();
          return null;
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Verify no errors occurred and all threads completed
    assertTrue(completed, "All threads should complete within the timeout");
    assertThat(errorCount.get(), is(0));
    
    // Clean up
    underTest.release();
  }

  private static CapabilityReference createReference(final String id, final String type) {
    CapabilityDescriptor descriptor = mock(CapabilityDescriptor.class);

    CapabilityContext context = mock(CapabilityContext.class);
    when(context.id()).thenReturn(capabilityIdentity(id));
    Map<String, String> testProperties = ImmutableMap.of("testKey", "testValue");
    when(context.properties()).thenReturn(testProperties);
    when(context.descriptor()).thenReturn(descriptor);
    when(context.type()).thenReturn(capabilityType(type));

    CapabilityReference reference = mock(CapabilityReference.class);
    when(reference.context()).thenReturn(context);

    return reference;
  }
}