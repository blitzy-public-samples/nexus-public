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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;


/**
 * Tests to validate Apache Felix OSGi service registry functionality under Java 21.
 *
 * This test suite ensures that OSGi service-oriented architecture primitives function correctly
 * with Java 21's modified classloading and concurrency model, preventing service resolution
 * and event handling issues in production.
 */
@ExtendWith(MockitoExtension.class)
public class FelixServiceRegistryTest
{
  /**
   * Record type for testing Java 21 record pattern support in OSGi service properties
   */
  public record ServiceConfig(String name, int priority, Map<String, Object> attributes) {}

  /**
   * Simple service interface for testing
   */
  public interface TestService {
    String getName();
    int getPriority();
  }

  /**
   * Implementation of test service
   */
  public static class TestServiceImpl implements TestService {
    private final String name;
    private final int priority;

    public TestServiceImpl(String name, int priority) {
      this.name = name;
      this.priority = priority;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public int getPriority() {
      return priority;
    }
  }

  @Mock
  private Bundle bundle;

  @Mock
  private BundleContext bundleContext;

  //private ServiceRegistry serviceRegistry;

  @BeforeEach
  void setUp() {
    // Create a new service registry for each test
    serviceRegistry = new ServiceRegistry(bundle);

    // Set up bundle context mock
    when(bundle.getBundleContext()).thenReturn(bundleContext);
  }

  @AfterEach
  void tearDown() {
    // Clean up any resources
    serviceRegistry = null;
  }

  @Test
  @DisplayName("Test basic service registration and lookup")
  void testBasicServiceRegistrationAndLookup() {
    // Create a test service
    TestService service = new TestServiceImpl("test-service", 100);

    // Create service properties
    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("service.name", "test-service");
    properties.put("service.priority", 100);

    // Register the service
    ServiceRegistration<?> registration = serviceRegistry.registerService(
        bundleContext, new String[] { TestService.class.getName() }, service, properties);

    // Verify registration is not null
    assertNotNull(registration, "Service registration should not be null");

    // Get service reference
    ServiceReference<?> reference = registration.getReference();
    assertNotNull(reference, "Service reference should not be null");

    // Verify service properties
    assertEquals("test-service", reference.getProperty("service.name"));
    assertEquals(100, reference.getProperty("service.priority"));

    // Get service
    TestService retrievedService = (TestService) serviceRegistry.getService(bundleContext, reference);
    assertNotNull(retrievedService, "Retrieved service should not be null");
    assertEquals("test-service", retrievedService.getName());
    assertEquals(100, retrievedService.getPriority());

    // Unregister service
    registration.unregister();

    // Verify service is no longer available
    assertNull(serviceRegistry.getService(bundleContext, reference), "Service should be null after unregistration");
  }

  @Test
  @DisplayName("Test service registration with Java 21 record type properties")
  void testServiceRegistrationWithRecordProperties() {
    // Create a test service
    TestService service = new TestServiceImpl("record-service", 200);

    // Create a record for service properties
    Map<String, Object> attributes = new ConcurrentHashMap<>();
    attributes.put("feature", "virtual-threads");
    attributes.put("enabled", true);

    ServiceConfig config = new ServiceConfig("record-service", 200, attributes);

    // Create service properties from record
    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("service.name", config.name());
    properties.put("service.priority", config.priority());
    properties.put("service.config", config); // Store the entire record as a property
    properties.put("service.attributes", config.attributes());

    // Register the service
    ServiceRegistration<?> registration = serviceRegistry.registerService(
        bundleContext, new String[] { TestService.class.getName() }, service, properties);

    // Get service reference
    ServiceReference<?> reference = registration.getReference();

    // Verify service properties
    assertEquals("record-service", reference.getProperty("service.name"));
    assertEquals(200, reference.getProperty("service.priority"));
    assertTrue(reference.getProperty("service.config") instanceof ServiceConfig);

    // Use pattern matching with record type (Java 21 feature)
    if (reference.getProperty("service.config") instanceof ServiceConfig(String name, int priority, var attrs)) {
      assertEquals("record-service", name);
      assertEquals(200, priority);
      assertEquals(true, attrs.get("enabled"));
      assertEquals("virtual-threads", attrs.get("feature"));
    } else {
      throw new AssertionError("Pattern matching with record type failed");
    }

    // Unregister service
    registration.unregister();
  }

  @Test
  @DisplayName("Test service lookup with filter")
  void testServiceLookupWithFilter() throws InvalidSyntaxException {
    // Register multiple services
    registerTestService("service-1", 100, "type", "primary");
    registerTestService("service-2", 200, "type", "secondary");
    registerTestService("service-3", 300, "type", "primary");

    // Create filter
    Filter filter = FrameworkUtil.createFilter("(&(service.name=*)(type=primary))");

    // Get service references
    ServiceReference<?>[] references = serviceRegistry.getServiceReferences(bundleContext, TestService.class.getName(), filter.toString());

    // Verify we got the expected services
    assertNotNull(references, "Service references should not be null");
    assertEquals(2, references.length, "Should find 2 services matching the filter");

    // Verify the services have the expected properties
    List<String> serviceNames = new ArrayList<>();
    for (ServiceReference<?> reference : references) {
      serviceNames.add((String) reference.getProperty("service.name"));
    }

    assertTrue(serviceNames.contains("service-1"), "Should find service-1");
    assertTrue(serviceNames.contains("service-3"), "Should find service-3");
    assertFalse(serviceNames.contains("service-2"), "Should not find service-2");
  }

  @Test
  @DisplayName("Test service ranking")
  void testServiceRanking() {
    // Register multiple services with different rankings
    registerTestService("low-priority", 100, Constants.SERVICE_RANKING, Integer.valueOf(1));
    registerTestService("medium-priority", 200, Constants.SERVICE_RANKING, Integer.valueOf(50));
    registerTestService("high-priority", 300, Constants.SERVICE_RANKING, Integer.valueOf(100));

    // Get highest ranked service
    ServiceReference<?> reference = serviceRegistry.getServiceReference(bundleContext, TestService.class.getName());

    // Verify it's the highest ranked service
    assertNotNull(reference, "Service reference should not be null");
    assertEquals("high-priority", reference.getProperty("service.name"));
    assertEquals(Integer.valueOf(100), reference.getProperty(Constants.SERVICE_RANKING));

    // Get the service
    TestService service = (TestService) serviceRegistry.getService(bundleContext, reference);
    assertEquals("high-priority", service.getName());
    assertEquals(300, service.getPriority());
  }

  @Test
  @DisplayName("Test service listener notifications")
  void testServiceListenerNotifications() throws InvalidSyntaxException {
    // Create a service listener
    AtomicReference<ServiceEvent> registeredEvent = new AtomicReference<>();
    AtomicReference<ServiceEvent> modifiedEvent = new AtomicReference<>();
    AtomicReference<ServiceEvent> unregisteredEvent = new AtomicReference<>();

    ServiceListener listener = event -> {
      switch (event.getType()) {
        case ServiceEvent.REGISTERED -> registeredEvent.set(event);
        case ServiceEvent.MODIFIED -> modifiedEvent.set(event);
        case ServiceEvent.UNREGISTERING -> unregisteredEvent.set(event);
      }
    };

    // Add the listener
    serviceRegistry.addServiceListener(bundleContext, listener, null);

    // Register a service
    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("service.name", "listener-test");
    TestService service = new TestServiceImpl("listener-test", 100);

    ServiceRegistration<?> registration = serviceRegistry.registerService(
        bundleContext, new String[] { TestService.class.getName() }, service, properties);

    // Verify REGISTERED event
    assertNotNull(registeredEvent.get(), "REGISTERED event should not be null");
    assertEquals(ServiceEvent.REGISTERED, registeredEvent.get().getType());
    assertEquals("listener-test", registeredEvent.get().getServiceReference().getProperty("service.name"));

    // Modify service properties
    Dictionary<String, Object> updatedProperties = new Hashtable<>();
    updatedProperties.put("service.name", "listener-test-updated");
    registration.setProperties(updatedProperties);

    // Verify MODIFIED event
    assertNotNull(modifiedEvent.get(), "MODIFIED event should not be null");
    assertEquals(ServiceEvent.MODIFIED, modifiedEvent.get().getType());
    assertEquals("listener-test-updated", modifiedEvent.get().getServiceReference().getProperty("service.name"));

    // Unregister service
    registration.unregister();

    // Verify UNREGISTERING event
    assertNotNull(unregisteredEvent.get(), "UNREGISTERING event should not be null");
    assertEquals(ServiceEvent.UNREGISTERING, unregisteredEvent.get().getType());

    // Remove the listener
    serviceRegistry.removeServiceListener(bundleContext, listener);
  }

  @Test
  @DisplayName("Test service operations with virtual threads")
  void testServiceOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();

    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int numServices = 100;
      CountDownLatch registrationLatch = new CountDownLatch(numServices);
      List<ServiceRegistration<?>> registrations = new ConcurrentHashMap<Integer, ServiceRegistration<?>>().newKeySet()
          .stream().collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

      // Register services concurrently using virtual threads
      List<CompletableFuture<Void>> registrationFutures = new ArrayList<>();

      for (int i = 0; i < numServices; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          String serviceName = "virtual-service-" + index;
          TestService service = new TestServiceImpl(serviceName, index);

          Dictionary<String, Object> properties = new Hashtable<>();
          properties.put("service.name", serviceName);
          properties.put("service.index", index);

          ServiceRegistration<?> registration = serviceRegistry.registerService(
              bundleContext, new String[] { TestService.class.getName() }, service, properties);

          registrations.add(registration);
          registrationLatch.countDown();
        }, executor);

        registrationFutures.add(future);
      }

      // Wait for all registrations to complete
      CompletableFuture.allOf(registrationFutures.toArray(new CompletableFuture[0])).join();
      assertTrue(registrationLatch.await(5, TimeUnit.SECONDS), "Service registrations should complete within timeout");

      // Verify all services were registered
      assertEquals(numServices, registrations.size(), "All services should be registered");

      // Look up services concurrently
      CountDownLatch lookupLatch = new CountDownLatch(numServices);
      AtomicInteger successfulLookups = new AtomicInteger(0);

      List<CompletableFuture<Void>> lookupFutures = new ArrayList<>();

      for (int i = 0; i < numServices; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create a filter to find the specific service
            String filter = "(service.index=" + index + ")";
            ServiceReference<?>[] references = serviceRegistry.getServiceReferences(
                bundleContext, TestService.class.getName(), filter);

            if (references != null && references.length == 1) {
              TestService service = (TestService) serviceRegistry.getService(bundleContext, references[0]);
              if (service != null && service.getName().equals("virtual-service-" + index)) {
                successfulLookups.incrementAndGet();
              }
            }
          } catch (InvalidSyntaxException e) {
            throw new RuntimeException(e);
          } finally {
            lookupLatch.countDown();
          }
        }, executor);

        lookupFutures.add(future);
      }

      // Wait for all lookups to complete
      CompletableFuture.allOf(lookupFutures.toArray(new CompletableFuture[0])).join();
      assertTrue(lookupLatch.await(5, TimeUnit.SECONDS), "Service lookups should complete within timeout");

      // Verify all lookups were successful
      assertEquals(numServices, successfulLookups.get(), "All service lookups should succeed");

      // Unregister all services concurrently
      CountDownLatch unregistrationLatch = new CountDownLatch(numServices);

      List<CompletableFuture<Void>> unregistrationFutures = new ArrayList<>();

      for (ServiceRegistration<?> registration : registrations) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          registration.unregister();
          unregistrationLatch.countDown();
        }, executor);

        unregistrationFutures.add(future);
      }

      // Wait for all unregistrations to complete
      CompletableFuture.allOf(unregistrationFutures.toArray(new CompletableFuture[0])).join();
      assertTrue(unregistrationLatch.await(5, TimeUnit.SECONDS), "Service unregistrations should complete within timeout");
    }
  }

  @Test
  @DisplayName("Test service tracker with virtual threads")
  void testServiceTrackerWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();

    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Create a mock service tracker
      ServiceTracker<TestService, TestService> tracker = mock(ServiceTracker.class);

      // Register the tracker with the service registry
      serviceRegistry.addServiceListener(bundleContext, event -> {
        if (event.getType() == ServiceEvent.REGISTERED) {
          ServiceReference<?> reference = event.getServiceReference();
          if (TestService.class.getName().equals(reference.getProperty(Constants.OBJECTCLASS))) {
            TestService service = (TestService) serviceRegistry.getService(bundleContext, reference);
            tracker.addingService(reference);
          }
        } else if (event.getType() == ServiceEvent.UNREGISTERING) {
          ServiceReference<?> reference = event.getServiceReference();
          if (TestService.class.getName().equals(reference.getProperty(Constants.OBJECTCLASS))) {
            tracker.removedService(reference, null);
          }
        }
      }, "(objectClass=" + TestService.class.getName() + ")");

      // Register services concurrently
      int numServices = 50;
      List<CompletableFuture<ServiceRegistration<?>>> registrationFutures = new ArrayList<>();

      for (int i = 0; i < numServices; i++) {
        final int index = i;
        CompletableFuture<ServiceRegistration<?>> future = CompletableFuture.supplyAsync(() -> {
          String serviceName = "tracker-service-" + index;
          TestService service = new TestServiceImpl(serviceName, index);

          Dictionary<String, Object> properties = new Hashtable<>();
          properties.put("service.name", serviceName);
          properties.put("service.index", index);

          return serviceRegistry.registerService(
              bundleContext, new String[] { TestService.class.getName() }, service, properties);
        }, executor);

        registrationFutures.add(future);
      }

      // Wait for all registrations to complete
      List<ServiceRegistration<?>> registrations = new ArrayList<>();
      for (CompletableFuture<ServiceRegistration<?>> future : registrationFutures) {
        registrations.add(future.join());
      }

      // Verify tracker was notified for each service
      verify(tracker, org.mockito.Mockito.timeout(Duration.ofSeconds(5).toMillis()).times(numServices)).addingService(org.mockito.ArgumentMatchers.any());

      // Unregister services concurrently
      List<CompletableFuture<Void>> unregistrationFutures = new ArrayList<>();

      for (ServiceRegistration<?> registration : registrations) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          registration.unregister();
        }, executor);

        unregistrationFutures.add(future);
      }

      // Wait for all unregistrations to complete
      CompletableFuture.allOf(unregistrationFutures.toArray(new CompletableFuture[0])).join();

      // Verify tracker was notified for each service removal
      verify(tracker, org.mockito.Mockito.timeout(Duration.ofSeconds(5).toMillis()).times(numServices)).removedService(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
  }

  @Test
  @DisplayName("Test bundle lifecycle integration with service registry")
  void testBundleLifecycleIntegration() {
    // Create a mock bundle
    Bundle mockBundle = mock(Bundle.class);
    BundleContext mockBundleContext = mock(BundleContext.class);
    when(mockBundle.getBundleContext()).thenReturn(mockBundleContext);

    // Create a service registry for this bundle
    ServiceRegistry bundleServiceRegistry = new ServiceRegistry(mockBundle);

    // Register a service
    TestService service = new TestServiceImpl("bundle-service", 100);
    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("service.name", "bundle-service");

    ServiceRegistration<?> registration = bundleServiceRegistry.registerService(
        mockBundleContext, new String[] { TestService.class.getName() }, service, properties);

    // Verify service is registered
    ServiceReference<?> reference = registration.getReference();
    assertNotNull(reference, "Service reference should not be null");
    assertEquals("bundle-service", reference.getProperty("service.name"));

    // Simulate bundle stopping
    bundleServiceRegistry.removeBundle(mockBundle);

    // Verify all services for this bundle are unregistered
    try {
      registration.getReference();
      throw new AssertionError("Service should be unregistered when bundle is removed");
    } catch (IllegalStateException e) {
      // Expected exception when service is unregistered
    }
  }

  @Test
  @DisplayName("Test service property pattern matching with Java 21 features")
  void testServicePropertyPatternMatching() {
    // Register a service with complex properties
    TestService service = new TestServiceImpl("pattern-service", 100);

    // Create nested data structures for properties
    Map<String, Object> configMap = new ConcurrentHashMap<>();
    configMap.put("enabled", true);
    configMap.put("maxConnections", 50);

    List<String> supportedFormats = List.of("maven", "npm", "docker");
    configMap.put("formats", supportedFormats);

    // Create a record with the configuration
    record ConnectionConfig(boolean secure, int timeout) {}
    ConnectionConfig connConfig = new ConnectionConfig(true, 30000);
    configMap.put("connection", connConfig);

    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("service.name", "pattern-service");
    properties.put("config", configMap);

    ServiceRegistration<?> registration = serviceRegistry.registerService(
        bundleContext, new String[] { TestService.class.getName() }, service, properties);

    ServiceReference<?> reference = registration.getReference();

    // Use pattern matching to extract and validate properties
    Object config = reference.getProperty("config");

    if (config instanceof Map<?, ?> map) {
      // Use pattern matching in instanceof check (Java 21 feature)
      if (map.get("connection") instanceof ConnectionConfig(boolean secure, int timeout)) {
        assertAll(
            () -> assertTrue(secure, "Connection should be secure"),
            () -> assertEquals(30000, timeout, "Timeout should be 30000")
        );
      } else {
        throw new AssertionError("Connection config pattern matching failed");
      }

      // Check formats using pattern matching
      if (map.get("formats") instanceof List<?> formats) {
        assertEquals(3, formats.size(), "Should have 3 supported formats");
        assertTrue(formats.contains("maven"), "Should support maven format");
        assertTrue(formats.contains("npm"), "Should support npm format");
        assertTrue(formats.contains("docker"), "Should support docker format");
      } else {
        throw new AssertionError("Formats pattern matching failed");
      }
    } else {
      throw new AssertionError("Config pattern matching failed");
    }

    // Unregister service
    registration.unregister();
  }

  /**
   * Helper method to register a test service with properties
   */
  private ServiceRegistration<?> registerTestService(String name, int priority, String key, Object value) {
    TestService service = new TestServiceImpl(name, priority);

    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("service.name", name);
    properties.put("service.priority", priority);
    properties.put(key, value);

    return serviceRegistry.registerService(
        bundleContext, new String[] { TestService.class.getName() }, service, properties);
  }

  /**
   * Mock implementation of ServiceTracker for testing
   */
  public interface ServiceTracker<S, T> {
    T addingService(ServiceReference<S> reference);
    void removedService(ServiceReference<S> reference, T service);
  }
}