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
package org.sonatype.nexus.repository.maven;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityReferenceFilterBuilder.CapabilityReferenceFilter;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;
import org.sonatype.nexus.utils.httpclient.UserAgentGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenProxyRequestHeaderSupport} specifically with Java 21 Virtual Threads.
 * <p>
 * This test class validates that MavenProxyRequestHeaderSupport correctly formats user agent strings
 * with analytics indicators when executed under Virtual Threads. It ensures that the analytics capability
 * state is properly reflected in the user agent string even when the code is running on Virtual Threads,
 * which have different thread-local variable behavior compared to platform threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("virtualthread")
public class MavenProxyRequestHeaderSupportVirtualThreadTest
    extends VirtualThreadTestSupport
{
  @Mock
  private CapabilityRegistry capabilityRegistry;

  @Mock
  private ApplicationVersion applicationVersion;

  private UserAgentGenerator userAgentGenerator;

  private MavenProxyRequestHeaderSupport underTest;

  @BeforeEach
  public void setUp() {
    assumeVirtualThreadSupported();
    userAgentGenerator = new UserAgentGenerator(applicationVersion);
    this.underTest = new MavenProxyRequestHeaderSupport(capabilityRegistry, userAgentGenerator);
    when(applicationVersion.getEdition()).thenReturn("edition");
  }

  @Test
  public void testUserAgentWithAnalyticsNotConfiguredOnVirtualThread() throws InterruptedException {
    AtomicReference<String> result = new AtomicReference<>();
    
    // Execute on a virtual thread
    runVirtual(() -> {
      result.set(underTest.getUserAgentForAnalytics());
    });
    
    String expectedUserAgent = userAgentGenerator.generate().replace(")","; pau)");
    assertEquals(expectedUserAgent, result.get());
  }

  @Test
  public void testUserAgentWithAnalyticsEnabledOnVirtualThread() throws InterruptedException {
    CapabilityReference capabilityReference = mockCapabilityReference();
    when(capabilityReference.context().isEnabled()).thenReturn(true);
    
    AtomicReference<String> result = new AtomicReference<>();
    
    // Execute on a virtual thread
    runVirtual(() -> {
      result.set(underTest.getUserAgentForAnalytics());
    });
    
    String expectedUserAgent = userAgentGenerator.generate().replace(")","; pae)");
    assertEquals(expectedUserAgent, result.get());
  }

  @Test
  public void testUserAgentWithAnalyticsDisabledOnVirtualThread() throws InterruptedException {
    CapabilityReference capabilityReference = mockCapabilityReference();
    when(capabilityReference.context().isEnabled()).thenReturn(false);
    
    AtomicReference<String> result = new AtomicReference<>();
    
    // Execute on a virtual thread
    runVirtual(() -> {
      result.set(underTest.getUserAgentForAnalytics());
    });
    
    String expectedUserAgent = userAgentGenerator.generate().replace(")","; pad)");
    assertEquals(expectedUserAgent, result.get());
  }
  
  @Test
  public void testConcurrentExecutionOnVirtualThreads() throws InterruptedException {
    // Mock capability reference for concurrent execution
    CapabilityReference capabilityReference = mockCapabilityReference();
    when(capabilityReference.context().isEnabled()).thenReturn(true);
    
    // Number of concurrent threads to test with
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicReference<Exception> testException = new AtomicReference<>();
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().name("virtual-test-" + i).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Execute the method under test
          String userAgent = underTest.getUserAgentForAnalytics();
          
          // Verify the result
          String expected = userAgentGenerator.generate().replace(")","; pae)");
          if (!expected.equals(userAgent)) {
            testException.set(new AssertionError("Expected: " + expected + ", but got: " + userAgent));
          }
        }
        catch (Exception e) {
          testException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    
    // Check if any thread encountered an exception
    if (testException.get() != null) {
      throw new AssertionError("Test failed in virtual thread", testException.get());
    }
  }
  
  @Test
  public void testThreadLocalVariableBehaviorInVirtualThreads() throws InterruptedException {
    // Set up different capability states for different threads
    CapabilityReference enabledRef = mockCapabilityReference();
    when(enabledRef.context().isEnabled()).thenReturn(true);
    
    CapabilityReference disabledRef = mockCapabilityReference();
    when(disabledRef.context().isEnabled()).thenReturn(false);
    
    // Create a latch to synchronize thread execution
    CountDownLatch latch = new CountDownLatch(2);
    
    // Results from each thread
    AtomicReference<String> result1 = new AtomicReference<>();
    AtomicReference<String> result2 = new AtomicReference<>();
    
    // First thread - analytics enabled
    Thread thread1 = Thread.ofVirtual().start(() -> {
      try {
        // Set up the mock for this thread
        when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
            .thenReturn(Collections.singleton(enabledRef));
        
        // Get the user agent
        result1.set(underTest.getUserAgentForAnalytics());
      }
      finally {
        latch.countDown();
      }
    });
    
    // Second thread - analytics disabled
    Thread thread2 = Thread.ofVirtual().start(() -> {
      try {
        // Set up the mock for this thread
        when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
            .thenReturn(Collections.singleton(disabledRef));
        
        // Get the user agent
        result2.set(underTest.getUserAgentForAnalytics());
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for both threads to complete
    latch.await();
    
    // Verify that each thread got the correct result
    String expectedEnabled = userAgentGenerator.generate().replace(")","; pae)");
    String expectedDisabled = userAgentGenerator.generate().replace(")","; pad)");
    
    assertEquals(expectedEnabled, result1.get(), "Thread 1 should have analytics enabled");
    assertEquals(expectedDisabled, result2.get(), "Thread 2 should have analytics disabled");
  }

  private CapabilityReference mockCapabilityReference() {
    CapabilityReference capabilityReference = mock(CapabilityReference.class);
    CapabilityContext capabilityContext = mock(CapabilityContext.class);
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
        .thenReturn(Collections.singleton(capabilityReference));
    when(capabilityReference.context()).thenReturn(capabilityContext);
    return capabilityReference;
  }
}