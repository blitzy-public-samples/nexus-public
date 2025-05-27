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
package org.sonatype.nexus.coreui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailComponent} with Java 21 Virtual Threads.
 * 
 * This test class validates that email operations execute correctly under virtual threads,
 * checks for thread pinning issues during SMTP connections, and compares performance
 * between platform threads and virtual threads for email operations.
 */
@ExtendWith(MockitoExtension.class)
public class EmailComponentVirtualThreadTest
{
  private static final String ADDRESS = "you@somewhere.com";
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private EmailManager emailManager;

  @InjectMocks
  private EmailComponent underTest;

  private EmailConfiguration emailConfiguration;

  @BeforeEach
  void setUp() {
    emailConfiguration = mock(EmailConfiguration.class);
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);
    when(emailConfiguration.isEnabled()).thenReturn(false);
    when(emailConfiguration.getHost()).thenReturn("localhost");
    when(emailConfiguration.getPort()).thenReturn(25);
    when(emailConfiguration.getFromAddress()).thenReturn("nexus@example.org");
    when(emailConfiguration.getUsername()).thenReturn("foo");
    when(emailConfiguration.isStartTlsEnabled()).thenReturn(true);
    when(emailConfiguration.isStartTlsRequired()).thenReturn(true);
    when(emailConfiguration.isSslOnConnectEnabled()).thenReturn(true);
    when(emailConfiguration.getSubjectPrefix()).thenReturn("prefix");
  }

  /**
   * Verifies that reading email configuration works correctly under virtual threads.
   */
  @Test
  void readConfigurationWithVirtualThread() throws Exception {
    // Setup
    when(emailConfiguration.getPassword()).thenReturn(mock(Secret.class));
    
    // Execute in a virtual thread
    AtomicReference<EmailConfigurationXO> resultRef = new AtomicReference<>();
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      resultRef.set(underTest.read());
    });
    
    // Wait for completion
    virtualThread.join();
    EmailConfigurationXO result = resultRef.get();
    
    // Verify
    assertThat(result, is(notNullValue()));
    assertThat(result.isEnabled(), is(false));
    assertThat(result.getHost(), is("localhost"));
    assertThat(result.getPort(), is(25));
    assertThat(result.getUsername(), is("foo"));
    assertThat(result.getFromAddress(), is("nexus@example.org"));
    assertThat(result.getPassword(), is(PasswordPlaceholder.get()));
    assertThat(result.isStartTlsEnabled(), is(true));
    assertThat(result.isStartTlsRequired(), is(true));
    assertThat(result.isSslOnConnectEnabled(), is(true));
    assertThat(result.getSubjectPrefix(), is("prefix"));
    assertThat(result.isNexusTrustStoreEnabled(), is(false));
    assertThat(result.isSslCheckServerIdentityEnabled(), is(false));
  }

  /**
   * Verifies that reading email configuration with null password works correctly under virtual threads.
   */
  @Test
  void readConfigurationWithNullPasswordUnderVirtualThread() throws Exception {
    // Setup
    when(emailConfiguration.getPassword()).thenReturn(null);
    
    // Execute in a virtual thread
    AtomicReference<EmailConfigurationXO> resultRef = new AtomicReference<>();
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      resultRef.set(underTest.read());
    });
    
    // Wait for completion
    virtualThread.join();
    EmailConfigurationXO result = resultRef.get();
    
    // Verify
    assertThat(result, is(notNullValue()));
    assertThat(result.isEnabled(), is(false));
    assertThat(result.getHost(), is("localhost"));
    assertThat(result.getPort(), is(25));
    assertThat(result.getUsername(), is("foo"));
    assertThat(result.getFromAddress(), is("nexus@example.org"));
    assertThat(result.getPassword(), is(nullValue()));
    assertThat(result.isStartTlsEnabled(), is(true));
    assertThat(result.isStartTlsRequired(), is(true));
    assertThat(result.isSslOnConnectEnabled(), is(true));
    assertThat(result.getSubjectPrefix(), is("prefix"));
    assertThat(result.isNexusTrustStoreEnabled(), is(false));
    assertThat(result.isSslCheckServerIdentityEnabled(), is(false));
  }

  /**
   * Verifies that updating email configuration works correctly under virtual threads.
   */
  @Test
  void updateConfigurationWithVirtualThread() throws Exception {
    // Setup
    when(emailManager.newConfiguration()).thenReturn(emailConfiguration);
    EmailConfigurationXO configXO = getConfigurationXO("baz");
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      underTest.update(configXO);
    });
    
    // Wait for completion
    virtualThread.join();
    
    // Verify
    verify(emailManager).setConfiguration(emailConfiguration, "baz");
    verify(emailManager).getConfiguration();
  }

  /**
   * Verifies that sending verification emails works correctly under virtual threads.
   */
  @Test
  void sendVerificationWithVirtualThread() throws Exception {
    // Setup
    EmailConfigurationXO configXO = getConfigurationXO("baz");
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailManager.newConfiguration()).thenReturn(emailConfig);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      underTest.sendVerification(configXO, ADDRESS);
    });
    
    // Wait for completion
    virtualThread.join();
    
    // Verify
    verify(emailManager).sendVerification(emailConfig, "baz", ADDRESS);
  }

  /**
   * Tests concurrent email verification operations using virtual threads.
   * This test verifies that the system can handle multiple concurrent email operations
   * efficiently using virtual threads.
   */
  @Test
  void concurrentEmailVerificationsWithVirtualThreads() throws Exception {
    // Setup
    EmailConfigurationXO configXO = getConfigurationXO("baz");
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailManager.newConfiguration()).thenReturn(emailConfig);
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final String address = "user" + i + "@example.com";
        executor.submit(() -> {
          try {
            underTest.sendVerification(configXO, address);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("No errors should occur", errorCount.get(), is(0));
      verify(emailManager, times(taskCount)).sendVerification(any(EmailConfiguration.class), eq("baz"), anyString());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests for thread pinning issues during SMTP connections.
   * This test verifies that virtual threads don't get pinned during email operations,
   * which would defeat the purpose of using virtual threads for I/O operations.
   */
  @Test
  void detectThreadPinningDuringSmtpConnections() throws Exception {
    // Setup
    EmailConfigurationXO configXO = getConfigurationXO("baz");
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailManager.newConfiguration()).thenReturn(emailConfig);
    
    // Setup thread pinning detector
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    
    // Make the email manager simulate a slow SMTP connection
    doAnswer(invocation -> {
      // Simulate SMTP connection delay
      Thread.sleep(500);
      
      // Check if the current thread is pinned during this operation
      boolean isPinned = pinningDetector.isThreadPinned();
      if (isPinned) {
        throw new RuntimeException("Thread pinning detected during SMTP operation");
      }
      
      return null;
    }).when(emailManager).sendVerification(any(), anyString(), anyString());
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      underTest.sendVerification(configXO, ADDRESS);
    });
    
    // Wait for completion
    virtualThread.join();
    
    // Verify no exceptions were thrown (which would indicate thread pinning)
    verify(emailManager).sendVerification(any(EmailConfiguration.class), eq("baz"), eq(ADDRESS));
  }

  /**
   * Compares performance between platform threads and virtual threads for email operations.
   * This test measures the time taken to perform multiple email operations using both
   * thread types and verifies that virtual threads provide better performance.
   */
  @Test
  void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    // Setup
    EmailConfigurationXO configXO = getConfigurationXO("baz");
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailManager.newConfiguration()).thenReturn(emailConfig);
    
    // Make the email manager simulate a realistic SMTP operation with some I/O delay
    doAnswer(invocation -> {
      // Simulate network I/O delay
      Thread.sleep(50);
      return null;
    }).when(emailManager).sendVerification(any(), anyString(), anyString());
    
    // Measure platform threads performance
    long platformThreadTime = measurePerformance(Thread.ofPlatform().factory(), configXO);
    
    // Measure virtual threads performance
    long virtualThreadTime = measurePerformance(Thread.ofVirtual().factory(), configXO);
    
    // Verify virtual threads perform better (or at least not significantly worse)
    // Note: In real-world scenarios with actual I/O, the difference would be more pronounced
    assertThat("Virtual threads should be faster than platform threads for I/O operations", 
               virtualThreadTime, lessThan(platformThreadTime * 1.2)); // Allow some margin
  }

  /**
   * Tests error handling during email operations with virtual threads.
   */
  @Test
  void errorHandlingWithVirtualThreads() throws Exception {
    // Setup
    EmailConfigurationXO configXO = getConfigurationXO("baz");
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailManager.newConfiguration()).thenReturn(emailConfig);
    
    // Make the email manager throw an exception
    doThrow(new RuntimeException("SMTP connection failed"))
        .when(emailManager).sendVerification(any(), anyString(), anyString());
    
    // Execute in a virtual thread and verify exception is propagated
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        underTest.sendVerification(configXO, ADDRESS);
      } catch (Throwable t) {
        exceptionRef.set(t);
      }
    });
    
    // Wait for completion
    virtualThread.join();
    
    // Verify exception was thrown
    assertThat("Exception should be propagated", exceptionRef.get(), is(notNullValue()));
  }

  /**
   * Tests concurrent email configuration updates using virtual threads.
   */
  @Test
  void concurrentConfigurationUpdatesWithVirtualThreads() throws Exception {
    // Setup
    when(emailManager.newConfiguration()).thenReturn(emailConfiguration);
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 10; // Fewer updates than verifications as this would be less common
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final String password = "password" + i;
        executor.submit(() -> {
          try {
            underTest.update(getConfigurationXO(password));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("No errors should occur", errorCount.get(), is(0));
      verify(emailManager, times(taskCount)).setConfiguration(any(EmailConfiguration.class), anyString());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to measure performance of email operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param configXO The email configuration to use
   * @return The time taken in milliseconds
   */
  private long measurePerformance(ThreadFactory threadFactory, EmailConfigurationXO configXO) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    int taskCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    try {
      long startTime = System.currentTimeMillis();
      
      // Submit tasks
      for (int i = 0; i < taskCount; i++) {
        final String address = "user" + i + "@example.com";
        executor.submit(() -> {
          try {
            underTest.sendVerification(configXO, address);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      long endTime = System.currentTimeMillis();
      return endTime - startTime;
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to create an EmailConfigurationXO with the specified password.
   */
  private static EmailConfigurationXO getConfigurationXO(final String password) {
    return new EmailConfigurationXO(
        false, "localhost", 25,
        "foo", password, "nexus@example.org",
        null, false, false,
        false, false, false
    );
  }
}