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
package org.sonatype.nexus.extender.modules.virtualthread;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.extender.modules.internal.CachedGaugeInjectionListener;
import org.sonatype.nexus.extender.modules.internal.CachedGaugeTypeListener;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.annotation.CachedGauge;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.palominolabs.metrics.guice.DefaultMetricNamer;
import com.palominolabs.metrics.guice.annotation.MethodAnnotationResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the {@link CachedGaugeTypeListener} compatibility with Java 21 Virtual Threads.
 * 
 * This test validates that metrics instrumentation and caching operate correctly in a virtual thread environment.
 */
@ExtendWith(MockitoExtension.class)
class CachedGaugeTypeListenerVirtualThreadTest
{
  private final TypeLiteral<TestClass> testTypeLiteral = new TypeLiteral<TestClass>() { };

  private final Map<String, String> mockProperties = new HashMap<>();

  @Captor
  ArgumentCaptor<InjectionListener<? super TestClass>> injectionListenerArgumentCaptor;

  @Mock
  private MetricRegistry mockMetricRegistry;

  @Mock
  private TypeEncounter<TestClass> mockTypeEncounter;

  private CachedGaugeTypeListener underTest;

  @BeforeEach
  void setup() {
    // Enable thread pinning detection for virtual threads
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    underTest = spy(new CachedGaugeTypeListener(mockMetricRegistry, new DefaultMetricNamer(), 
        new MethodAnnotationResolver(), mockProperties));
  }

  @Test
  void testVirtualThreadGaugeRegistration() {
    // Create a virtual thread to execute the gauge registration
    Thread virtualThread = Thread.ofVirtual().name("gauge-registration-thread").start(() -> {
      underTest.hear(testTypeLiteral, mockTypeEncounter);
    });
    
    try {
      // Wait for the virtual thread to complete
      virtualThread.join(5000);
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    // Verify that the gauge was registered correctly
    verify(mockTypeEncounter, times(1)).register(isA(CachedGaugeInjectionListener.class));
  }

  @Test
  void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            underTest.hear(testTypeLiteral, mockTypeEncounter);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All virtual thread tasks should complete within timeout");
      assertThat(errorCount.get(), is(0));
    } 
    finally {
      executor.shutdown();
    }
  }

  @Test
  void testThreadPinningDetection() throws Exception {
    // Capture any thread pinning events during reflection operations
    AtomicReference<String> pinnedThreadInfo = new AtomicReference<>(null);
    Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
    
    try {
      // Set a handler to capture thread pinning warnings
      Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
        if (throwable.getMessage() != null && throwable.getMessage().contains("Virtual thread pinned")) {
          pinnedThreadInfo.set(throwable.getMessage());
        }
        if (originalHandler != null) {
          originalHandler.uncaughtException(thread, throwable);
        }
      });
      
      // Execute the test with a virtual thread
      Thread virtualThread = Thread.ofVirtual().name("reflection-test-thread").start(() -> {
        underTest.hear(testTypeLiteral, mockTypeEncounter);
        verify(mockTypeEncounter).register(injectionListenerArgumentCaptor.capture());
        
        // Access fields via reflection which might cause pinning
        try {
          InjectionListener<? super TestClass> listener = injectionListenerArgumentCaptor.getValue();
          Field timeoutField = CachedGaugeInjectionListener.class.getDeclaredField("timeout");
          timeoutField.setAccessible(true);
          timeoutField.getLong(listener);
          
          Field timeUnitField = CachedGaugeInjectionListener.class.getDeclaredField("timeUnit");
          timeUnitField.setAccessible(true);
          timeUnitField.get(listener);
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      
      virtualThread.join(5000);
      
      // If thread pinning occurred, the test should log it but not fail
      // This is informational to help identify areas for optimization
      if (pinnedThreadInfo.get() != null) {
        System.out.println("Thread pinning detected: " + pinnedThreadInfo.get());
      }
    } 
    finally {
      Thread.setDefaultUncaughtExceptionHandler(originalHandler);
    }
  }

  @Test
  void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    int iterations = 1000;
    
    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(() -> {
      ExecutorService platformExecutor = Executors.newFixedThreadPool(16);
      try {
        CountDownLatch platformLatch = new CountDownLatch(iterations);
        for (int i = 0; i < iterations; i++) {
          platformExecutor.submit(() -> {
            try {
              underTest.hear(testTypeLiteral, mockTypeEncounter);
            } 
            finally {
              platformLatch.countDown();
            }
          });
        }
        platformLatch.await(30, TimeUnit.SECONDS);
      } 
      finally {
        platformExecutor.shutdown();
      }
    });
    
    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(() -> {
      ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        CountDownLatch virtualLatch = new CountDownLatch(iterations);
        for (int i = 0; i < iterations; i++) {
          virtualExecutor.submit(() -> {
            try {
              underTest.hear(testTypeLiteral, mockTypeEncounter);
            } 
            finally {
              virtualLatch.countDown();
            }
          });
        }
        virtualLatch.await(30, TimeUnit.SECONDS);
      } 
      finally {
        virtualExecutor.shutdown();
      }
    });
    
    System.out.println("Platform thread execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual thread execution time: " + virtualThreadTime + "ms");
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }
  
  private long measureExecutionTime(Runnable task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }

  @Test
  void testTimeoutOverrideWithVirtualThread() throws Exception {
    mockProperties.put("test.annotated.cache.timeout", "42");
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("timeout-override-thread").start(() -> {
      underTest.hear(testTypeLiteral, mockTypeEncounter);
    });
    
    virtualThread.join(5000);
    
    verify(mockTypeEncounter).register(injectionListenerArgumentCaptor.capture());
    assertThat(getTimeout(injectionListenerArgumentCaptor.getValue()), is(42L));
  }

  @Test
  void testTimeUnitOverrideWithVirtualThread() throws Exception {
    mockProperties.put("test.annotated.cache.timeUnit", "minutes");
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("timeunit-override-thread").start(() -> {
      underTest.hear(testTypeLiteral, mockTypeEncounter);
    });
    
    virtualThread.join(5000);
    
    verify(mockTypeEncounter).register(injectionListenerArgumentCaptor.capture());
    assertThat(getTimeUnit(injectionListenerArgumentCaptor.getValue()), is(TimeUnit.MINUTES));
  }

  private long getTimeout(InjectionListener<? super TestClass> listener) throws Exception {
    Field timeoutField = CachedGaugeInjectionListener.class.getDeclaredField("timeout");
    timeoutField.setAccessible(true);
    return timeoutField.getLong(listener);
  }

  private TimeUnit getTimeUnit(InjectionListener<?> listener) throws Exception {
    Field timeUnitField = CachedGaugeInjectionListener.class.getDeclaredField("timeUnit");
    timeUnitField.setAccessible(true);
    return (TimeUnit) timeUnitField.get(listener);
  }

  public static class TestClass
  {
    @CachedGauge(name = "test.annotated", timeout = 10, timeoutUnit = TimeUnit.SECONDS, absolute = true)
    public String annotated() {
      return null;
    }
  }
}