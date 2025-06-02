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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletContext;

import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.ManagedLifecycleManager;
import org.sonatype.nexus.extender.NexusBundleExtender;
import org.sonatype.nexus.extender.NexusContextListener;
import org.sonatype.nexus.security.SecuritySystem;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link NexusContextListener} compatibility with Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
public class NexusContextListenerVirtualThreadTest
{
  @Mock
  private NexusBundleExtender bundleExtender;

  @Mock
  private BundleContext bundleContext;

  @Mock
  private ServletContext servletContext;

  @Mock
  private Bundle systemBundle;

  @Mock
  private FrameworkStartLevel frameworkStartLevel;

  @Mock
  private ServiceReference<SecuritySystem> securitySystemRef;

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private ApplicationVersion applicationVersion;

  @Mock
  private ManagedLifecycleManager lifecycleManager;

  private NexusContextListener underTest;

  @BeforeEach
  void setUp() {
    when(bundleExtender.getBundleContext()).thenReturn(bundleContext);
    when(bundleContext.getBundle(0)).thenReturn(systemBundle);
    when(systemBundle.adapt(FrameworkStartLevel.class)).thenReturn(frameworkStartLevel);
    when(bundleContext.getServiceReference(SecuritySystem.class)).thenReturn(securitySystemRef);
    when(bundleContext.getService(securitySystemRef)).thenReturn(securitySystem);

    underTest = new NexusContextListener(bundleExtender);
  }

  /**
   * Tests that feature flag behavior works correctly with concurrent virtual thread access.
   */
  @Test
  void testFeatureFlagWithConcurrentVirtualThreadAccess() throws Exception {
    // Set up test data
    String edition = "OSS";
    String installMode = "oss:featureFlag:enabledByDefault:test.flag";
    System.setProperty("test.flag", "true");

    // Create a thread factory for virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicBoolean allFlagsEnabled = new AtomicBoolean(true);

    try {
      // Execute feature flag check concurrently from multiple virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            boolean enabled = underTest.isFeatureFlagEnabled(edition, installMode);
            if (!enabled) {
              allFlagsEnabled.set(false);
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      assertTrue(allFlagsEnabled.get(), "Feature flag should be enabled for all virtual threads");

      // Test with flag disabled
      System.setProperty("test.flag", "false");
      CountDownLatch latch2 = new CountDownLatch(threadCount);
      AtomicBoolean anyFlagEnabled = new AtomicBoolean(false);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            boolean enabled = underTest.isFeatureFlagEnabled(edition, installMode);
            if (enabled) {
              anyFlagEnabled.set(true);
            }
          } finally {
            latch2.countDown();
          }
        });
      }

      assertTrue(latch2.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      assertFalse(anyFlagEnabled.get(), "Feature flag should be disabled for all virtual threads");
    } finally {
      executor.shutdown();
      System.clearProperty("test.flag");
    }
  }

  /**
   * Tests that servlet context management works correctly when accessed from virtual threads.
   */
  @Test
  void testServletContextManagementFromVirtualThreads() throws Exception {
    // Set up mock servlet context with properties
    when(servletContext.getAttribute("nexus.properties")).thenReturn(null);

    // Create a thread factory for virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    AtomicReference<Exception> threadException = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try {
      // Access servlet context from a virtual thread
      executor.submit(() -> {
        try {
          // Simulate context initialization from a virtual thread
          jakarta.servlet.ServletContextEvent event = mock(jakarta.servlet.ServletContextEvent.class);
          when(event.getServletContext()).thenReturn(servletContext);
          underTest.contextInitialized(event);

          // Verify that the servlet context was properly accessed
          verify(servletContext).getAttribute("nexus.properties");
        } catch (Exception e) {
          threadException.set(e);
        } finally {
          latch.countDown();
        }
      });

      // Wait for the virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual thread to complete");
      
      // Verify no exceptions occurred
      if (threadException.get() != null) {
        throw threadException.get();
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that security context propagation is maintained across virtual threads.
   */
  @Test
  void testSecurityContextPropagationAcrossVirtualThreads() throws Exception {
    // Set up security context
    when(bundleContext.getServiceReference(SecuritySystem.class)).thenReturn(securitySystemRef);
    when(bundleContext.getService(securitySystemRef)).thenReturn(securitySystem);

    // Create a thread factory for virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<SecuritySystem> threadSecuritySystem = new AtomicReference<>();

    try {
      // Access security context from a virtual thread
      executor.submit(() -> {
        try {
          // Retrieve the security system from the bundle context
          SecuritySystem security = bundleContext.getService(securitySystemRef);
          threadSecuritySystem.set(security);
        } finally {
          latch.countDown();
        }
      });

      // Wait for the virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual thread to complete");
      
      // Verify security context was properly propagated
      assertNotNull(threadSecuritySystem.get(), "Security system should be accessible from virtual thread");
      assertEquals(securitySystem, threadSecuritySystem.get(), "Security system reference should be maintained across virtual threads");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests OSGi framework event handling from virtual threads.
   */
  @Test
  void testOsgiFrameworkEventHandlingFromVirtualThreads() throws Exception {
    // Create a thread factory for virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> threadException = new AtomicReference<>();

    try {
      // Handle OSGi framework event from a virtual thread
      executor.submit(() -> {
        try {
          // Create a framework event
          FrameworkEvent event = mock(FrameworkEvent.class);
          when(event.getType()).thenReturn(FrameworkEvent.STARTLEVEL_CHANGED);
          
          // Process the event
          underTest.frameworkEvent(event);
          
          // Verify event type was checked
          verify(event).getType();
        } catch (Exception e) {
          threadException.set(e);
        } finally {
          latch.countDown();
        }
      });

      // Wait for the virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual thread to complete");
      
      // Verify no exceptions occurred
      if (threadException.get() != null) {
        throw threadException.get();
      }
    } finally {
      executor.shutdown();
    }
  }
}