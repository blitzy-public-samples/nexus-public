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
import static org.mockito.Mockito.when;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

/**
 * JUnit 5 test suite that validates the Apache Felix OSGi service registry functionality under Java 21.
 * Tests service registration, lookup, tracking, filtering by properties, service ranking, and integration
 * with bundle lifecycle events. This test ensures that OSGi service-oriented architecture primitives
 * function correctly with Java 21's modified classloading and concurrency model, preventing service
 * resolution and event handling issues in production.
 */
@DisplayName("Felix Service Registry Java 21 Compatibility Tests")
public class FelixServiceRegistryTest
{
    private Felix felix;
    private BundleContext bundleContext;

    /**
     * Test service interface used for service registry tests.
     */
    public interface TestService {
        String getMessage();
    }

    /**
     * Implementation of the test service.
     */
    public static class TestServiceImpl implements TestService {
        private final String message;

        public TestServiceImpl(String message) {
            this.message = message;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }

    /**
     * Java 21 record type used to test service properties with record patterns.
     */
    public record ServiceConfig(String name, int priority, Map<String, Object> attributes) {}

    @BeforeEach
    public void setUp() throws BundleException {
        // Configure Felix with minimal settings for testing
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put(FelixConstants.LOG_LEVEL_PROP, "4"); // Only log errors
        config.put(Constants.FRAMEWORK_STORAGE, "target/felix-cache");
        config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        
        // Start Felix
        felix = new Felix(config);
        felix.start();
        bundleContext = felix.getBundleContext();
    }

    @AfterEach
    public void tearDown() throws BundleException, InterruptedException {
        if (felix != null) {
            felix.stop();
            felix.waitForStop(5000);
            felix = null;
            bundleContext = null;
        }
    }

    @Test
    @DisplayName("Basic service registration and lookup works with Java 21")
    public void testBasicServiceRegistrationAndLookup() {
        // Register a service
        TestService service = new TestServiceImpl("Hello from Java 21");
        ServiceRegistration<TestService> registration = bundleContext.registerService(
                TestService.class, service, null);

        // Look up the service
        ServiceReference<TestService> reference = bundleContext.getServiceReference(TestService.class);
        TestService lookedUpService = bundleContext.getService(reference);

        // Verify service lookup works
        assertNotNull(lookedUpService, "Service should be found");
        assertEquals("Hello from Java 21", lookedUpService.getMessage(), "Service message should match");

        // Unregister the service
        registration.unregister();

        // Verify service is no longer available
        ServiceReference<TestService> referenceAfterUnregister = 
                bundleContext.getServiceReference(TestService.class);
        assertNull(referenceAfterUnregister, "Service should not be found after unregistering");
    }

    @Test
    @DisplayName("Service properties with Java 21 record types work correctly")
    public void testServicePropertiesWithRecordTypes() {
        // Create a service config using Java 21 record
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put("feature", "Java21");
        attributes.put("enabled", true);
        
        ServiceConfig config = new ServiceConfig("TestService", 100, attributes);
        
        // Register service with properties from record
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("name", config.name());
        props.put("priority", config.priority());
        props.put("feature", config.attributes().get("feature"));
        props.put("enabled", config.attributes().get("enabled"));
        props.put("config", config); // Store the entire record as a property
        
        TestService service = new TestServiceImpl("Service with record properties");
        ServiceRegistration<TestService> registration = bundleContext.registerService(
                TestService.class, service, props);

        // Look up the service by filter using record properties
        try {
            String filter = "(&(name=TestService)(priority>=100)(feature=Java21)(enabled=true))"; 
            Filter osgiFilter = bundleContext.createFilter(filter);
            ServiceReference<?>[] refs = bundleContext.getServiceReferences(TestService.class.getName(), filter);
            
            assertNotNull(refs, "Service references should be found");
            assertTrue(refs.length > 0, "At least one service reference should be found");
            assertTrue(osgiFilter.match(refs[0]), "Filter should match service reference");
            
            // Verify we can retrieve the record from properties
            ServiceConfig retrievedConfig = (ServiceConfig) refs[0].getProperty("config");
            assertNotNull(retrievedConfig, "Should retrieve record from properties");
            assertEquals("TestService", retrievedConfig.name(), "Record name should match");
            assertEquals(100, retrievedConfig.priority(), "Record priority should match");
            assertEquals("Java21", retrievedConfig.attributes().get("feature"), "Record feature should match");
        }
        catch (InvalidSyntaxException e) {
            throw new RuntimeException("Invalid filter syntax", e);
        }
        finally {
            registration.unregister();
        }
    }

    @Test
    @DisplayName("Service tracking works with Java 21 virtual threads")
    public void testServiceTrackingWithVirtualThreads() throws Exception {
        // Create a tracker to monitor service registrations
        final CountDownLatch serviceAddedLatch = new CountDownLatch(1);
        final CountDownLatch serviceRemovedLatch = new CountDownLatch(1);
        final AtomicReference<TestService> trackedService = new AtomicReference<>();
        
        ServiceTracker<TestService, TestService> tracker = new ServiceTracker<>(
                bundleContext, TestService.class, new ServiceTrackerCustomizer<TestService, TestService>() {
                    @Override
                    public TestService addingService(ServiceReference<TestService> reference) {
                        TestService service = bundleContext.getService(reference);
                        trackedService.set(service);
                        serviceAddedLatch.countDown();
                        return service;
                    }

                    @Override
                    public void modifiedService(ServiceReference<TestService> reference, TestService service) {
                        // Not testing modification in this test
                    }

                    @Override
                    public void removedService(ServiceReference<TestService> reference, TestService service) {
                        serviceRemovedLatch.countDown();
                    }
                });
        
        tracker.open();
        
        try {
            // Use virtual threads to register and unregister services
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                // Register service in a virtual thread
                CompletableFuture<ServiceRegistration<TestService>> future = CompletableFuture.supplyAsync(() -> {
                    TestService service = new TestServiceImpl("Service from virtual thread");
                    Dictionary<String, Object> props = new Hashtable<>();
                    props.put("thread", "virtual");
                    return bundleContext.registerService(TestService.class, service, props);
                }, executor);
                
                ServiceRegistration<TestService> registration = future.get(5, TimeUnit.SECONDS);
                
                // Wait for service to be tracked
                assertTrue(serviceAddedLatch.await(5, TimeUnit.SECONDS), "Service should be tracked");
                assertNotNull(trackedService.get(), "Tracked service should not be null");
                assertEquals("Service from virtual thread", trackedService.get().getMessage(), 
                        "Tracked service message should match");
                
                // Unregister service in another virtual thread
                CompletableFuture<Void> unregisterFuture = CompletableFuture.runAsync(() -> {
                    registration.unregister();
                }, executor);
                
                unregisterFuture.get(5, TimeUnit.SECONDS);
                
                // Wait for service removal to be tracked
                assertTrue(serviceRemovedLatch.await(5, TimeUnit.SECONDS), 
                        "Service removal should be tracked");
            }
        } 
        finally {
            tracker.close();
        }
    }

    @Test
    @DisplayName("Service events are properly delivered with Java 21")
    public void testServiceEvents() throws Exception {
        final CountDownLatch registeredLatch = new CountDownLatch(1);
        final CountDownLatch modifiedLatch = new CountDownLatch(1);
        final CountDownLatch unregisteredLatch = new CountDownLatch(1);
        
        // Add service listener
        ServiceListener listener = event -> {
            switch (event.getType()) {
                case ServiceEvent.REGISTERED -> registeredLatch.countDown();
                case ServiceEvent.MODIFIED -> modifiedLatch.countDown();
                case ServiceEvent.UNREGISTERING -> unregisteredLatch.countDown();
            }
        };
        
        bundleContext.addServiceListener(listener);
        
        try {
            // Register service
            TestService service = new TestServiceImpl("Event test service");
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("test", "events");
            ServiceRegistration<TestService> registration = 
                    bundleContext.registerService(TestService.class, service, props);
            
            // Wait for registered event
            assertTrue(registeredLatch.await(5, TimeUnit.SECONDS), 
                    "Service registered event should be received");
            
            // Modify service properties
            props = new Hashtable<>();
            props.put("test", "events-modified");
            registration.setProperties(props);
            
            // Wait for modified event
            assertTrue(modifiedLatch.await(5, TimeUnit.SECONDS), 
                    "Service modified event should be received");
            
            // Unregister service
            registration.unregister();
            
            // Wait for unregistered event
            assertTrue(unregisteredLatch.await(5, TimeUnit.SECONDS), 
                    "Service unregistered event should be received");
        } 
        finally {
            bundleContext.removeServiceListener(listener);
        }
    }

    @Test
    @DisplayName("Service ranking works correctly with Java 21")
    public void testServiceRanking() {
        // Register multiple services with different rankings
        TestService service1 = new TestServiceImpl("Low priority service");
        Dictionary<String, Object> props1 = new Hashtable<>();
        props1.put(Constants.SERVICE_RANKING, 10);
        ServiceRegistration<TestService> reg1 = 
                bundleContext.registerService(TestService.class, service1, props1);
        
        TestService service2 = new TestServiceImpl("High priority service");
        Dictionary<String, Object> props2 = new Hashtable<>();
        props2.put(Constants.SERVICE_RANKING, 100);
        ServiceRegistration<TestService> reg2 = 
                bundleContext.registerService(TestService.class, service2, props2);
        
        try {
            // Get highest ranked service
            ServiceReference<TestService> ref = bundleContext.getServiceReference(TestService.class);
            TestService highestRanked = bundleContext.getService(ref);
            
            assertNotNull(highestRanked, "Highest ranked service should be found");
            assertEquals("High priority service", highestRanked.getMessage(), 
                    "Highest ranked service should be the one with highest ranking");
            assertEquals(100, ref.getProperty(Constants.SERVICE_RANKING), 
                    "Service ranking property should match");
            
            // Get all services and verify order
            ServiceReference<?>[] allRefs = bundleContext.getServiceReferences(TestService.class.getName(), null);
            assertNotNull(allRefs, "Service references should be found");
            assertEquals(2, allRefs.length, "Should find two service references");
            
            // First reference should be highest ranked
            assertEquals(100, allRefs[0].getProperty(Constants.SERVICE_RANKING), 
                    "First reference should have highest ranking");
        } 
        catch (InvalidSyntaxException e) {
            throw new RuntimeException("Invalid filter syntax", e);
        } 
        finally {
            reg1.unregister();
            reg2.unregister();
        }
    }

    @Test
    @DisplayName("Concurrent service operations work with Java 21 virtual threads")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testConcurrentServiceOperations() throws Exception {
        final int SERVICE_COUNT = 50;
        final CountDownLatch completionLatch = new CountDownLatch(SERVICE_COUNT);
        final AtomicBoolean failed = new AtomicBoolean(false);
        final List<ServiceRegistration<?>> registrations = new ArrayList<>();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Register multiple services concurrently using virtual threads
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (int i = 0; i < SERVICE_COUNT; i++) {
                final int index = i;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        TestService service = new TestServiceImpl("Concurrent service " + index);
                        Dictionary<String, Object> props = new Hashtable<>();
                        props.put("index", index);
                        
                        ServiceRegistration<TestService> registration = 
                                bundleContext.registerService(TestService.class, service, props);
                        
                        synchronized (registrations) {
                            registrations.add(registration);
                        }
                        
                        // Verify we can find our own service
                        String filter = "(index=" + index + ")";
                        ServiceReference<?>[] refs = 
                                bundleContext.getServiceReferences(TestService.class.getName(), filter);
                        
                        if (refs == null || refs.length == 0) {
                            failed.set(true);
                        }
                    } 
                    catch (Exception e) {
                        e.printStackTrace();
                        failed.set(true);
                    } 
                    finally {
                        completionLatch.countDown();
                    }
                }, executor);
                
                futures.add(future);
            }
            
            // Wait for all operations to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "All service operations should complete");
            assertFalse(failed.get(), "No operations should fail");
            
            // Verify all services are registered
            ServiceReference<?>[] allRefs = bundleContext.getServiceReferences(TestService.class.getName(), null);
            assertNotNull(allRefs, "Service references should be found");
            assertEquals(SERVICE_COUNT, allRefs.length, "Should find all registered services");
        } 
        finally {
            // Clean up all registrations
            for (ServiceRegistration<?> registration : registrations) {
                try {
                    registration.unregister();
                } 
                catch (Exception e) {
                    // Ignore, might be already unregistered
                }
            }
        }
    }

    @Test
    @DisplayName("Service registry handles bundle lifecycle events correctly with Java 21")
    public void testBundleLifecycleIntegration() throws BundleException {
        // Mock a bundle for testing
        Bundle mockBundle = mock(Bundle.class);
        when(mockBundle.getBundleId()).thenReturn(999L);
        when(mockBundle.getSymbolicName()).thenReturn("mock.bundle");
        
        // Get all services before our test
        ServiceReference<?>[] beforeRefs;
        try {
            beforeRefs = bundleContext.getAllServiceReferences(null, null);
        } 
        catch (InvalidSyntaxException e) {
            throw new RuntimeException("Invalid filter syntax", e);
        }
        
        int initialServiceCount = (beforeRefs != null) ? beforeRefs.length : 0;
        
        // Register a service listener to track bundle service events
        final AtomicInteger registeredCount = new AtomicInteger(0);
        final AtomicInteger unregisteredCount = new AtomicInteger(0);
        
        ServiceListener listener = event -> {
            ServiceReference<?> ref = event.getServiceReference();
            if (ref.getProperty(Constants.SERVICE_BUNDLEID).equals(999L)) {
                if (event.getType() == ServiceEvent.REGISTERED) {
                    registeredCount.incrementAndGet();
                } 
                else if (event.getType() == ServiceEvent.UNREGISTERING) {
                    unregisteredCount.incrementAndGet();
                }
            }
        };
        
        bundleContext.addServiceListener(listener);
        
        try {
            // Simulate bundle starting and registering services
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(Constants.SERVICE_BUNDLEID, 999L);
            props.put("bundle.symbolic.name", "mock.bundle");
            
            // Register multiple services from the mock bundle
            List<ServiceRegistration<?>> registrations = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                TestService service = new TestServiceImpl("Bundle service " + i);
                ServiceRegistration<TestService> reg = 
                        bundleContext.registerService(TestService.class, service, props);
                registrations.add(reg);
            }
            
            // Verify services are registered
            assertEquals(5, registeredCount.get(), "Should receive 5 service registered events");
            
            // Get all services from our mock bundle
            ServiceReference<?>[] bundleRefs;
            try {
                bundleRefs = bundleContext.getAllServiceReferences(null, 
                        "(bundle.symbolic.name=mock.bundle)");
            } 
            catch (InvalidSyntaxException e) {
                throw new RuntimeException("Invalid filter syntax", e);
            }
            
            assertNotNull(bundleRefs, "Bundle service references should be found");
            assertEquals(5, bundleRefs.length, "Should find 5 bundle services");
            
            // Unregister all services (simulating bundle stopping)
            for (ServiceRegistration<?> reg : registrations) {
                reg.unregister();
            }
            
            // Verify services are unregistered
            assertEquals(5, unregisteredCount.get(), "Should receive 5 service unregistered events");
            
            // Verify no services remain for our mock bundle
            try {
                bundleRefs = bundleContext.getAllServiceReferences(null, 
                        "(bundle.symbolic.name=mock.bundle)");
                assertTrue(bundleRefs == null || bundleRefs.length == 0, 
                        "No bundle services should remain");
            } 
            catch (InvalidSyntaxException e) {
                throw new RuntimeException("Invalid filter syntax", e);
            }
            
            // Verify total service count is back to initial
            try {
                ServiceReference<?>[] afterRefs = bundleContext.getAllServiceReferences(null, null);
                int finalServiceCount = (afterRefs != null) ? afterRefs.length : 0;
                assertEquals(initialServiceCount, finalServiceCount, 
                        "Service count should return to initial value");
            } 
            catch (InvalidSyntaxException e) {
                throw new RuntimeException("Invalid filter syntax", e);
            }
        } 
        finally {
            bundleContext.removeServiceListener(listener);
        }
    }

    @Test
    @DisplayName("Pattern matching with service properties works in Java 21")
    public void testPatternMatchingWithServiceProperties() {
        // Register a service with nested properties structure
        TestService service = new TestServiceImpl("Pattern matching test");
        
        // Create a complex property structure
        Map<String, Object> nestedMap = new ConcurrentHashMap<>();
        nestedMap.put("key1", "value1");
        nestedMap.put("key2", 42);
        
        ServiceConfig config1 = new ServiceConfig("config1", 10, nestedMap);
        ServiceConfig config2 = new ServiceConfig("config2", 20, nestedMap);
        
        List<ServiceConfig> configList = new ArrayList<>();
        configList.add(config1);
        configList.add(config2);
        
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("configs", configList);
        props.put("mainConfig", config1);
        
        ServiceRegistration<TestService> registration = 
                bundleContext.registerService(TestService.class, service, props);
        
        try {
            // Get the service reference
            ServiceReference<TestService> reference = bundleContext.getServiceReference(TestService.class);
            assertNotNull(reference, "Service reference should be found");
            
            // Use pattern matching with instanceof to process properties
            Object mainConfig = reference.getProperty("mainConfig");
            
            // Pattern matching with instanceof (Java 21 feature)
            if (mainConfig instanceof ServiceConfig(String name, int priority, Map<String, Object> attributes)) {
                // Verify extracted values from pattern match
                assertEquals("config1", name, "Name should match from pattern");
                assertEquals(10, priority, "Priority should match from pattern");
                assertEquals("value1", attributes.get("key1"), "Nested attribute should match");
            } else {
                throw new AssertionError("Pattern matching failed");
            }
            
            // Get the configs list and use pattern matching in a loop
            @SuppressWarnings("unchecked")
            List<ServiceConfig> configs = (List<ServiceConfig>) reference.getProperty("configs");
            assertNotNull(configs, "Configs list should be found");
            assertEquals(2, configs.size(), "Should have 2 configs");
            
            // Use pattern matching in a switch expression (Java 21 feature)
            for (ServiceConfig config : configs) {
                String result = switch (config) {
                    case ServiceConfig(String name, int p, var _) when p > 15 -> 
                        "High priority config: " + name;
                    case ServiceConfig(String name, int p, var _) when p <= 15 -> 
                        "Low priority config: " + name;
                    default -> "Unknown config";
                };
                
                if (config.name().equals("config1")) {
                    assertEquals("Low priority config: config1", result, 
                            "Switch pattern for config1 should match low priority");
                } else if (config.name().equals("config2")) {
                    assertEquals("High priority config: config2", result, 
                            "Switch pattern for config2 should match high priority");
                }
            }
        } 
        finally {
            registration.unregister();
        }
    }
}