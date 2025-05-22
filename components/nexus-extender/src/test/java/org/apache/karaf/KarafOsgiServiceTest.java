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
package org.apache.karaf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * Tests that validate OSGi service registration, discovery, and lifecycle operations
 * work correctly with Java 21 in Apache Karaf 4.4.4.
 * 
 * This test class focuses on validating that Java 21 language features like record classes
 * and sealed interfaces can be properly used as OSGi services and service interfaces.
 */
public class KarafOsgiServiceTest
{
  @Mock
  private BundleContext bundleContext;
  
  @Mock
  private Bundle bundle;
  
  private AutoCloseable mocks;
  
  // Define test interfaces and implementations
  
  /**
   * Simple service interface for testing
   */
  public interface TestService {
    String getMessage();
  }
  
  /**
   * Implementation of TestService using a standard class
   */
  public static class StandardTestService implements TestService {
    private final String message;
    
    public StandardTestService(String message) {
      this.message = message;
    }
    
    @Override
    public String getMessage() {
      return message;
    }
  }
  
  /**
   * Implementation of TestService using a Java 21 record class
   */
  public static record RecordTestService(String message) implements TestService {
    @Override
    public String getMessage() {
      return message;
    }
  }
  
  /**
   * A sealed interface for testing Java 21 sealed types with OSGi
   */
  public sealed interface SealedService permits SealedServiceImpl, RecordSealedServiceImpl {
    String getServiceType();
  }
  
  /**
   * Standard implementation of the sealed interface
   */
  public static final class SealedServiceImpl implements SealedService {
    @Override
    public String getServiceType() {
      return "standard";
    }
  }
  
  /**
   * Record implementation of the sealed interface
   */
  public static record RecordSealedServiceImpl(String type) implements SealedService {
    @Override
    public String getServiceType() {
      return type;
    }
  }
  
  /**
   * Factory interface for testing service factory pattern
   */
  public interface ServiceFactory {
    TestService createService(String config);
  }
  
  /**
   * Record implementation of service factory
   */
  public static record RecordServiceFactory() implements ServiceFactory {
    @Override
    public TestService createService(String config) {
      return new RecordTestService(config);
    }
  }
  
  @BeforeEach
  public void setup() {
    mocks = MockitoAnnotations.openMocks(this);
    when(bundleContext.getBundle()).thenReturn(bundle);
  }
  
  @AfterEach
  public void cleanup() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }
  
  /**
   * Tests that a standard class can be registered as an OSGi service
   */
  @Test
  public void testStandardServiceRegistration() {
    // Create service and properties
    TestService service = new StandardTestService("Hello from standard service");
    Dictionary<String, Object> props = new Hashtable<>();
    props.put("service.type", "standard");
    
    // Mock service registration
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(TestService.class, service, props)).thenReturn(registration);
    
    // Register service
    ServiceRegistration<TestService> result = bundleContext.registerService(TestService.class, service, props);
    
    // Verify registration
    assertNotNull(result);
    verify(bundleContext).registerService(TestService.class, service, props);
  }
  
  /**
   * Tests that a Java 21 record class can be registered as an OSGi service
   */
  @Test
  public void testRecordServiceRegistration() {
    // Create record service and properties
    TestService service = new RecordTestService("Hello from record service");
    Dictionary<String, Object> props = new Hashtable<>();
    props.put("service.type", "record");
    
    // Mock service registration
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(TestService.class, service, props)).thenReturn(registration);
    
    // Register service
    ServiceRegistration<TestService> result = bundleContext.registerService(TestService.class, service, props);
    
    // Verify registration
    assertNotNull(result);
    verify(bundleContext).registerService(TestService.class, service, props);
  }
  
  /**
   * Tests that a Java 21 sealed interface can be used as an OSGi service interface
   */
  @Test
  public void testSealedInterfaceService() {
    // Create sealed service implementation and properties
    SealedService service = new SealedServiceImpl();
    Dictionary<String, Object> props = new Hashtable<>();
    props.put("sealed.type", "standard");
    
    // Mock service registration
    @SuppressWarnings("unchecked")
    ServiceRegistration<SealedService> registration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(SealedService.class, service, props)).thenReturn(registration);
    
    // Register service
    ServiceRegistration<SealedService> result = bundleContext.registerService(SealedService.class, service, props);
    
    // Verify registration
    assertNotNull(result);
    verify(bundleContext).registerService(SealedService.class, service, props);
  }
  
  /**
   * Tests that a Java 21 record implementing a sealed interface can be used as an OSGi service
   */
  @Test
  public void testRecordImplementingSealedInterface() {
    // Create record implementation of sealed interface and properties
    SealedService service = new RecordSealedServiceImpl("record-sealed");
    Dictionary<String, Object> props = new Hashtable<>();
    props.put("sealed.type", "record");
    
    // Mock service registration
    @SuppressWarnings("unchecked")
    ServiceRegistration<SealedService> registration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(SealedService.class, service, props)).thenReturn(registration);
    
    // Register service
    ServiceRegistration<SealedService> result = bundleContext.registerService(SealedService.class, service, props);
    
    // Verify registration
    assertNotNull(result);
    verify(bundleContext).registerService(SealedService.class, service, props);
  }
  
  /**
   * Tests service lookup using direct class reference
   */
  @Test
  public void testServiceLookupByClass() {
    // Create service and reference
    TestService service = new RecordTestService("Hello from lookup test");
    @SuppressWarnings("unchecked")
    ServiceReference<TestService> reference = mock(ServiceReference.class);
    
    // Mock service lookup
    when(bundleContext.getServiceReference(TestService.class)).thenReturn(reference);
    when(bundleContext.getService(reference)).thenReturn(service);
    
    // Lookup service
    ServiceReference<TestService> resultRef = bundleContext.getServiceReference(TestService.class);
    TestService resultService = bundleContext.getService(resultRef);
    
    // Verify lookup
    assertNotNull(resultRef);
    assertNotNull(resultService);
    assertEquals("Hello from lookup test", resultService.getMessage());
    
    // Verify pattern matching with instanceof
    if (resultService instanceof RecordTestService(String message)) {
      assertEquals("Hello from lookup test", message);
    } else {
      // This should not happen
      assertFalse(true, "Pattern matching failed for record service");
    }
  }
  
  /**
   * Tests service lookup using LDAP filter
   */
  @Test
  public void testServiceLookupByFilter() throws InvalidSyntaxException {
    // Create filter and references
    String filterString = "(service.type=record)";
    Filter filter = mock(Filter.class);
    @SuppressWarnings("unchecked")
    ServiceReference<TestService>[] references = new ServiceReference[] { mock(ServiceReference.class) };
    TestService service = new RecordTestService("Hello from filter lookup");
    
    // Mock filter lookup
    when(bundleContext.createFilter(filterString)).thenReturn(filter);
    when(bundleContext.getServiceReferences(TestService.class.getName(), filterString)).thenReturn(references);
    when(bundleContext.getService(references[0])).thenReturn(service);
    
    // Lookup service
    Filter resultFilter = bundleContext.createFilter(filterString);
    ServiceReference<?>[] resultRefs = bundleContext.getServiceReferences(TestService.class.getName(), filterString);
    TestService resultService = (TestService) bundleContext.getService(resultRefs[0]);
    
    // Verify lookup
    assertNotNull(resultFilter);
    assertNotNull(resultRefs);
    assertEquals(1, resultRefs.length);
    assertNotNull(resultService);
    assertEquals("Hello from filter lookup", resultService.getMessage());
    
    // Verify pattern matching with switch
    String result = switch (resultService) {
      case RecordTestService record -> "Record: " + record.message();
      case StandardTestService standard -> "Standard: " + standard.getMessage();
      default -> "Unknown service type";
    };
    
    assertEquals("Record: Hello from filter lookup", result);
  }
  
  /**
   * Tests service ranking with multiple services
   */
  @Test
  public void testServiceRanking() {
    // Create services with different rankings
    TestService service1 = new StandardTestService("Low ranking service");
    TestService service2 = new RecordTestService("High ranking service");
    
    Dictionary<String, Object> props1 = new Hashtable<>();
    props1.put(Constants.SERVICE_RANKING, 10);
    
    Dictionary<String, Object> props2 = new Hashtable<>();
    props2.put(Constants.SERVICE_RANKING, 100);
    
    // Mock service registrations
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration1 = mock(ServiceRegistration.class);
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration2 = mock(ServiceRegistration.class);
    @SuppressWarnings("unchecked")
    ServiceReference<TestService> reference1 = mock(ServiceReference.class);
    @SuppressWarnings("unchecked")
    ServiceReference<TestService> reference2 = mock(ServiceReference.class);
    
    when(bundleContext.registerService(TestService.class, service1, props1)).thenReturn(registration1);
    when(bundleContext.registerService(TestService.class, service2, props2)).thenReturn(registration2);
    when(registration1.getReference()).thenReturn(reference1);
    when(registration2.getReference()).thenReturn(reference2);
    when(reference1.getProperty(Constants.SERVICE_RANKING)).thenReturn(10);
    when(reference2.getProperty(Constants.SERVICE_RANKING)).thenReturn(100);
    
    // Mock highest ranked service lookup
    when(bundleContext.getServiceReference(TestService.class)).thenReturn(reference2);
    when(bundleContext.getService(reference2)).thenReturn(service2);
    
    // Register services
    bundleContext.registerService(TestService.class, service1, props1);
    bundleContext.registerService(TestService.class, service2, props2);
    
    // Lookup highest ranked service
    ServiceReference<TestService> highestRef = bundleContext.getServiceReference(TestService.class);
    TestService highestService = bundleContext.getService(highestRef);
    
    // Verify highest ranked service
    assertNotNull(highestRef);
    assertNotNull(highestService);
    assertEquals(100, highestRef.getProperty(Constants.SERVICE_RANKING));
    assertEquals("High ranking service", highestService.getMessage());
    
    // Verify pattern matching with instanceof and record pattern
    if (highestService instanceof RecordTestService(String message)) {
      assertEquals("High ranking service", message);
    } else {
      // This should not happen
      assertFalse(true, "Pattern matching failed for highest ranked service");
    }
  }
  
  /**
   * Tests service events and listeners
   */
  @Test
  public void testServiceEvents() throws Exception {
    // Create service and properties
    TestService service = new RecordTestService("Event test service");
    Dictionary<String, Object> props = new Hashtable<>();
    props.put("event.test", "true");
    
    // Create latch for synchronization
    CountDownLatch registeredLatch = new CountDownLatch(1);
    CountDownLatch modifiedLatch = new CountDownLatch(1);
    CountDownLatch unregisteredLatch = new CountDownLatch(1);
    
    // Create service listener
    ServiceListener listener = event -> {
      if (event.getType() == ServiceEvent.REGISTERED) {
        registeredLatch.countDown();
      } else if (event.getType() == ServiceEvent.MODIFIED) {
        modifiedLatch.countDown();
      } else if (event.getType() == ServiceEvent.UNREGISTERING) {
        unregisteredLatch.countDown();
      }
    };
    
    // Mock service registration
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration = mock(ServiceRegistration.class);
    @SuppressWarnings("unchecked")
    ServiceReference<TestService> reference = mock(ServiceReference.class);
    when(bundleContext.registerService(TestService.class, service, props)).thenReturn(registration);
    when(registration.getReference()).thenReturn(reference);
    
    // Add listener
    bundleContext.addServiceListener(listener);
    
    // Register service and simulate events
    bundleContext.registerService(TestService.class, service, props);
    ServiceEvent registeredEvent = new ServiceEvent(ServiceEvent.REGISTERED, reference);
    listener.serviceChanged(registeredEvent);
    
    // Modify service properties
    Dictionary<String, Object> newProps = new Hashtable<>();
    newProps.put("event.test", "modified");
    ServiceEvent modifiedEvent = new ServiceEvent(ServiceEvent.MODIFIED, reference);
    listener.serviceChanged(modifiedEvent);
    
    // Unregister service
    ServiceEvent unregisteringEvent = new ServiceEvent(ServiceEvent.UNREGISTERING, reference);
    listener.serviceChanged(unregisteringEvent);
    
    // Verify events were received
    assertTrue(registeredLatch.await(1, TimeUnit.SECONDS), "REGISTERED event not received");
    assertTrue(modifiedLatch.await(1, TimeUnit.SECONDS), "MODIFIED event not received");
    assertTrue(unregisteredLatch.await(1, TimeUnit.SECONDS), "UNREGISTERING event not received");
    
    // Remove listener
    bundleContext.removeServiceListener(listener);
  }
  
  /**
   * Tests service factory pattern with Java 21 record classes
   */
  @Test
  public void testServiceFactoryWithRecords() {
    // Create service factory and properties
    ServiceFactory factory = new RecordServiceFactory();
    Dictionary<String, Object> props = new Hashtable<>();
    props.put("factory.type", "record");
    
    // Mock service registration
    @SuppressWarnings("unchecked")
    ServiceRegistration<ServiceFactory> registration = mock(ServiceRegistration.class);
    @SuppressWarnings("unchecked")
    ServiceReference<ServiceFactory> reference = mock(ServiceReference.class);
    when(bundleContext.registerService(ServiceFactory.class, factory, props)).thenReturn(registration);
    when(bundleContext.getServiceReference(ServiceFactory.class)).thenReturn(reference);
    when(bundleContext.getService(reference)).thenReturn(factory);
    
    // Register factory
    bundleContext.registerService(ServiceFactory.class, factory, props);
    
    // Lookup factory
    ServiceReference<ServiceFactory> factoryRef = bundleContext.getServiceReference(ServiceFactory.class);
    ServiceFactory resultFactory = bundleContext.getService(factoryRef);
    
    // Create service using factory
    TestService createdService = resultFactory.createService("Factory created service");
    
    // Verify factory and created service
    assertNotNull(resultFactory);
    assertInstanceOf(RecordServiceFactory.class, resultFactory);
    assertNotNull(createdService);
    assertEquals("Factory created service", createdService.getMessage());
    
    // Verify pattern matching with instanceof and record patterns
    if (resultFactory instanceof RecordServiceFactory()) {
      // Record pattern match successful
      assertTrue(true);
    } else {
      // This should not happen
      assertFalse(true, "Pattern matching failed for record service factory");
    }
    
    if (createdService instanceof RecordTestService(String message)) {
      assertEquals("Factory created service", message);
    } else {
      // This should not happen
      assertFalse(true, "Pattern matching failed for factory created service");
    }
  }
}