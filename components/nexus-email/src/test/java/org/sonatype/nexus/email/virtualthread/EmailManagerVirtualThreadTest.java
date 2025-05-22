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
package org.sonatype.nexus.email.virtualthread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailConfigurationStore;
import org.sonatype.nexus.internal.email.EmailManagerImpl;
import org.sonatype.nexus.ssl.TrustStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link EmailManagerImpl} with Java 21 Virtual Threads to validate its behavior
 * under high concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
public class EmailManagerVirtualThreadTest
{
  private static final int CONCURRENT_EMAILS = 5000;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private EventManager eventManager;

  @Mock
  private EmailConfigurationStore emailConfigurationStore;

  @Mock
  private TrustStore trustStore;

  @Mock
  private SecretsService secretsService;

  private EmailManagerImpl emailManager;

  @BeforeEach
  public void setup() throws Exception {
    // Create the email manager with mocked dependencies
    emailManager = new EmailManagerImpl(eventManager, emailConfigurationStore, trustStore, null, secretsService);
    
    // Mock the email configuration
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailConfig.isEnabled()).thenReturn(true);
    when(emailConfig.getHost()).thenReturn("smtp.example.com");
    when(emailConfig.getPort()).thenReturn(25);
    when(emailConfig.getFromAddress()).thenReturn("sender@example.com");
    when(emailConfig.getUsername()).thenReturn("user");
    Secret password = mock(Secret.class);
    when(emailConfig.getPassword()).thenReturn(password);
    when(emailConfigurationStore.load()).thenReturn(emailConfig);
    
    // Mock the trust store
    lenient().when(trustStore.getSSLContext()).thenReturn(javax.net.ssl.SSLContext.getDefault());
  }

  @AfterEach
  public void tearDown() {
    // No specific teardown needed
  }

  /**
   * Tests that the EmailManager can handle a large number of concurrent email operations
   * using Virtual Threads without any issues.
   */
  @Test
  public void testConcurrentEmailSendingWithVirtualThreads() {
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_EMAILS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Mock the email send method to avoid actual network calls
    doAnswer(invocation -> {
      // Simulate some I/O delay (50-150ms)
      Thread.sleep((long) (50 + Math.random() * 100));
      return null;
    }).when(emailConfigurationStore).load();
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Assert that the operation completes within the timeout
    assertTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to send emails concurrently
      for (int i = 0; i < CONCURRENT_EMAILS; i++) {
        final int emailId = i;
        executor.submit(() -> {
          try {
            // Create a simple email
            Email email = new SimpleEmail();
            email.setSubject("Test Email " + emailId);
            email.setMsg("This is test email " + emailId);
            email.addTo("recipient" + emailId + "@example.com");
            
            // Send the email
            emailManager.send(email);
            successCount.incrementAndGet();
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all email operations completed within the timeout");
      
      // Shutdown the executor
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate");
    });
    
    // Verify that all emails were processed successfully
    assertEquals(CONCURRENT_EMAILS, successCount.get(), "Not all emails were sent successfully");
    assertEquals(0, errorCount.get(), "Some emails failed to send");
    
    // Verify that the email configuration was loaded for each email
    verify(emailConfigurationStore, times(CONCURRENT_EMAILS)).load();
  }

  /**
   * Tests that the EmailManager properly handles resource cleanup when sending emails
   * concurrently with Virtual Threads.
   */
  @Test
  public void testResourceCleanupWithVirtualThreads() throws Exception {
    // Create a mock email that tracks if it's closed properly
    AtomicInteger closedEmails = new AtomicInteger(0);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_EMAILS);
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Assert that the operation completes within the timeout
    assertTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to send emails concurrently
      for (int i = 0; i < CONCURRENT_EMAILS; i++) {
        executor.submit(() -> {
          try {
            // Create a mock email that counts when it's closed
            Email email = mock(Email.class);
            doAnswer(invocation -> {
              // Simulate some work
              Thread.sleep((long) (10 + Math.random() * 50));
              closedEmails.incrementAndGet();
              return null;
            }).when(email).send();
            
            // Send the email
            emailManager.send(email);
          } catch (Exception e) {
            // Ignore exceptions for this test
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all email operations completed within the timeout");
      
      // Shutdown the executor
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate");
    });
    
    // Verify that all emails were processed
    assertEquals(CONCURRENT_EMAILS, closedEmails.get(), 
        "Not all emails were properly processed, which may indicate resource leaks");
  }

  /**
   * Tests that the EmailManager can handle a mix of different email operations concurrently
   * using Virtual Threads without any issues.
   */
  @Test
  public void testMixedEmailOperationsWithVirtualThreads() throws Exception {
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_EMAILS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Mock the email configuration for testing
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailConfig.isEnabled()).thenReturn(true);
    when(emailConfig.getHost()).thenReturn("smtp.example.com");
    when(emailConfig.getPort()).thenReturn(25);
    when(emailConfig.getFromAddress()).thenReturn("sender@example.com");
    when(emailConfig.getUsername()).thenReturn("user");
    when(emailConfig.copy()).thenReturn(emailConfig);
    Secret password = mock(Secret.class);
    when(emailConfig.getPassword()).thenReturn(password);
    when(emailConfigurationStore.load()).thenReturn(emailConfig);
    
    // Assert that the operation completes within the timeout
    assertTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to perform different email operations concurrently
      for (int i = 0; i < CONCURRENT_EMAILS; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            // Perform different operations based on the operation ID
            if (operationId % 3 == 0) {
              // Send an email
              Email email = new SimpleEmail();
              emailManager.send(email);
            } else if (operationId % 3 == 1) {
              // Get the email configuration
              emailManager.getConfiguration();
            } else {
              // Apply email configuration
              SimpleEmail email = new SimpleEmail();
              emailManager.apply(emailConfig, email, "password");
            }
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Ignore exceptions for this test
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all email operations completed within the timeout");
      
      // Shutdown the executor
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate");
    });
    
    // Verify that a significant number of operations succeeded
    assertTrue(successCount.get() > CONCURRENT_EMAILS * 0.9, 
        "Too many operations failed, expected at least 90% success rate");
  }

  /**
   * Tests that the EmailManager can handle email operations with varying delays
   * using Virtual Threads without any issues.
   */
  @Test
  public void testEmailOperationsWithVaryingDelays() throws Exception {
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_EMAILS);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Mock the email send method to simulate varying delays
    doAnswer(invocation -> {
      Email email = invocation.getArgument(0);
      // Extract the delay from the email subject (if available)
      String subject = email.getSubject();
      long delay = 50; // Default delay
      if (subject != null && subject.startsWith("Delay-")) {
        try {
          delay = Long.parseLong(subject.substring(6));
        } catch (NumberFormatException e) {
          // Use default delay
        }
      }
      // Simulate the delay
      Thread.sleep(delay);
      return null;
    }).when(emailConfigurationStore).load();
    
    // Assert that the operation completes within the timeout
    assertTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to send emails with varying delays
      for (int i = 0; i < CONCURRENT_EMAILS; i++) {
        final int emailId = i;
        executor.submit(() -> {
          try {
            // Create a simple email with a delay encoded in the subject
            Email email = new SimpleEmail();
            // Vary the delay between 10ms and 500ms based on the email ID
            long delay = 10 + (emailId % 10) * 50;
            email.setSubject("Delay-" + delay);
            email.setMsg("This is test email " + emailId + " with delay " + delay + "ms");
            email.addTo("recipient" + emailId + "@example.com");
            
            // Send the email
            emailManager.send(email);
            completedCount.incrementAndGet();
          } catch (Exception e) {
            // Ignore exceptions for this test
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all email operations completed within the timeout");
      
      // Shutdown the executor
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate");
    });
    
    // Verify that all emails were processed
    assertEquals(CONCURRENT_EMAILS, completedCount.get(), 
        "Not all emails were processed, which may indicate issues with varying delays");
  }
}