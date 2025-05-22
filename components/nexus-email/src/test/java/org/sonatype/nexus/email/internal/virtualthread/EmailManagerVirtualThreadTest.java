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
package org.sonatype.nexus.email.internal.virtualthread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import javax.inject.Provider;
import javax.net.ssl.SSLContext;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.slf4j.MDC;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.internal.email.EmailConfigurationStore;
import org.sonatype.nexus.internal.email.EmailManagerImpl;
import org.sonatype.nexus.security.UserIdHelper;
import org.sonatype.nexus.ssl.TrustStore;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link EmailManagerImpl} with Java 21 Virtual Threads to validate its behavior
 * under high concurrency scenarios and ensure proper thread context propagation.
 */
public class EmailManagerVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int CONCURRENT_EMAILS = 100;
  private static final int TIMEOUT_SECONDS = 30;
  private static final String MDC_TEST_KEY = "testKey";
  private static final String MDC_TEST_VALUE = "testValue";

  @Mock
  private EventManager eventManager;

  @Mock
  private EmailConfigurationStore emailConfigurationStore;

  @Mock
  private TrustStore trustStore;

  @Mock
  private Function<EmailConfiguration, EmailConfiguration> defaults;

  @Mock
  private Provider capabilityRegistryProvider;

  @Mock
  private SecretsService secretsService;

  private EmailManagerImpl emailManager;

  private MockedStatic<UserIdHelper> userIdHelperMock;

  @Before
  public void setup() throws Exception {
    userIdHelperMock = mockStatic(UserIdHelper.class);
    userIdHelperMock.when(UserIdHelper::get).thenReturn("userId");

    // Create the email manager with mocked dependencies
    emailManager = new EmailManagerImpl(eventManager, emailConfigurationStore, trustStore, defaults, secretsService);
    
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
    when(trustStore.getSSLContext()).thenReturn(SSLContext.getDefault());
  }

  @After
  public void tearDown() {
    userIdHelperMock.close();
  }

  /**
   * Tests that the EmailManager can send emails from a virtual thread without any issues.
   */
  @Test
  public void testSendEmailFromVirtualThread() throws Exception {
    // Create a simple email
    Email email = mock(Email.class);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit task to send email from a virtual thread
      executor.submit(() -> {
        try {
          // Send the email
          emailManager.send(email);
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Verify that the email was sent
    verify(email).send();
  }

  /**
   * Tests that the EmailManager can handle concurrent email operations using virtual threads
   * without any thread pinning issues.
   */
  @Test
  public void testNoPinningDuringEmailOperations() throws Exception {
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_EMAILS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Enable thread pinning detection
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    pinningDetector.enable();
    
    try {
      // Create a virtual thread executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
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
            } 
            catch (Exception e) {
              // Log exception but don't fail the test
              log.error("Error sending email", e);
            } 
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all threads to complete
        assertThat("Not all email operations completed within the timeout",
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      }
      
      // Verify that all emails were processed successfully
      assertThat("Not all emails were sent successfully", 
          successCount.get(), is(CONCURRENT_EMAILS));
      
      // Verify that no thread pinning was detected
      assertThat("Thread pinning detected during email operations",
          pinningDetector.getPinningEvents().isEmpty(), is(true));
    } 
    finally {
      pinningDetector.disable();
    }
  }

  /**
   * Tests that MDC context is properly propagated across virtual thread boundaries
   * when sending emails.
   */
  @Test
  public void testMdcContextPropagationAcrossVirtualThreads() throws Exception {
    // Create a simple email
    Email email = mock(Email.class);
    
    // Set up MDC context verification
    doAnswer(invocation -> {
      // Verify that MDC context is available in the email send operation
      String mdcValue = MDC.get(MDC_TEST_KEY);
      assertThat("MDC context not propagated to email send operation", 
          mdcValue, is(MDC_TEST_VALUE));
      return null;
    }).when(email).send();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Set MDC context in the parent thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      try {
        // Submit task to send email from a virtual thread
        executor.submit(() -> {
          try {
            // Send the email - this should have the MDC context
            emailManager.send(email);
          } 
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } 
      finally {
        // Clean up MDC context
        MDC.remove(MDC_TEST_KEY);
      }
    }
    
    // Verify that the email was sent
    verify(email).send();
  }

  /**
   * Tests that the EmailManager can handle concurrent configuration access
   * from multiple virtual threads without any issues.
   */
  @Test
  public void testConcurrentConfigurationAccess() throws Exception {
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_EMAILS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Mock the email configuration
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailConfig.isEnabled()).thenReturn(true);
    when(emailConfig.copy()).thenReturn(emailConfig);
    Secret password = mock(Secret.class);
    when(emailConfig.getPassword()).thenReturn(password);
    when(emailConfigurationStore.load()).thenReturn(emailConfig);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to access configuration concurrently
      for (int i = 0; i < CONCURRENT_EMAILS; i++) {
        executor.submit(() -> {
          try {
            // Get the email configuration
            EmailConfiguration config = emailManager.getConfiguration();
            assertThat("Email configuration should not be null", config, is(notNullValue()));
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error accessing email configuration", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertThat("Not all configuration access operations completed within the timeout",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    // Verify that all configuration access operations were successful
    assertThat("Not all configuration access operations were successful", 
        successCount.get(), is(CONCURRENT_EMAILS));
    
    // Verify that the configuration was loaded for each operation
    verify(emailConfigurationStore, times(CONCURRENT_EMAILS)).load();
  }

  /**
   * Tests that the EmailManager can handle configuration updates from virtual threads
   * without any issues.
   */
  @Test
  public void testConfigurationUpdateFromVirtualThread() throws Exception {
    // Mock the email configuration
    EmailConfiguration oldEmailConfig = mock(EmailConfiguration.class);
    when(emailConfigurationStore.load()).thenReturn(oldEmailConfig);
    when(oldEmailConfig.copy()).thenReturn(oldEmailConfig);
    Secret oldPass = mock(Secret.class);
    when(oldEmailConfig.getPassword()).thenReturn(oldPass);
    
    EmailConfiguration newEmailConfig = mock(EmailConfiguration.class);
    when(newEmailConfig.copy()).thenReturn(newEmailConfig);
    Secret newPass = mock(Secret.class);
    when(secretsService.encrypt(any(), any(), any())).thenReturn(newPass);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit task to update configuration from a virtual thread
      executor.submit(() -> {
        // Update the email configuration
        emailManager.setConfiguration(newEmailConfig, "newPassword");
      }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Verify that the configuration was updated
    verify(emailConfigurationStore).save(newEmailConfig);
    verify(secretsService).encrypt(any(), any(), any());
    verify(secretsService).remove(oldPass);
    verify(eventManager).post(any());
  }
}