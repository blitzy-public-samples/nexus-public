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
package org.apache.felix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.time.Duration;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Tests Apache Felix OSGi framework compatibility with Java 21 Virtual Threads.
 * 
 * This test suite verifies that OSGi service operations, event handling, and bundle operations
 * work correctly when executed on Virtual Threads. It tests for thread pinning issues,
 * compares performance between platform and virtual threads, and verifies correct thread
 * context propagation in the OSGi environment.
 */
public class FelixVirtualThreadTest
{
  private static final String FELIX_FRAMEWORK_FACTORY = "org.apache.felix.framework.FrameworkFactory";
  private static final int TEST_TIMEOUT_SECONDS = 10;
  private static final int THREAD_COUNT = 100;
  
  private Framework framework;
  private BundleContext bundleContext;
  private ThreadFactory virtualThreadFactory;
  private ThreadFactory platformThreadFactory;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  /**
   * Sets up the Felix OSGi framework and thread factories before each test.
   */
  @BeforeEach
  public void setUp(TestInfo testInfo) throws Exception {
    // Create thread factories for both virtual and platform threads
    virtualThreadFactory = Thread.ofVirtual().name("virtual-", 0).factory();
    platformThreadFactory = Thread.ofPlatform().name("platform-", 0).factory();
    
    // Create executor services
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    platformThreadExecutor = Executors.newThreadPerTaskExecutor(platformThreadFactory);
    
    // Set up Felix framework
    FrameworkFactory frameworkFactory = getFrameworkFactory();
    Properties config = new Properties();
    config.put(Constants.FRAMEWORK_STORAGE, new File("target/felix-" + testInfo.getDisplayName()).getAbsolutePath());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    
    framework = frameworkFactory.newFramework(config);
    framework.init();
    framework.start();
    bundleContext = framework.getBundleContext();
    
    System.out.println("Test setup complete: " + testInfo.getDisplayName());
  }
  
  /**
   * Cleans up resources after each test.
   */
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdownNow();
    }
    
    if (framework != null) {
      framework.stop();
      framework.waitForStop(5000);
    }
    
    System.out.println("Test teardown complete");
  }
  
  /**
   * Tests that OSGi service registration and retrieval works correctly with virtual threads.
   */
  @Test
  public void testServiceRegistrationWithVirtualThreads() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<String> threadName = new AtomicReference<>();
    final AtomicBoolean isVirtual = new AtomicBoolean(false);
    
    // Register a service using a virtual thread
    virtualThreadExecutor.submit(() -> {
      try {
        threadName.set(Thread.currentThread().getName());
        isVirtual.set(Thread.currentThread().isVirtual());
        
        // Register a simple service
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("test", "value");
        bundleContext.registerService(Runnable.class, () -> System.out.println("Service executed"), props);
        
        latch.countDown();
      } catch (Exception e) {
        e.printStackTrace();
        fail("Service registration failed: " + e.getMessage());
      }
    });
    
    assertTrue(latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Service registration timed out");
    assertTrue(isVirtual.get(), "Thread should be virtual");
    assertTrue(threadName.get().startsWith("virtual-"), "Thread name should start with 'virtual-'");
    
    // Verify service can be retrieved
    ServiceReference<?>[] refs = bundleContext.getServiceReferences(Runnable.class.getName(), "(test=value)");
    assertNotNull(refs, "Service references should not be null");
    assertEquals(1, refs.length, "Should find exactly one service");
    
    Runnable service = (Runnable) bundleContext.getService(refs[0]);
    assertNotNull(service, "Service should not be null");
  }
  
  /**
   * Tests that OSGi service events are properly delivered when using virtual threads.
   */
  @Test
  public void testServiceEventsWithVirtualThreads() throws Exception {
    final CountDownLatch registeredLatch = new CountDownLatch(1);
    final CountDownLatch modifiedLatch = new CountDownLatch(1);
    final CountDownLatch unregisteredLatch = new CountDownLatch(1);
    
    // Add service listener using a virtual thread
    virtualThreadExecutor.submit(() -> {
      try {
        bundleContext.addServiceListener(new ServiceListener() {
          @Override
          public void serviceChanged(ServiceEvent event) {
            // Verify we're still on a virtual thread when receiving events
            assertTrue(Thread.currentThread().isVirtual(), 
                "Service event should be delivered on a virtual thread");
            
            switch (event.getType()) {
              case ServiceEvent.REGISTERED:
                registeredLatch.countDown();
                break;
              case ServiceEvent.MODIFIED:
                modifiedLatch.countDown();
                break;
              case ServiceEvent.UNREGISTERING:
                unregisteredLatch.countDown();
                break;
            }
          }
        });
      } catch (Exception e) {
        fail("Failed to add service listener: " + e.getMessage());
      }
    }).get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Register, modify, and unregister a service to trigger events
    Dictionary<String, Object> props = new Hashtable<>();
    props.put("test", "value");
    
    ServiceRegistration<?> reg = bundleContext.registerService(
        Runnable.class, 
        () -> System.out.println("Service executed"), 
        props);
    
    assertTrue(registeredLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Service REGISTERED event not received");
    
    props.put("test", "updated");
    reg.setProperties(props);
    
    assertTrue(modifiedLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Service MODIFIED event not received");
    
    reg.unregister();
    
    assertTrue(unregisteredLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Service UNREGISTERING event not received");
  }
  
  /**
   * Tests for thread pinning issues when using virtual threads with OSGi.
   * Thread pinning occurs when a virtual thread is forced to stay on its carrier thread,
   * which can happen with synchronized blocks or native methods.
   */
  @Test
  public void testThreadPinningWithOSGi() throws Exception {
    final int numThreads = THREAD_COUNT;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(numThreads);
    final AtomicInteger pinnedThreads = new AtomicInteger(0);
    final Map<String, Long> threadTimes = new ConcurrentHashMap<>();
    
    // Start JFR recording to detect thread pinning
    Recording recording = new Recording();
    recording.enable("jdk.VirtualThreadPinned");
    recording.start();
    
    try {
      // Create multiple virtual threads that perform OSGi operations
      for (int i = 0; i < numThreads; i++) {
        final int threadId = i;
        Thread thread = Thread.ofVirtual().name("test-thread-" + i).start(() -> {
          try {
            startLatch.await(); // Wait for all threads to be created
            long startTime = System.nanoTime();
            
            // Perform OSGi operations that might cause pinning
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("thread-id", threadId);
            
            // Register and immediately get the service (potential pinning point)
            ServiceRegistration<?> reg = bundleContext.registerService(
                Runnable.class, 
                () -> {
                  // Synchronized block might cause pinning
                  synchronized (this) {
                    try {
                      Thread.sleep(10); // Short sleep to increase pinning chance
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                }, 
                props);
            
            ServiceReference<?> ref = reg.getReference();
            Runnable service = (Runnable) bundleContext.getService(ref);
            service.run();
            bundleContext.ungetService(ref);
            reg.unregister();
            
            long endTime = System.nanoTime();
            threadTimes.put("thread-" + threadId, (endTime - startTime) / 1_000_000); // ms
            
            completionLatch.countDown();
          } catch (Exception e) {
            e.printStackTrace();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Not all threads completed in time");
      
    } finally {
      recording.stop();
      
      // Analyze recording for pinning events
      for (RecordedEvent event : recording) {
        if (event.getEventType().getName().equals("jdk.VirtualThreadPinned")) {
          pinnedThreads.incrementAndGet();
          System.out.println("Thread pinning detected: " + event);
        }
      }
      
      recording.close();
    }
    
    // Report pinning statistics
    System.out.println("Thread pinning detected in " + pinnedThreads.get() + 
        " out of " + numThreads + " threads");
    
    // We don't fail the test if pinning is detected, just report it
    // In a real application, you would want to minimize pinning
    if (pinnedThreads.get() > 0) {
      System.out.println("WARNING: Thread pinning detected. This may impact performance.");
      System.out.println("Thread execution times (ms): " + threadTimes);
    }
  }
  
  /**
   * Compares performance between virtual threads and platform threads for OSGi operations.
   */
  @Test
  public void testPerformanceComparisonWithOSGi() throws Exception {
    final int numOperations = THREAD_COUNT;
    final CountDownLatch virtualThreadsLatch = new CountDownLatch(numOperations);
    final CountDownLatch platformThreadsLatch = new CountDownLatch(numOperations);
    
    // Measure virtual threads performance
    long virtualStartTime = System.nanoTime();
    
    for (int i = 0; i < numOperations; i++) {
      final int opId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Register a service
          Dictionary<String, Object> props = new Hashtable<>();
          props.put("operation-id", opId);
          props.put("thread-type", "virtual");
          
          ServiceRegistration<?> reg = bundleContext.registerService(
              Runnable.class, 
              () -> {
                try {
                  Thread.sleep(50); // Simulate some work
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }, 
              props);
          
          // Look up the service
          ServiceReference<?> ref = reg.getReference();
          Runnable service = (Runnable) bundleContext.getService(ref);
          service.run();
          bundleContext.ungetService(ref);
          reg.unregister();
          
          virtualThreadsLatch.countDown();
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
    }
    
    assertTrue(virtualThreadsLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Virtual thread operations did not complete in time");
    long virtualEndTime = System.nanoTime();
    long virtualDuration = TimeUnit.NANOSECONDS.toMillis(virtualEndTime - virtualStartTime);
    
    // Measure platform threads performance
    long platformStartTime = System.nanoTime();
    
    for (int i = 0; i < numOperations; i++) {
      final int opId = i;
      platformThreadExecutor.submit(() -> {
        try {
          // Register a service
          Dictionary<String, Object> props = new Hashtable<>();
          props.put("operation-id", opId);
          props.put("thread-type", "platform");
          
          ServiceRegistration<?> reg = bundleContext.registerService(
              Runnable.class, 
              () -> {
                try {
                  Thread.sleep(50); // Simulate some work
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }, 
              props);
          
          // Look up the service
          ServiceReference<?> ref = reg.getReference();
          Runnable service = (Runnable) bundleContext.getService(ref);
          service.run();
          bundleContext.ungetService(ref);
          reg.unregister();
          
          platformThreadsLatch.countDown();
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
    }
    
    assertTrue(platformThreadsLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Platform thread operations did not complete in time");
    long platformEndTime = System.nanoTime();
    long platformDuration = TimeUnit.NANOSECONDS.toMillis(platformEndTime - platformStartTime);
    
    // Report performance comparison
    System.out.println("Performance comparison for " + numOperations + " OSGi operations:");
    System.out.println("Virtual threads: " + virtualDuration + " ms");
    System.out.println("Platform threads: " + platformDuration + " ms");
    System.out.println("Difference: " + (platformDuration - virtualDuration) + " ms");
    
    // We don't assert on specific performance numbers as they can vary by environment
    // but we do expect virtual threads to generally perform better for I/O bound operations
  }
  
  /**
   * Tests that thread context is properly maintained when using virtual threads with OSGi.
   */
  @Test
  public void testThreadContextPropagationWithOSGi() throws Exception {
    final int numThreads = 10;
    final CountDownLatch latch = new CountDownLatch(numThreads);
    final AtomicBoolean contextPropagationFailed = new AtomicBoolean(false);
    
    // Create a ThreadLocal to test context propagation
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    
    for (int i = 0; i < numThreads; i++) {
      final String expectedValue = "thread-context-" + i;
      
      Thread.startVirtualThread(() -> {
        try {
          // Set thread local value
          threadLocal.set(expectedValue);
          
          // Register a service that will check the thread local value
          Dictionary<String, Object> props = new Hashtable<>();
          props.put("context-test", expectedValue);
          
          ServiceRegistration<?> reg = bundleContext.registerService(
              Supplier.class, 
              () -> {
                // Check if thread local value is preserved
                String actualValue = threadLocal.get();
                if (!expectedValue.equals(actualValue)) {
                  System.err.println("Thread context not propagated correctly. Expected: " + 
                      expectedValue + ", Actual: " + actualValue);
                  contextPropagationFailed.set(true);
                  return false;
                }
                return true;
              }, 
              props);
          
          // Look up and invoke the service
          ServiceReference<?> ref = reg.getReference();
          Supplier<?> service = (Supplier<?>) bundleContext.getService(ref);
          service.get();
          bundleContext.ungetService(ref);
          reg.unregister();
          
          // Clear thread local to avoid memory leaks
          threadLocal.remove();
          
          latch.countDown();
        } catch (Exception e) {
          e.printStackTrace();
          contextPropagationFailed.set(true);
        }
      });
    }
    
    assertTrue(latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Thread context propagation test did not complete in time");
    assertFalse(contextPropagationFailed.get(), 
        "Thread context was not properly propagated in OSGi environment");
  }
  
  /**
   * Tests that framework events are properly delivered when using virtual threads.
   */
  @Test
  public void testFrameworkEventsWithVirtualThreads() throws Exception {
    final CountDownLatch eventLatch = new CountDownLatch(1);
    final AtomicReference<FrameworkEvent> receivedEvent = new AtomicReference<>();
    
    // Add framework listener using a virtual thread
    virtualThreadExecutor.submit(() -> {
      bundleContext.addFrameworkListener(new FrameworkListener() {
        @Override
        public void frameworkEvent(FrameworkEvent event) {
          // Verify we're on a virtual thread when receiving events
          assertTrue(Thread.currentThread().isVirtual(), 
              "Framework event should be delivered on a virtual thread");
          
          receivedEvent.set(event);
          eventLatch.countDown();
        }
      });
    }).get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Trigger a framework event by refreshing packages
    Bundle systemBundle = bundleContext.getBundle(0);
    systemBundle.adapt(org.osgi.framework.wiring.FrameworkWiring.class)
        .refreshBundles(null);
    
    assertTrue(eventLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Framework event not received");
    assertNotNull(receivedEvent.get(), "Framework event should not be null");
    assertEquals(FrameworkEvent.PACKAGES_REFRESHED, receivedEvent.get().getType(), 
        "Expected PACKAGES_REFRESHED event");
  }
  
  /**
   * Tests that bundle operations work correctly with virtual threads.
   */
  @Test
  public void testBundleOperationsWithVirtualThreads() throws Exception {
    // This test requires a bundle to install
    // For simplicity, we'll use the system bundle and just verify operations work with virtual threads
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicBoolean success = new AtomicBoolean(false);
    
    Thread.startVirtualThread(() -> {
      try {
        // Get the system bundle
        Bundle systemBundle = bundleContext.getBundle(0);
        assertNotNull(systemBundle, "System bundle should not be null");
        
        // Verify bundle operations work on virtual thread
        assertEquals(Bundle.ACTIVE, systemBundle.getState(), "System bundle should be active");
        
        // Get bundle headers
        Dictionary<String, String> headers = systemBundle.getHeaders();
        assertNotNull(headers, "Bundle headers should not be null");
        assertNotNull(headers.get(Constants.BUNDLE_SYMBOLICNAME), 
            "Bundle symbolic name should be present");
        
        success.set(true);
        latch.countDown();
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
    
    assertTrue(latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Bundle operations test did not complete in time");
    assertTrue(success.get(), "Bundle operations should succeed on virtual thread");
  }
  
  /**
   * Gets the Felix framework factory.
   */
  private FrameworkFactory getFrameworkFactory() throws Exception {
    try {
      return (FrameworkFactory) Class.forName(FELIX_FRAMEWORK_FACTORY).getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException e) {
      fail("Felix framework factory not found. Make sure Felix is on the classpath.");
      return null; // Never reached
    }
  }
}