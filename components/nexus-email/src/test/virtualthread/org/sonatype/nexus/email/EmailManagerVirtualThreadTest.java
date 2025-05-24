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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.internal.email.EmailConfigurationStore;
import org.sonatype.nexus.internal.email.EmailManagerImpl;
import org.sonatype.nexus.ssl.TrustStore;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailManager} implementation compatibility with Java 21 virtual threads.
 * 
 * This test validates that the EmailManager can operate correctly under high concurrency
 * scenarios using Java 21 virtual threads, ensuring proper resource allocation and disposal.
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThreadTestGroup")
class EmailManagerVirtualThreadTest
    extends TestSupport
{
  @Mock
  private EmailConfigurationStore emailConfigurationStore;

  @Mock
  private TrustStore trustStore;

  @Mock
  private SecretsService secretsService;

  private EmailManagerImpl emailManager;

  private EmailConfiguration emailConfig;

  @BeforeEach
  void setUp() throws Exception {
    // Initialize the email manager
    emailManager = new EmailManagerImpl(emailConfigurationStore, trustStore, secretsService);
    
    // Set up a mock email configuration
    emailConfig = mock(EmailConfiguration.class);
    when(emailConfig.isEnabled()).thenReturn(true);
    when(emailConfig.getHost()).thenReturn("smtp.example.com");
    when(emailConfig.getPort()).thenReturn(587);
    when(emailConfig.getFromAddress()).thenReturn("sender@example.com");
    when(emailConfig.getUsername()).thenReturn("user");
    Secret password = mock(Secret.class);
    when(emailConfig.getPassword()).thenReturn(password);
    when(emailConfigurationStore.load()).thenReturn(emailConfig);
    
    // Set up SSL context for email operations
    when(trustStore.getSSLContext()).thenReturn(SSLContext.getDefault());
  }

  @AfterEach
  void tearDown() {
    // Clean up resources if needed
  }

  /**
   * Tests that the EmailManager can handle concurrent email sending operations
   * using virtual threads without issues.
   */
  @Test
  void concurrentEmailSendingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up test parameters
      int emailCount = 1000; // High number of concurrent operations
      CountDownLatch latch = new CountDownLatch(emailCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a mock for the Email class that will be used to verify interactions
      Email mockEmail = mock(Email.class);
      
      // Mock the email send operation to avoid actual network calls
      doAnswer(invocation -> {
        // Simulate some I/O work
        Thread.sleep(5);
        return null;
      }).when(mockEmail).send();
      
      // Submit multiple concurrent email sending tasks
      for (int i = 0; i < emailCount; i++) {
        final int emailIndex = i;
        executor.submit(() -> {
          try {
            // Use the mock email for this task
            // In a real scenario, we would create a new email instance for each task
            // but for testing purposes, we can reuse the mock
            
            // Send the email through the EmailManager
            emailManager.send(mockEmail);
            
            // Increment success counter
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Failed to send email", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all emails were processed
      assertThat("All email tasks should complete within the timeout", completed, is(true));
      assertThat("All emails should be sent successfully", successCount.get(), is(emailCount));
      
      // Verify the email was sent the expected number of times
      verify(mockEmail, times(emailCount)).send();
    }
    finally {
      // Ensure executor is shut down properly
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that the EmailManager correctly handles disabled email configuration
   * when operating with virtual threads.
   */
  @Test
  void disabledEmailConfigurationWithVirtualThreads() throws Exception {
    // Set up a disabled email configuration
    when(emailConfig.isEnabled()).thenReturn(false);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up test parameters
      int emailCount = 100;
      CountDownLatch latch = new CountDownLatch(emailCount);
      
      // Create a mock email for verification
      Email mockEmail = mock(Email.class);
      
      // Submit multiple concurrent email sending tasks
      for (int i = 0; i < emailCount; i++) {
        executor.submit(() -> {
          try {
            // Send the mock email through the EmailManager
            emailManager.send(mockEmail);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      
      // Verify all tasks completed and no emails were actually sent
      assertThat("All tasks should complete within the timeout", completed, is(true));
      verify(mockEmail, never()).send();
    }
    finally {
      // Ensure executor is shut down properly
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that the EmailManager can handle a very high number of concurrent operations
   * using virtual threads, which would be impractical with platform threads.
   */
  @Test
  void highConcurrencyEmailOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up test parameters with a very high thread count
      int emailCount = 10000; // This would be impractical with platform threads
      CountDownLatch latch = new CountDownLatch(emailCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Mock the email configuration retrieval to avoid contention
      EmailConfiguration cachedConfig = emailConfig;
      when(emailConfigurationStore.load()).thenReturn(cachedConfig);
      
      // Submit a large number of concurrent tasks
      for (int i = 0; i < emailCount; i++) {
        executor.submit(() -> {
          try {
            // Get the email configuration
            EmailConfiguration config = emailManager.getConfiguration();
            
            // Verify the configuration is correct
            if (config != null && config.isEnabled()) {
              successCount.incrementAndGet();
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with a longer timeout due to high volume)
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("All configuration retrievals should succeed", successCount.get(), is(emailCount));
      
      // Verify the configuration was retrieved multiple times
      verify(emailConfigurationStore, times(emailCount)).load();
    }
    finally {
      // Ensure executor is shut down properly
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that the EmailManager implementation does not cause thread pinning issues
   * when used with virtual threads. Thread pinning occurs when a virtual thread is
   * forced to stay on its carrier thread, which defeats the purpose of virtual threads.
   * 
   * This test runs operations that would typically cause thread pinning if the implementation
   * uses synchronized blocks or methods inappropriately, and verifies that they complete
   * in a reasonable time.
   */
  @Test
  void emailManagerShouldNotCauseThreadPinning() throws Exception {
    // Enable thread pinning detection for this test
    // In a real environment, this would be set via JVM flag: -Djdk.tracePinnedThreads=full
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    try {
      // Create a virtual thread factory
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      
      // Create an executor service that uses virtual threads
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      
      try {
        // Set up test parameters
        int operationCount = 5000;
        CountDownLatch latch = new CountDownLatch(operationCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        // Create a mock email for testing
        Email mockEmail = mock(Email.class);
        doAnswer(invocation -> {
          // Simulate some I/O work with a sleep
          // This would reveal thread pinning issues if present
          Thread.sleep(10);
          return null;
        }).when(mockEmail).send();
        
        // Record start time to measure overall execution time
        long startTime = System.currentTimeMillis();
        
        // Submit concurrent operations that mix different EmailManager methods
        for (int i = 0; i < operationCount; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              // Alternate between different operations to exercise various code paths
              switch (index % 3) {
                case 0:
                  // Get configuration
                  emailManager.getConfiguration();
                  break;
                case 1:
                  // Send email
                  emailManager.send(mockEmail);
                  break;
                case 2:
                  // Check if email is enabled
                  if (emailManager.getConfiguration().isEnabled()) {
                    // Do something with the configuration
                  }
                  break;
              }
              successCount.incrementAndGet();
            }
            catch (Exception e) {
              log.error("Operation failed", e);
            }
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all operations to complete
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        
        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;
        
        // Verify all operations completed successfully and in a reasonable time
        assertThat("All operations should complete within the timeout", completed, is(true));
        assertThat("All operations should succeed", successCount.get(), is(operationCount));
        
        // Log the execution time for analysis
        log.info("Completed {} operations in {} ms using virtual threads", operationCount, executionTime);
        
        // If thread pinning were occurring, the execution time would be much longer
        // than expected because virtual threads would be blocked on carrier threads.
        // A reasonable threshold depends on the hardware, but we can set a conservative value.
        // For 5000 operations with 10ms sleep each, if properly parallelized with virtual threads,
        // this should complete much faster than if they were executed sequentially (which would take 50 seconds).
        assertThat("Execution time indicates possible thread pinning issues", 
            executionTime, is(lessThan(15000L)));
      }
      finally {
        // Ensure executor is shut down properly
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
      }
    }
    finally {
      // Reset the thread pinning detection property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }
}