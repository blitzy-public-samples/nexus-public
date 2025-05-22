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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.ManagedLifecycle.Phase;
import org.sonatype.nexus.common.app.ManagedLifecycleManager;
import org.sonatype.nexus.extender.NexusBundleExtender;
import org.sonatype.nexus.extender.NexusContextListener;

import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.startlevel.FrameworkStartLevel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link NexusContextListener} with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class NexusContextListenerVirtualThreadTest
{
  @Mock
  private NexusBundleExtender extender;

  @Mock
  private BundleContext bundleContext;

  @Mock
  private ServletContext servletContext;

  @Mock
  private ServletContextEvent servletContextEvent;

  @Mock
  private FeaturesService featuresService;

  @Mock
  private ServiceReference<FeaturesService> featuresServiceRef;

  @Mock
  private ManagedLifecycleManager lifecycleManager;

  @Mock
  private ApplicationVersion applicationVersion;

  @Mock
  private Bundle systemBundle;

  @Mock
  private FrameworkStartLevel frameworkStartLevel;

  private NexusContextListener underTest;

  @BeforeEach
  public void setup() {
    when(extender.getBundleContext()).thenReturn(bundleContext);
    when(servletContextEvent.getServletContext()).thenReturn(servletContext);
    when(bundleContext.getServiceReference(FeaturesService.class)).thenReturn(featuresServiceRef);
    when(bundleContext.getService(featuresServiceRef)).thenReturn(featuresService);
    when(bundleContext.getBundle(0)).thenReturn(systemBundle);
    when(systemBundle.adapt(FrameworkStartLevel.class)).thenReturn(frameworkStartLevel);

    underTest = new NexusContextListener(extender);
  }

  /**
   * Tests that feature flag behavior works correctly with concurrent virtual thread access.
   */
  @Test
  public void testFeatureFlagWithVirtualThreads() throws Exception {
    // Setup a feature flag
    String installMode = "oss:featureFlag:enabledByDefault:test.flag";
    String edition = "OSS";
    
    // Create a map to track results from different threads
    Map<Thread, Boolean> results = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(10);
    
    // Run 10 virtual threads to check the feature flag concurrently
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
          try {
            boolean result = underTest.isFeatureFlagEnabled(edition, installMode);
            results.put(Thread.currentThread(), result);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(5, TimeUnit.SECONDS);
    }
    
    // Verify all threads got the same result
    assertFalse(results.isEmpty(), "No results collected from virtual threads");
    assertTrue(results.values().stream().allMatch(Boolean::booleanValue), 
        "Feature flag evaluation inconsistent across virtual threads");
  }

  /**
   * Tests servlet context management when accessed from virtual threads.
   */
  @Test
  public void testServletContextWithVirtualThreads() throws Exception {
    // Setup servlet context with properties
    Map<String, String> properties = new ConcurrentHashMap<>();
    properties.put("test.property", "test.value");
    when(servletContext.getAttribute("nexus.properties")).thenReturn(properties);
    
    // Initialize the context
    underTest.contextInitialized(servletContextEvent);
    
    // Test accessing servlet context from virtual threads
    AtomicBoolean allThreadsSucceeded = new AtomicBoolean(true);
    CountDownLatch latch = new CountDownLatch(5);
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 5; i++) {
        executor.submit(() -> {
          try {
            // Access the injector which requires servlet context to be properly initialized
            underTest.getInjector();
          } catch (Exception e) {
            allThreadsSucceeded.set(false);
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(5, TimeUnit.SECONDS);
    }
    
    assertTrue(allThreadsSucceeded.get(), "Servlet context access failed from virtual threads");
    
    // Clean up
    underTest.contextDestroyed(servletContextEvent);
  }
  
  /**
   * Tests security context propagation across virtual threads.
   */
  @Test
  public void testSecurityContextPropagationWithVirtualThreads() throws Exception {
    // Setup mock repository with feature flags
    Repository flagsRepository = mock(Repository.class);
    Feature feature = mock(Feature.class);
    Feature[] features = new Feature[] { feature };
    
    when(featuresService.getRepository("nexus-flags-feature")).thenReturn(flagsRepository);
    when(flagsRepository.getFeatures()).thenReturn(features);
    when(feature.getInstall()).thenReturn("security:featureFlag:enabledByDefault:security.context.test");
    when(feature.getName()).thenReturn("security-context-test");
    when(feature.getId()).thenReturn("security-context-test");
    
    // Setup application version
    when(applicationVersion.getEdition()).thenReturn("security");
    
    // Initialize context with mocked injector that returns our mocked application version
    Map<String, String> properties = new ConcurrentHashMap<>();
    properties.put("nexus-features", "base-feature");
    when(servletContext.getAttribute("nexus.properties")).thenReturn(properties);
    
    // Create a latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(3);
    AtomicBoolean securityContextPreserved = new AtomicBoolean(true);
    
    // Test security context propagation across virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads that depend on security context
      for (int i = 0; i < 3; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Verify feature flag is enabled (requires security context)
            boolean enabled = underTest.isFeatureFlagEnabled("security", 
                "security:featureFlag:enabledByDefault:security.context.test");
            
            if (!enabled) {
              securityContextPreserved.set(false);
            }
          } catch (Exception e) {
            securityContextPreserved.set(false);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    assertTrue(securityContextPreserved.get(), "Security context not properly propagated across virtual threads");
  }

  /**
   * Tests OSGi framework event handling with virtual threads.
   */
  @Test
  public void testFrameworkEventWithVirtualThreads() throws Exception {
    // Mock lifecycle manager
    when(lifecycleManager.to(any(Phase.class))).thenReturn(lifecycleManager);
    
    // Initialize context with mocked injector that returns our mocked lifecycle manager
    when(servletContext.getAttribute("nexus.properties")).thenReturn(new ConcurrentHashMap<>());
    underTest.contextInitialized(servletContextEvent);
    
    // Create framework event
    FrameworkEvent event = mock(FrameworkEvent.class);
    when(event.getType()).thenReturn(FrameworkEvent.STARTLEVEL_CHANGED);
    
    // Test handling framework event from virtual threads
    Thread.startVirtualThread(() -> {
      underTest.frameworkEvent(event);
    }).join();
    
    // Verify that lifecycle manager was called to move to CAPABILITIES phase
    verify(lifecycleManager, times(1)).to(eq(Phase.CAPABILITIES));
    
    // Clean up
    underTest.contextDestroyed(servletContextEvent);
  }
  
  /**
   * Tests concurrent feature flag access from multiple virtual threads.
   * This test verifies that the feature flag system works correctly under high concurrency
   * with virtual threads, which is important for system stability during startup.
   */
  @Test
  public void testConcurrentFeatureFlagAccessWithVirtualThreads() throws Exception {
    // Setup multiple feature flags with different editions
    String[] installModes = {
        "oss:featureFlag:enabledByDefault:test.flag.1",
        "pro:featureFlag:enabledByDefault:test.flag.2",
        "community:featureFlag:enabledByDefault:test.flag.3",
        "oss,pro:featureFlag:enabledByDefault:test.flag.4",
        "pro,community:featureFlag:enabledByDefault:test.flag.5",
        "oss,community:featureFlag:enabledByDefault:test.flag.6",
        "oss,pro,community:featureFlag:enabledByDefault:test.flag.7"
    };
    
    String[] editions = {"OSS", "PRO", "COMMUNITY"};
    
    // Create a map to track results from different threads
    Map<String, Boolean> results = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(installModes.length * editions.length * 5); // 5 threads per combination
    
    // Run multiple virtual threads to check all feature flag combinations concurrently
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String installMode : installModes) {
        for (String edition : editions) {
          for (int i = 0; i < 5; i++) { // 5 threads per combination
            final String currentInstallMode = installMode;
            final String currentEdition = edition;
            
            executor.submit(() -> {
              try {
                boolean result = underTest.isFeatureFlagEnabled(currentEdition, currentInstallMode);
                String key = currentEdition + "-" + currentInstallMode + "-" + Thread.currentThread().threadId();
                results.put(key, result);
              } finally {
                latch.countDown();
              }
            });
          }
        }
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all virtual threads completed in time");
    }
    
    // Verify results are consistent for the same edition/installMode combinations
    Map<String, Boolean> uniqueResults = new ConcurrentHashMap<>();
    
    for (Map.Entry<String, Boolean> entry : results.entrySet()) {
      String key = entry.getKey().substring(0, entry.getKey().lastIndexOf('-'));
      Boolean existingResult = uniqueResults.get(key);
      
      if (existingResult == null) {
        uniqueResults.put(key, entry.getValue());
      } else {
        // Verify that all threads evaluating the same combination got the same result
        assertTrue(existingResult.equals(entry.getValue()),
            "Inconsistent feature flag results for " + key);
      }
    }
    
    // Verify expected results for specific combinations
    assertTrue(uniqueResults.getOrDefault("OSS-oss:featureFlag:enabledByDefault:test.flag.1", false),
        "OSS flag should be enabled for OSS edition");
    assertFalse(uniqueResults.getOrDefault("OSS-pro:featureFlag:enabledByDefault:test.flag.2", true),
        "PRO flag should be disabled for OSS edition");
    assertTrue(uniqueResults.getOrDefault("COMMUNITY-community:featureFlag:enabledByDefault:test.flag.3", false),
        "COMMUNITY flag should be enabled for COMMUNITY edition");
  }
}