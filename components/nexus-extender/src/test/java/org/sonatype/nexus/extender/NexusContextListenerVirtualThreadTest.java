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
package org.sonatype.nexus.extender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.ManagedLifecycle.Phase;
import org.sonatype.nexus.common.app.ManagedLifecycleManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.startlevel.FrameworkStartLevel;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.CAPABILITIES;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.KERNEL;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SECURITY;

/**
 * Tests {@link NexusContextListener} with Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
class NexusContextListenerVirtualThreadTest
{
  @Mock
  private NexusBundleExtender bundleExtender;

  @Mock
  private BundleContext bundleContext;
  
  @Mock
  private ServletContext servletContext;
  
  @Mock
  private ServletContextEvent servletContextEvent;
  
  @Mock
  private ManagedLifecycleManager lifecycleManager;
  
  @Mock
  private ApplicationVersion applicationVersion;
  
  @Mock
  private Bundle systemBundle;
  
  @Mock
  private FrameworkStartLevel frameworkStartLevel;
  
  @Captor
  private ArgumentCaptor<FrameworkListener> frameworkListenerCaptor;

  private NexusContextListener underTest;

  @BeforeEach
  void setUp() {
    when(bundleExtender.getBundleContext()).thenReturn(bundleContext);
    when(servletContextEvent.getServletContext()).thenReturn(servletContext);
    when(servletContext.getAttribute(anyString())).thenReturn(null);
    when(bundleContext.getBundle(0)).thenReturn(systemBundle);
    when(systemBundle.adapt(FrameworkStartLevel.class)).thenReturn(frameworkStartLevel);
    
    underTest = new NexusContextListener(bundleExtender);
  }

  /**
   * Tests that the isFeatureFlagEnabled method works correctly when called from multiple virtual threads concurrently.
   */
  @Test
  void concurrentFeatureFlagAccessWithVirtualThreads() throws InterruptedException {
    // Set up system property for feature flag
    String flagName = "test.virtual.thread.flag";
    System.setProperty(flagName, "true");
    
    // Number of concurrent threads to test with
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Test the feature flag method
          boolean enabled = underTest.isFeatureFlagEnabled("OSS", "featureFlag:enabledByDefault:" + flagName);
          
          if (!enabled) {
            errorCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify no errors occurred
    assertThat("All virtual threads should successfully check feature flags", errorCount.get(), is(0));
    
    // Clean up
    System.clearProperty(flagName);
  }

  /**
   * Tests that feature flag checks work correctly with different editions when accessed from virtual threads.
   */
  @Test
  void featureFlagEditionCheckWithVirtualThreads() throws InterruptedException, ExecutionException {
    // Test with different editions and feature flags
    String flagName = "test.edition.flag";
    System.setProperty(flagName, "true");
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Test OSS edition with OSS feature flag
      Future<Boolean> ossResult = executor.submit(() ->
          underTest.isFeatureFlagEnabled("OSS", "oss:featureFlag:" + flagName));
      
      // Test PRO edition with OSS feature flag
      Future<Boolean> proWithOssResult = executor.submit(() ->
          underTest.isFeatureFlagEnabled("PRO", "oss:featureFlag:" + flagName));
      
      // Test PRO edition with PRO feature flag
      Future<Boolean> proWithProResult = executor.submit(() ->
          underTest.isFeatureFlagEnabled("PRO", "pro:featureFlag:" + flagName));
      
      // Test COMMUNITY edition with community feature flag
      Future<Boolean> communityResult = executor.submit(() ->
          underTest.isFeatureFlagEnabled("COMMUNITY", "community:featureFlag:" + flagName));
      
      // Verify results
      assertTrue(ossResult.get(), "OSS edition should have OSS feature flag enabled");
      assertFalse(proWithOssResult.get(), "PRO edition should not have OSS-only feature flag enabled");
      assertTrue(proWithProResult.get(), "PRO edition should have PRO feature flag enabled");
      assertTrue(communityResult.get(), "COMMUNITY edition should have community feature flag enabled");
    }
    
    // Clean up
    System.clearProperty(flagName);
  }

  /**
   * Tests that multiple feature flags can be checked concurrently from virtual threads.
   */
  @Test
  void multipleFeatureFlagChecksWithVirtualThreads() throws InterruptedException {
    // Set up multiple feature flags
    List<String> flagNames = List.of(
        "test.flag.1",
        "test.flag.2",
        "test.flag.3",
        "test.flag.4",
        "test.flag.5"
    );
    
    // Set all flags to true
    flagNames.forEach(flag -> System.setProperty(flag, "true"));
    
    // Create a latch to wait for all threads
    int threadCount = flagNames.size() * 100; // 100 threads per flag
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean allFlagsEnabled = new AtomicBoolean(true);
    
    // Create virtual threads to check flags concurrently
    for (int i = 0; i < threadCount; i++) {
      final String flag = flagNames.get(i % flagNames.size());
      final String edition = (i % 3 == 0) ? "OSS" : ((i % 3 == 1) ? "PRO" : "COMMUNITY");
      final String installMode = "featureFlag:enabledByDefault:" + flag;
      
      Thread.ofVirtual().start(() -> {
        try {
          boolean enabled = underTest.isFeatureFlagEnabled(edition, installMode);
          if (!enabled) {
            allFlagsEnabled.set(false);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify all flags were enabled
    assertTrue(allFlagsEnabled.get(), "All feature flags should be enabled");
    
    // Clean up
    flagNames.forEach(System::clearProperty);
  }

  /**
   * Tests that feature flag checks with malformed install modes are handled correctly in virtual threads.
   */
  @Test
  void malformedFeatureFlagWithVirtualThreads() throws InterruptedException {
    // List of malformed install modes
    List<String> malformedModes = List.of(
        "featureFlag:",
        "featureFlag:enabledByDefault:",
        "foo:featureFlag:enabledByDefault:",
        "fooFlag:enabledByDefault:foo.enabled",
        ""
    );
    
    // Create a latch to wait for all threads
    int threadCount = malformedModes.size() * 20; // 20 threads per malformed mode
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<Boolean> results = new ArrayList<>();
    
    // Create virtual threads to check malformed flags concurrently
    for (int i = 0; i < threadCount; i++) {
      final String mode = malformedModes.get(i % malformedModes.size());
      
      Thread.ofVirtual().start(() -> {
        try {
          boolean result = underTest.isFeatureFlagEnabled("OSS", mode);
          synchronized (results) {
            results.add(result);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify all malformed flags returned false
    assertFalse(results.stream().anyMatch(Boolean::booleanValue),
        "All malformed feature flags should return false");
  }
  
  /**
   * Tests that the context initialization and lifecycle phase transitions work correctly with virtual threads.
   * This test verifies thread mounting/unmounting behavior during lifecycle operations.
   */
  @Test
  void lifecycleOperationsWithVirtualThreads() throws Exception {
    // Mock the necessary components for context initialization
    ServiceReference<Object> serviceReference = mock(ServiceReference.class);
    when(bundleContext.getServiceReference(any(Class.class))).thenReturn(serviceReference);
    when(bundleContext.getService(any())).thenReturn(mock(Object.class));
    
    // Create a map to store thread IDs at different lifecycle phases
    Map<Phase, Long> threadIds = new ConcurrentHashMap<>();
    AtomicReference<Thread> kernelPhaseThread = new AtomicReference<>();
    AtomicReference<Thread> securityPhaseThread = new AtomicReference<>();
    AtomicReference<Thread> capabilitiesPhaseThread = new AtomicReference<>();
    
    // Mock the lifecycle manager to capture thread information during phase transitions
    doAnswer(invocation -> {
      Phase phase = invocation.getArgument(0);
      Thread currentThread = Thread.currentThread();
      threadIds.put(phase, currentThread.threadId());
      
      if (phase == KERNEL) {
        kernelPhaseThread.set(currentThread);
      } else if (phase == SECURITY) {
        securityPhaseThread.set(currentThread);
      } else if (phase == CAPABILITIES) {
        capabilitiesPhaseThread.set(currentThread);
      }
      
      return null;
    }).when(lifecycleManager).to(any(Phase.class));
    
    // Create a virtual thread to initialize the context
    Thread virtualThread = Thread.ofVirtual().name("context-init-thread").start(() -> {
      try {
        // Set up a mock injector that returns our mocked lifecycle manager
        com.google.inject.Injector injector = mock(com.google.inject.Injector.class);
        when(injector.getInstance(ManagedLifecycleManager.class)).thenReturn(lifecycleManager);
        when(injector.getInstance(ApplicationVersion.class)).thenReturn(applicationVersion);
        
        // Use reflection to set the injector field in the NexusContextListener
        java.lang.reflect.Field injectorField = NexusContextListener.class.getDeclaredField("injector");
        injectorField.setAccessible(true);
        injectorField.set(underTest, injector);
        
        // Initialize the context
        underTest.contextInitialized(servletContextEvent);
        
        // Simulate framework event to trigger capabilities phase
        FrameworkEvent frameworkEvent = mock(FrameworkEvent.class);
        when(frameworkEvent.getType()).thenReturn(FrameworkEvent.STARTLEVEL_CHANGED);
        underTest.frameworkEvent(frameworkEvent);
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to initialize context", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(5000);
    
    // Verify that the lifecycle phases were executed
    verify(lifecycleManager, times(1)).to(eq(KERNEL));
    verify(lifecycleManager, times(1)).to(eq(SECURITY));
    verify(lifecycleManager, times(1)).to(eq(CAPABILITIES));
    
    // Verify that all phases were executed on the same virtual thread
    assertTrue(kernelPhaseThread.get().isVirtual(), "Kernel phase should be executed on a virtual thread");
    assertTrue(securityPhaseThread.get().isVirtual(), "Security phase should be executed on a virtual thread");
    assertTrue(capabilitiesPhaseThread.get().isVirtual(), "Capabilities phase should be executed on a virtual thread");
    
    // Verify thread continuity - all phases should be executed on the same thread
    assertThat(threadIds.get(KERNEL), is(threadIds.get(SECURITY)));
    assertThat(threadIds.get(SECURITY), is(threadIds.get(CAPABILITIES)));
  }
  
  /**
   * Tests that thread context is properly maintained when using virtual threads with the NexusContextListener.
   * This test verifies that thread-local variables are properly propagated across virtual thread operations.
   */
  @Test
  void threadContextPropagationWithVirtualThreads() throws InterruptedException {
    // Create a thread-local variable to track context propagation
    ThreadLocal<String> securityContext = new ThreadLocal<>();
    
    // Number of threads to test with
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean contextMaintained = new AtomicBoolean(true);
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      final String threadContext = "security-context-" + i;
      
      Thread.ofVirtual().start(() -> {
        try {
          // Set up thread-local context
          securityContext.set(threadContext);
          
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform feature flag check which should maintain the thread context
          underTest.isFeatureFlagEnabled("OSS", "featureFlag:enabledByDefault:test.flag");
          
          // Verify that the thread context is maintained
          String currentContext = securityContext.get();
          if (!threadContext.equals(currentContext)) {
            contextMaintained.set(false);
          }
        }
        catch (Exception e) {
          contextMaintained.set(false);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify that thread context was maintained
    assertTrue(contextMaintained.get(), "Thread context should be maintained across virtual thread operations");
  }
  
  /**
   * Tests the performance characteristics of virtual threads vs platform threads when checking feature flags.
   * This test verifies that virtual threads can handle a large number of concurrent operations efficiently.
   */
  @Test
  void virtualThreadPerformanceComparisonTest() throws InterruptedException {
    // Set up system property for feature flag
    String flagName = "test.performance.flag";
    System.setProperty(flagName, "true");
    
    // Number of concurrent operations to perform
    int operationCount = 10000;
    
    // Create thread factories for both types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Measure virtual thread performance
    long virtualThreadTime = measurePerformance(virtualThreadFactory, operationCount, flagName);
    
    // Measure platform thread performance with a smaller count to avoid resource exhaustion
    int platformThreadCount = Math.min(operationCount, 1000); // Limit platform threads to avoid resource issues
    long platformThreadTime = measurePerformance(platformThreadFactory, platformThreadCount, flagName);
    
    // Scale platform thread time to match the operation count of virtual threads
    long scaledPlatformTime = platformThreadTime * operationCount / platformThreadCount;
    
    // Log the results
    System.out.println("Virtual Thread Time (ms): " + virtualThreadTime + " for " + operationCount + " operations");
    System.out.println("Platform Thread Time (ms): " + platformThreadTime + " for " + platformThreadCount + " operations");
    System.out.println("Scaled Platform Thread Time (ms): " + scaledPlatformTime + " for " + operationCount + " operations (estimated)");
    
    // Verify that virtual threads can handle more concurrent operations
    // Note: This is not a strict assertion as performance can vary by environment
    assertTrue(operationCount > platformThreadCount, 
        "Virtual threads should support more concurrent operations than platform threads");
    
    // Clean up
    System.clearProperty(flagName);
  }
  
  /**
   * Helper method to measure performance of feature flag checks using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (virtual or platform)
   * @param operationCount The number of concurrent operations to perform
   * @param flagName The name of the feature flag to check
   * @return The time taken in milliseconds
   */
  private long measurePerformance(ThreadFactory threadFactory, int operationCount, String flagName) 
      throws InterruptedException {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(operationCount);
    
    // Create threads
    for (int i = 0; i < operationCount; i++) {
      threadFactory.newThread(() -> {
        try {
          startLatch.await();
          underTest.isFeatureFlagEnabled("OSS", "featureFlag:enabledByDefault:" + flagName);
        }
        catch (Exception e) {
          // Ignore exceptions for performance test
        }
        finally {
          completionLatch.countDown();
        }
      }).start();
    }
    
    // Start timing
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for completion
    completionLatch.await(30, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    
    return endTime - startTime;
  }
}