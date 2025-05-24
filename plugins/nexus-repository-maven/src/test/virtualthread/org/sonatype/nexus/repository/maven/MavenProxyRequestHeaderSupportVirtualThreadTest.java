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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityReferenceFilterBuilder.CapabilityReferenceFilter;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadTestGroup;
import org.sonatype.nexus.utils.httpclient.UserAgentGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link MavenProxyRequestHeaderSupport} with Java 21 Virtual Threads to ensure
 * thread-local variables and capability state are correctly handled in a virtual thread environment.
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class MavenProxyRequestHeaderSupportVirtualThreadTest
    extends TestSupport
{
  @Mock
  private CapabilityRegistry capabilityRegistry;

  @Mock
  private ApplicationVersion applicationVersion;

  private UserAgentGenerator userAgentGenerator;

  private MavenProxyRequestHeaderSupport underTest;

  @BeforeEach
  public void setUp() {
    userAgentGenerator = new UserAgentGenerator(applicationVersion);
    this.underTest = new MavenProxyRequestHeaderSupport(capabilityRegistry, userAgentGenerator);
    when(applicationVersion.getEdition()).thenReturn("edition");
  }

  /**
   * Tests that user agent string is correctly formatted when analytics is not configured
   * when executed in a virtual thread.
   */
  @Test
  public void testUserAgentWithAnalyticsNotConfiguredInVirtualThread() throws Exception {
    AtomicReference<String> result = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      result.set(underTest.getUserAgentForAnalytics());
    });
    
    virtualThread.join();
    
    String userAgentForAnalytics = result.get();
    String expectedUserAgent = userAgentGenerator.generate().replace(")","; pau)");
    
    assertNotNull(userAgentForAnalytics, "User agent should not be null when executed in virtual thread");
    assertEquals(expectedUserAgent, userAgentForAnalytics);
    assertTrue(virtualThread.isVirtual(), "Thread should be a virtual thread");
  }

  /**
   * Tests that user agent string is correctly formatted when analytics is enabled
   * when executed in a virtual thread.
   */
  @Test
  public void testUserAgentWithAnalyticsEnabledInVirtualThread() throws Exception {
    CapabilityReference capabilityReference = mockCapabilityReference();
    when(capabilityReference.context().isEnabled()).thenReturn(true);
    
    AtomicReference<String> result = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      result.set(underTest.getUserAgentForAnalytics());
    });
    
    virtualThread.join();
    
    String userAgentForAnalytics = result.get();
    String expectedUserAgent = userAgentGenerator.generate().replace(")","; pae)");
    
    assertNotNull(userAgentForAnalytics, "User agent should not be null when executed in virtual thread");
    assertEquals(expectedUserAgent, userAgentForAnalytics);
    assertTrue(virtualThread.isVirtual(), "Thread should be a virtual thread");
  }

  /**
   * Tests that user agent string is correctly formatted when analytics is disabled
   * when executed in a virtual thread.
   */
  @Test
  public void testUserAgentWithAnalyticsDisabledInVirtualThread() throws Exception {
    CapabilityReference capabilityReference = mockCapabilityReference();
    when(capabilityReference.context().isEnabled()).thenReturn(false);
    
    AtomicReference<String> result = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      result.set(underTest.getUserAgentForAnalytics());
    });
    
    virtualThread.join();
    
    String userAgentForAnalytics = result.get();
    String expectedUserAgent = userAgentGenerator.generate().replace(")","; pad)");
    
    assertNotNull(userAgentForAnalytics, "User agent should not be null when executed in virtual thread");
    assertEquals(expectedUserAgent, userAgentForAnalytics);
    assertTrue(virtualThread.isVirtual(), "Thread should be a virtual thread");
  }

  /**
   * Tests concurrent execution of getUserAgentForAnalytics with multiple virtual threads
   * to verify thread-local variable behavior is consistent.
   */
  @Test
  public void testConcurrentExecutionWithVirtualThreads() throws Exception {
    // Set up different capability states for testing
    CapabilityReference capabilityReference = mockCapabilityReference();
    when(capabilityReference.context().isEnabled()).thenReturn(true);
    
    final int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create virtual threads using the ExecutorService
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Future<?>[] futures = new Future<?>[threadCount];
    
    // Start multiple virtual threads that will execute simultaneously
    for (int i = 0; i < threadCount; i++) {
      futures[i] = executor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Execute the method under test
          String userAgent = underTest.getUserAgentForAnalytics();
          
          // Verify the result
          String expected = userAgentGenerator.generate().replace(")","; pae)");
          assertEquals(expected, userAgent, "User agent should be consistent across virtual threads");
          
          // Signal completion
          completionLatch.countDown();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
    assertTrue(completed, "All virtual threads should complete within the timeout period");
    
    // Check for any exceptions
    for (Future<?> future : futures) {
      try {
        future.get(); // Will throw an exception if the thread failed
      }
      catch (ExecutionException e) {
        throw new AssertionError("Virtual thread execution failed", e.getCause());
      }
    }
    
    executor.shutdown();
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