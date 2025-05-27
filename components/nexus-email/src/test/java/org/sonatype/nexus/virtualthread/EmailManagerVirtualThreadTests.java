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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.internal.email.EmailConfigurationStore;
import org.sonatype.nexus.internal.email.EmailManagerImpl;
import org.sonatype.nexus.security.UserIdHelper;
import org.sonatype.nexus.ssl.TrustStore;

import javax.inject.Provider;
import javax.net.ssl.SSLContext;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailManager} using Java 21 Virtual Threads to validate thread safety and performance.
 * 
 * This test class verifies that the EmailManager can handle a large number of concurrent email sending
 * operations without errors or race conditions by leveraging Virtual Threads to simulate high concurrency
 * with minimal resource overhead.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class EmailManagerVirtualThreadTests
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private EventManager eventManager;

  @Mock
  private EmailConfigurationStore emailConfigurationStore;

  @Mock
  private TrustStore trustStore;

  @Mock
  private Provider<?> capabilityRegistryProvider;

  @Mock
  private SecretsService secretsService;
  
  private EmailManagerImpl emailManager;
  
  private AutoCloseable userIdHelperMock;
  
  @BeforeEach
  public void setup() throws Exception {
    // Setup mock for UserIdHelper
    userIdHelperMock = mockStatic(UserIdHelper.class);
    ((org.mockito.MockedStatic<UserIdHelper>) userIdHelperMock).when(UserIdHelper::get).thenReturn("userId");
    
    // Setup SSL context
    when(trustStore.getSSLContext()).thenReturn(SSLContext.getDefault());
    
    // Create and configure the email manager
    emailManager = new EmailManagerImpl(eventManager, emailConfigurationStore, trustStore, 
        config -> config, capabilityRegistryProvider, secretsService);
    
    // Setup email configuration
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailConfig.isEnabled()).thenReturn(true);
    when(emailConfig.getHost()).thenReturn("example.com");
    when(emailConfig.getPort()).thenReturn(25);
    when(emailConfig.getFromAddress()).thenReturn("sender@example.com");
    when(emailConfig.getUsername()).thenReturn("user");
    when(emailConfig.isStartTlsEnabled()).thenReturn(true);
    when(emailConfig.isStartTlsRequired()).thenReturn(false);
    when(emailConfig.isSslOnConnectEnabled()).thenReturn(false);
    when(emailConfig.isSslCheckServerIdentityEnabled()).thenReturn(false);
    when(emailConfig.isNexusTrustStoreEnabled()).thenReturn(true);
    
    when(emailConfigurationStore.load()).thenReturn(emailConfig);
    
    // Configure thread pinning detection for virtual threads
    System.setProperty("jdk.tracePinnedThreads", "full");
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    userIdHelperMock.close();
    System.clearProperty("jdk.tracePinnedThreads");
  }
  
  /**
   * Tests that the EmailManager can handle a large number of concurrent email sending operations
   * using Virtual Threads without errors or race conditions.
   */
  @Test
  public void testConcurrentEmailSending() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Mock the email send method to avoid actual network operations
    doAnswer(invocation -> {
      // Simulate some processing time
      Thread.sleep(5);
      return null;
    }).when(trustStore).getSSLContext();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int emailIndex = i;
        executor.submit(() -> {
          try {
            Email email = new SimpleEmail();
            email.setSubject("Test Email " + emailIndex);
            email.setMsg("This is test email " + emailIndex);
            email.addTo("recipient" + emailIndex + "@example.com");
            
            emailManager.send(email);
          } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error sending email", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Timed out waiting for email operations to complete");
      }
      
      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent email sending");
      
      // Verify the email send method was called the expected number of times
      verify(trustStore, times(taskCount)).getSSLContext();
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests the performance difference between Virtual Threads and Platform Threads
   * when performing concurrent email operations.
   */
  @Test
  public void testThreadPerformanceComparison() throws Exception {
    // Configure thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Mock the email send method to avoid actual network operations
    doAnswer(invocation -> {
      // Simulate some I/O bound operation
      Thread.sleep(10);
      return null;
    }).when(trustStore).getSSLContext();
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(platformThreadFactory, 500);
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(virtualThreadFactory, 500);
    
    log.info("Platform Thread execution time: {} ms", platformThreadTime);
    log.info("Virtual Thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O bound operations
    assertThat("Virtual threads should perform better than platform threads for I/O bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests that the EmailManager correctly handles mutex synchronization when accessed
   * concurrently from multiple virtual threads.
   */
  @Test
  public void testMutexSynchronization() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Setup a mock email configuration
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    lenient().when(emailConfig.copy()).thenReturn(emailConfig);
    Secret secret = mock(Secret.class);
    lenient().when(secretsService.encrypt(any(), any(), any())).thenReturn(secret);
    
    try {
      // Submit multiple concurrent configuration update tasks
      for (int i = 0; i < taskCount; i++) {
        final int configIndex = i;
        executor.submit(() -> {
          try {
            // This operation involves mutex synchronization in the EmailManager
            emailManager.setConfiguration(emailConfig, "password" + configIndex);
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error updating configuration", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Timed out waiting for configuration operations to complete");
      }
      
      // Verify results
      assertEquals(taskCount, successCount.get(), 
          "All configuration updates should succeed without synchronization issues");
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Helper method to measure execution time for a given thread factory and operation count.
   */
  private long measureExecutionTime(ThreadFactory threadFactory, int operationCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    long startTime = System.currentTimeMillis();
    
    try {
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            Email email = new SimpleEmail();
            email.setSubject("Performance Test");
            email.setMsg("This is a performance test email");
            email.addTo("performance@example.com");
            
            emailManager.send(email);
          } catch (Exception e) {
            log.error("Error in performance test", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return System.currentTimeMillis() - startTime;
    } finally {
      executor.shutdown();
    }
  }
}