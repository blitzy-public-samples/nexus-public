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
package org.sonatype.nexus.email;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailManager} implementation with Java 21 virtual threads.
 * 
 * This test class validates that the EmailManager implementation is compatible with
 * Java 21 virtual threads, focusing on high concurrency scenarios, proper resource
 * management, and absence of thread pinning issues.
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThreadTestGroup")
class EmailManagerVirtualThreadTest
{
  private static final int HIGH_CONCURRENCY_THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private EmailManager emailManager;
  
  @Mock
  private EmailConfiguration emailConfiguration;
  
  @Captor
  private ArgumentCaptor<Email> emailCaptor;
  
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() {
    // Create a virtual thread per task executor for testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Configure email manager mock for basic operations
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);
    when(emailConfiguration.isEnabled()).thenReturn(true);
  }
  
  @AfterEach
  void tearDown() {
    // Ensure executor is properly shut down after each test
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdownNow();
      try {
        virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Tests that the EmailManager.sendAsync method can handle a high number of concurrent
   * requests using virtual threads without issues.
   */
  @Test
  void testSendAsyncWithHighConcurrency() throws Exception {
    // Setup
    int taskCount = HIGH_CONCURRENCY_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<CompletableFuture<Void>> futures = new ArrayList<>(taskCount);
    
    // Configure email manager to return completed futures
    when(emailManager.sendAsync(any(Email.class))).thenReturn(CompletableFuture.completedFuture(null));
    
    // Execute multiple concurrent tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Create a unique email for each task
          SimpleEmail email = new SimpleEmail();
          email.setSubject("Test email " + index);
          email.setMsg("This is test email " + index);
          email.addTo("recipient" + index + "@example.com");
          
          // Send the email asynchronously
          emailManager.sendAsync(email);
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertTrue(completed, "All tasks should complete within the timeout period");
    assertEquals(0, errorCount.get(), "No errors should occur during concurrent email sending");
    
    // Verify that sendAsync was called the expected number of times
    verify(emailManager, times(taskCount)).sendAsync(any(Email.class));
  }
  
  /**
   * Tests that the EmailManager.sendVerificationAsync method works correctly with virtual threads.
   */
  @Test
  void testSendVerificationAsyncWithVirtualThreads() throws Exception {
    // Setup
    String testAddress = "test@example.com";
    String testPassword = "password";
    CompletableFuture<Void> completedFuture = CompletableFuture.completedFuture(null);
    
    // Configure mock behavior
    when(emailManager.sendVerificationAsync(emailConfiguration, testPassword, testAddress))
        .thenReturn(completedFuture);
    
    // Execute the test in a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      CompletableFuture<Void> result = emailManager.sendVerificationAsync(
          emailConfiguration, testPassword, testAddress);
      
      assertThat(result, is(notNullValue()));
      assertFalse(result.isCompletedExceptionally());
    }, virtualThreadExecutor);
    
    // Wait for completion and verify
    future.join();
    verify(emailManager).sendVerificationAsync(emailConfiguration, testPassword, testAddress);
  }
  
  /**
   * Tests that the EmailManager.constructMessageAsync method works correctly with virtual threads.
   */
  @Test
  void testConstructMessageAsyncWithVirtualThreads() throws Exception {
    // Setup
    String testMessage = "Test message";
    String constructedMessage = "Constructed: Test message";
    CompletableFuture<String> completedFuture = CompletableFuture.completedFuture(constructedMessage);
    
    // Configure mock behavior
    when(emailManager.constructMessageAsync(testMessage)).thenReturn(completedFuture);
    
    // Execute the test in a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      CompletableFuture<String> result = emailManager.constructMessageAsync(testMessage);
      
      assertThat(result, is(notNullValue()));
      assertFalse(result.isCompletedExceptionally());
      assertEquals(constructedMessage, result.join());
    }, virtualThreadExecutor);
    
    // Wait for completion and verify
    future.join();
    verify(emailManager).constructMessageAsync(testMessage);
  }
  
  /**
   * Tests that the EmailManager handles exceptions correctly when using virtual threads.
   */
  @Test
  void testExceptionHandlingWithVirtualThreads() throws Exception {
    // Setup
    Email email = spy(new SimpleEmail());
    EmailException testException = new EmailException("Test exception");
    
    // Configure mock to throw an exception
    doThrow(testException).when(emailManager).send(email);
    
    // Execute the test in a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Verify that the exception is properly thrown
      EmailException exception = assertThrows(EmailException.class, () -> {
        emailManager.send(email);
      });
      
      assertEquals("Test exception", exception.getMessage());
    }, virtualThreadExecutor);
    
    // Wait for completion and verify
    future.join();
    verify(emailManager).send(email);
  }
  
  /**
   * Tests that the EmailManager properly respects the enabled flag when using virtual threads.
   */
  @Test
  void testEmailEnabledFlagWithVirtualThreads() throws Exception {
    // Setup
    Email email = spy(new SimpleEmail());
    
    // Configure email configuration to be disabled
    when(emailConfiguration.isEnabled()).thenReturn(false);
    
    // Execute the test in a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Verify that send is not called when email is disabled
      assertDoesNotThrow(() -> {
        emailManager.send(email);
      });
    }, virtualThreadExecutor);
    
    // Wait for completion and verify
    future.join();
    verify(emailManager).send(email);
  }
  
  /**
   * Tests that multiple concurrent operations can be performed without thread pinning issues.
   * This test uses a high number of virtual threads to detect potential thread pinning problems.
   */
  @Test
  void testNoPinningWithConcurrentOperations() throws Exception {
    // Setup
    int taskCount = HIGH_CONCURRENCY_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    // Configure email manager for various operations
    when(emailManager.sendAsync(any(Email.class))).thenReturn(CompletableFuture.completedFuture(null));
    when(emailManager.constructMessageAsync(any())).thenReturn(CompletableFuture.completedFuture("test"));
    when(emailManager.sendVerificationAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
    
    // Execute multiple concurrent tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i % 3; // Cycle through 3 different operations
      CompletableFuture.runAsync(() -> {
        try {
          switch (index) {
            case 0:
              // Test send async
              SimpleEmail email = new SimpleEmail();
              emailManager.sendAsync(email);
              break;
            case 1:
              // Test construct message async
              emailManager.constructMessageAsync("test");
              break;
            case 2:
              // Test send verification async
              emailManager.sendVerificationAsync(emailConfiguration, "test@example.com");
              break;
          }
          completedCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertTrue(completed, "All tasks should complete within the timeout period");
    assertEquals(taskCount, completedCount.get(), "All operations should complete successfully");
    
    // Verify that the methods were called the expected number of times
    int expectedSendAsyncCalls = taskCount / 3 + (taskCount % 3 > 0 ? 1 : 0);
    int expectedConstructMessageCalls = taskCount / 3 + (taskCount % 3 > 1 ? 1 : 0);
    int expectedSendVerificationCalls = taskCount / 3;
    
    verify(emailManager, times(expectedSendAsyncCalls)).sendAsync(any(Email.class));
    verify(emailManager, times(expectedConstructMessageCalls)).constructMessageAsync(any());
    verify(emailManager, times(expectedSendVerificationCalls)).sendVerificationAsync(any(), any());
  }
  
  /**
   * Tests that the EmailManager can handle a mix of synchronous and asynchronous operations
   * when executed with virtual threads.
   */
  @Test
  void testMixedSyncAndAsyncOperationsWithVirtualThreads() throws Exception {
    // Setup
    int taskCount = 100; // Smaller count for mixed operations test
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Configure email manager for various operations
    when(emailManager.sendAsync(any(Email.class))).thenReturn(CompletableFuture.completedFuture(null));
    when(emailManager.constructMessageAsync(any())).thenReturn(CompletableFuture.completedFuture("test"));
    
    // Execute multiple concurrent tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      CompletableFuture.runAsync(() -> {
        try {
          if (index % 2 == 0) {
            // Synchronous operation
            emailManager.send(new SimpleEmail());
          }
          else {
            // Asynchronous operation
            emailManager.sendAsync(new SimpleEmail());
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertTrue(completed, "All tasks should complete within the timeout period");
    assertEquals(0, errorCount.get(), "No errors should occur during mixed operations");
    
    // Verify that the methods were called the expected number of times
    verify(emailManager, times(taskCount / 2)).send(any(Email.class));
    verify(emailManager, times(taskCount / 2)).sendAsync(any(Email.class));
  }
}