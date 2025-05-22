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
package org.sonatype.nexus.virtualthread;

import java.net.URL;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.sisu.inject.MutableBeanLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

import org.sonatype.nexus.extender.NexusBundleTracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link NexusBundleTracker} with Java 21 Virtual Threads to ensure bundle tracking
 * operations work correctly when executed from virtual threads.
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadBundleTrackerTest
{
  private static final String NAMED_RESOURCE = "META-INF/sisu/javax.inject.Named";

  @Mock
  private BundleContext bundleContext;

  @Mock
  private MutableBeanLocator locator;

  @Mock
  private Bundle systemBundle;

  private NexusBundleTracker bundleTracker;

  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() {
    // Set up system bundle
    when(bundleContext.getBundle(0)).thenReturn(systemBundle);
    when(systemBundle.getState()).thenReturn(Bundle.ACTIVE);

    // Create the bundle tracker
    bundleTracker = new NexusBundleTracker(bundleContext, locator);

    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() throws Exception {
    bundleTracker.close();
    virtualThreadExecutor.shutdownNow();
    virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * Tests that bundle listener callbacks can be successfully invoked from virtual threads.
   */
  @Test
  void testBundleListenerCallbacksFromVirtualThreads() throws Exception {
    // Capture the bundle listener when it's registered
    final AtomicBoolean listenerCalled = new AtomicBoolean(false);
    final CountDownLatch latch = new CountDownLatch(1);
    
    doAnswer(invocation -> {
      BundleListener listener = invocation.getArgument(0);
      // Store the listener for later use
      virtualThreadExecutor.submit(() -> {
        // Create a mock bundle that has components
        Bundle bundle = createMockBundleWithComponents("test.bundle", Bundle.ACTIVE);
        
        // Create a bundle event
        BundleEvent event = new BundleEvent(BundleEvent.STARTED, bundle);
        
        // Call the listener from a virtual thread
        listener.bundleChanged(event);
        
        listenerCalled.set(true);
        latch.countDown();
      });
      return null;
    }).when(bundleContext).addBundleListener(any(BundleListener.class));

    // Open the bundle tracker which will register the listener
    bundleTracker.open();

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Listener was not called within timeout");
    assertTrue(listenerCalled.get(), "Bundle listener was not called from virtual thread");
  }

  /**
   * Tests that bundle activation works correctly when triggered from virtual threads.
   */
  @Test
  void testBundleActivationFromVirtualThreads() throws Exception {
    // Set up a mock bundle with components
    Bundle bundle = createMockBundleWithComponents("test.activation.bundle", Bundle.ACTIVE);
    
    // Open the bundle tracker
    bundleTracker.open();
    
    // Use a virtual thread to activate the bundle
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      // Simulate bundle activation
      return bundleTracker.addingBundle(bundle, null) != null;
    }, virtualThreadExecutor);
    
    // Wait for the result
    assertTrue(future.get(5, TimeUnit.SECONDS), "Bundle activation failed from virtual thread");
    
    // Verify the bundle was prepared
    verify(locator, times(1)).add(any());
  }

  /**
   * Tests that bundle deactivation works correctly when triggered from virtual threads.
   */
  @Test
  void testBundleDeactivationFromVirtualThreads() throws Exception {
    // Set up a mock bundle with components
    Bundle bundle = createMockBundleWithComponents("test.deactivation.bundle", Bundle.ACTIVE);
    
    // Open the bundle tracker
    bundleTracker.open();
    
    // First add the bundle
    bundleTracker.addingBundle(bundle, null);
    
    // Use a virtual thread to deactivate the bundle
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Simulate bundle deactivation
      bundleTracker.removedBundle(bundle, null, null);
    }, virtualThreadExecutor);
    
    // Wait for completion
    future.get(5, TimeUnit.SECONDS);
    
    // Verify the bundle was removed
    verify(locator, times(1)).remove(any());
  }

  /**
   * Tests that OSGi service registration and unregistration work correctly when performed from virtual threads.
   */
  @Test
  void testServiceRegistrationFromVirtualThreads() throws Exception {
    // Mock service registration
    ServiceRegistration<?> serviceRegistration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(eq(String.class), any(), any(Dictionary.class)))
        .thenReturn(serviceRegistration);
    
    // Create a countdown latch to wait for both operations
    CountDownLatch latch = new CountDownLatch(2);
    AtomicBoolean registrationSucceeded = new AtomicBoolean(false);
    AtomicBoolean unregistrationSucceeded = new AtomicBoolean(false);
    
    // Use a virtual thread to register a service
    virtualThreadExecutor.submit(() -> {
      try {
        // Register a service
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("test", "value");
        ServiceRegistration<String> reg = bundleContext.registerService(
            String.class, "TestService", props);
        assertNotNull(reg);
        registrationSucceeded.set(true);
        latch.countDown();
        
        // Unregister in another virtual thread
        virtualThreadExecutor.submit(() -> {
          try {
            reg.unregister();
            unregistrationSucceeded.set(true);
          } catch (Exception e) {
            // Test will fail if this happens
          } finally {
            latch.countDown();
          }
        });
      } catch (Exception e) {
        // Test will fail if this happens
        latch.countDown();
      }
    });
    
    // Wait for both operations to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Service operations did not complete in time");
    assertTrue(registrationSucceeded.get(), "Service registration failed from virtual thread");
    assertTrue(unregistrationSucceeded.get(), "Service unregistration failed from virtual thread");
    
    // Verify the service was registered and unregistered
    verify(bundleContext).registerService(eq(String.class), any(), any(Dictionary.class));
    verify(serviceRegistration).unregister();
  }

  /**
   * Tests concurrent bundle operations with multiple virtual threads.
   */
  @Test
  void testConcurrentBundleOperationsWithVirtualThreads() throws Exception {
    // Open the bundle tracker
    bundleTracker.open();
    
    // Number of concurrent operations
    final int numOperations = 100;
    
    // Create a latch to wait for all operations
    CountDownLatch latch = new CountDownLatch(numOperations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and submit tasks for concurrent bundle operations
    for (int i = 0; i < numOperations; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a mock bundle with components
          Bundle bundle = createMockBundleWithComponents("test.concurrent.bundle." + index, Bundle.ACTIVE);
          
          // Add the bundle
          Object result = bundleTracker.addingBundle(bundle, null);
          
          // If successful, remove it
          if (result != null) {
            bundleTracker.removedBundle(bundle, null, result);
            successCount.incrementAndGet();
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent operations did not complete in time");
    
    // Verify that all operations were successful
    assertEquals(numOperations, successCount.get(), "Not all concurrent bundle operations succeeded");
  }

  /**
   * Creates a mock bundle with components for testing.
   */
  private Bundle createMockBundleWithComponents(String symbolicName, int state) {
    Bundle bundle = mock(Bundle.class);
    when(bundle.getSymbolicName()).thenReturn(symbolicName);
    when(bundle.getState()).thenReturn(state);
    
    // Mock the bundle to have components
    URL mockUrl = mock(URL.class);
    when(bundle.getResource(NAMED_RESOURCE)).thenReturn(mockUrl);
    
    // Mock bundle wiring for dependency checks
    BundleWiring wiring = mock(BundleWiring.class);
    when(bundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE)).thenReturn(List.of());
    
    // Mock bundle context
    BundleContext bundleCtx = mock(BundleContext.class);
    when(bundle.getBundleContext()).thenReturn(bundleCtx);
    
    return bundle;
  }
}