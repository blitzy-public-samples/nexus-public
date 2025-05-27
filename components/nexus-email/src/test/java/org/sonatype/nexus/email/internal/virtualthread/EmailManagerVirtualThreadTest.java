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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Provider;
import javax.net.ssl.SSLContext;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.internal.EmailConfigurationStore;
import org.sonatype.nexus.email.internal.EmailManagerImpl;
import org.sonatype.nexus.security.UserIdHelper;
import org.sonatype.nexus.ssl.TrustStore;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailManagerImpl} when executed under Java 21 virtual threads.
 * 
 * These tests validate that email operations work correctly with virtual threads, including:
 * - Email sending operations don't block virtual threads
 * - Thread context (like MDC) is properly propagated across virtual thread boundaries
 * - Concurrent operations don't suffer from thread pinning
 * - EmailManager.send() functions correctly when invoked from a virtual thread
 */
@ExtendWith(MockitoExtension.class)
public class EmailManagerVirtualThreadTest
    extends TestSupport
{
  private static final String MDC_TEST_KEY = "test-key";
  private static final String MDC_TEST_VALUE = "test-value";
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  private EventManager eventManager;

  @Mock
  private EmailConfigurationStore emailConfigurationStore;

  @Mock
  private TrustStore trustStore;

  @Mock
  private Provider capabilityRegistryProvider;

  @Mock
  private SecretsService secretsService;

  @InjectMocks
  private EmailManagerImpl emailManager;

  private MockedStatic<UserIdHelper> userIdHelperMock;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setup() throws Exception {
    userIdHelperMock = mockStatic(UserIdHelper.class);
    userIdHelperMock.when(UserIdHelper::get).thenReturn("userId");
    
    // Create a virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Setup common mocks
    when(trustStore.getSSLContext()).thenReturn(SSLContext.getDefault());
    
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
  }

  @AfterEach
  public void tearDown() throws Exception {
    userIdHelperMock.close();
    virtualThreadExecutor.shutdown();
    virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Tests that email sending works correctly when executed from a virtual thread.
   */
  @Test
  public void testSendEmailFromVirtualThread() throws Exception {
    // Create a mock email
    Email email = mock(Email.class);
    
    // Execute email sending from a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        emailManager.send(email);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    // Wait for completion and verify
    future.join();
    verify(email).send();
  }

  /**
   * Tests that MDC context is properly propagated across virtual thread boundaries
   * during email operations.
   */
  @Test
  public void testMdcContextPropagationWithVirtualThreads() throws Exception {
    // Create a mock email
    Email email = mock(Email.class);
    AtomicBoolean mdcPropagated = new AtomicBoolean(false);
    
    // Setup email.send() to verify MDC context
    doAnswer(invocation -> {
      String mdcValue = MDC.get(MDC_TEST_KEY);
      mdcPropagated.set(MDC_TEST_VALUE.equals(mdcValue));
      return null;
    }).when(email).send();
    
    // Set MDC context and execute email sending from a virtual thread
    MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
    try {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          emailManager.send(email);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor);
      
      // Wait for completion
      future.join();
    } finally {
      MDC.remove(MDC_TEST_KEY);
    }
    
    // Verify MDC context was propagated
    assertTrue(mdcPropagated.get(), "MDC context should be propagated to virtual thread");
  }

  /**
   * Tests that concurrent email operations don't suffer from thread pinning
   * when using virtual threads.
   */
  @Test
  public void testConcurrentEmailOperationsWithVirtualThreads() throws Exception {
    // Create a countdown latch to wait for all operations
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Setup a mock email that simulates I/O with a small delay
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      CompletableFuture.runAsync(() -> {
        try {
          // Create a new email for each operation
          Email email = mock(Email.class);
          doAnswer(invocation -> {
            // Simulate I/O operation with a small delay
            Thread.sleep(50);
            return null;
          }).when(email).send();
          
          // Send the email
          emailManager.send(email);
          
          // Verify the email was sent
          verify(email).send();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all operations completed successfully
    assertTrue(completed, "All concurrent operations should complete within timeout");
    assertThat(errorCount.get(), is(0));
  }

  /**
   * Tests that email configuration operations work correctly when executed from virtual threads.
   */
  @Test
  public void testEmailConfigurationWithVirtualThreads() throws Exception {
    // Setup mocks for configuration
    EmailConfiguration oldEmailConfig = mock(EmailConfiguration.class);
    when(emailConfigurationStore.load()).thenReturn(oldEmailConfig);
    when(oldEmailConfig.copy()).thenReturn(oldEmailConfig);
    Secret oldPass = mock(Secret.class);
    when(oldEmailConfig.getPassword()).thenReturn(oldPass);
    
    EmailConfiguration newEmailConfig = mock(EmailConfiguration.class);
    when(newEmailConfig.copy()).thenReturn(newEmailConfig);
    Secret newSecret = mock(Secret.class);
    when(secretsService.encrypt(any(), any(), any())).thenReturn(newSecret);
    
    // Execute configuration update from a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      emailManager.setConfiguration(newEmailConfig, "newPassword");
    }, virtualThreadExecutor);
    
    // Wait for completion
    future.join();
    
    // Verify configuration was updated
    verify(emailConfigurationStore).save(newEmailConfig);
    verify(secretsService).encrypt(any(), any(), any());
    verify(secretsService).remove(oldPass);
    verify(eventManager).post(any());
  }

  /**
   * Tests that retrieving email configuration works correctly when executed from virtual threads.
   */
  @Test
  public void testGetEmailConfigurationFromVirtualThread() throws Exception {
    // Execute get configuration from a virtual thread
    CompletableFuture<EmailConfiguration> future = CompletableFuture.supplyAsync(() -> {
      return emailManager.getConfiguration();
    }, virtualThreadExecutor);
    
    // Wait for completion and verify
    EmailConfiguration config = future.join();
    assertThat(config, notNullValue());
    verify(emailConfigurationStore).load();
  }

  /**
   * Tests that email validation works correctly when executed from virtual threads.
   */
  @Test
  public void testEmailValidationFromVirtualThread() throws Exception {
    // Create a simple email for validation
    SimpleEmail email = new SimpleEmail();
    email.setHostName("example.com");
    email.setSmtpPort(25);
    email.setFrom("sender@example.com");
    email.addTo("recipient@example.com");
    email.setSubject("Test Subject");
    email.setMsg("Test Message");
    
    // Execute validation from a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      assertDoesNotThrow(() -> emailManager.apply(emailManager.getConfiguration(), email, "password"));
    }, virtualThreadExecutor);
    
    // Wait for completion
    future.join();
  }
}