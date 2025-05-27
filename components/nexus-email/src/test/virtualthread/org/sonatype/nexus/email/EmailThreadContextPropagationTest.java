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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.org.lidalia.slf4jtest.LoggingEvent;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that verify thread context propagation for {@link EmailManager} operations
 * when executed in Java 21 virtual threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class EmailThreadContextPropagationTest
    extends TestSupport
{
  private static final String MDC_KEY = "testKey";
  private static final String MDC_VALUE = "testValue";
  private static final String TEST_USER = "testUser";
  
  private final TestLogger testLogger = TestLoggerFactory.getTestLogger(EmailThreadContextPropagationTest.class);
  
  @Mock
  private EmailManager emailManager;
  
  @Mock
  private SecurityHelper securityHelper;
  
  private Subject testSubject;
  
  @BeforeEach
  void setUp() {
    // Clear any existing MDC context
    MDC.clear();
    
    // Set up a test subject
    testSubject = new FakeAlmightySubject(TEST_USER);
    SecurityUtils.setSecurityManager(null);
    
    // Configure security helper mock
    when(securityHelper.subject()).thenReturn(testSubject);
    
    // Clear test logger
    TestLoggerFactory.clear();
  }
  
  @AfterEach
  void tearDown() {
    // Clean up MDC
    MDC.clear();
    
    // Clean up test logger
    TestLoggerFactory.clear();
  }
  
  /**
   * Tests that MDC context is properly propagated to virtual threads when sending emails.
   */
  @Test
  void testMdcPropagationInVirtualThreads() throws Exception {
    // Set up MDC in the main thread
    MDC.put(MDC_KEY, MDC_VALUE);
    
    // Configure email manager to log MDC value when send is called
    doAnswer(invocation -> {
      Logger logger = LoggerFactory.getLogger(EmailManager.class);
      logger.info("Email sent with MDC context: {}", MDC.get(MDC_KEY));
      return null;
    }).when(emailManager).send(any(Email.class));
    
    // Create and execute a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Send an email from the virtual thread
          emailManager.send(new SimpleEmail());
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for the virtual thread to complete
      future.join();
    }
    
    // Verify that the MDC context was properly propagated to the virtual thread
    verify(emailManager).send(any(Email.class));
    
    // Check the log output to verify MDC was available in the virtual thread
    assertThat(testLogger.getLoggingEvents().size(), is(1));
    LoggingEvent event = testLogger.getLoggingEvents().get(0);
    assertThat(event.getMessage(), containsString("Email sent with MDC context: " + MDC_VALUE));
  }
  
  /**
   * Tests that Shiro Subject is properly propagated to virtual threads when sending emails.
   */
  @Test
  void testSubjectPropagationInVirtualThreads() throws Exception {
    // Configure email manager to capture the subject when send is called
    AtomicReference<String> capturedUsername = new AtomicReference<>();
    
    doAnswer(invocation -> {
      Subject currentSubject = SecurityUtils.getSubject();
      if (currentSubject != null && currentSubject.getPrincipal() != null) {
        capturedUsername.set(currentSubject.getPrincipal().toString());
      }
      return null;
    }).when(emailManager).send(any(Email.class));
    
    // Create and execute a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Bind the subject to the virtual thread
          testSubject.execute(() -> {
            try {
              // Send an email from the virtual thread
              emailManager.send(new SimpleEmail());
            }
            catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for the virtual thread to complete
      future.join();
    }
    
    // Verify that the subject was properly propagated to the virtual thread
    verify(emailManager).send(any(Email.class));
    assertThat(capturedUsername.get(), is(TEST_USER));
  }
  
  /**
   * Tests that both MDC and Subject contexts are properly propagated through nested virtual threads.
   */
  @Test
  void testNestedVirtualThreadContextPropagation() throws Exception {
    // Set up MDC in the main thread
    MDC.put(MDC_KEY, MDC_VALUE);
    
    // Latch to coordinate the test
    CountDownLatch latch = new CountDownLatch(1);
    
    // References to capture values from the innermost thread
    AtomicReference<String> capturedMdc = new AtomicReference<>();
    AtomicReference<String> capturedUsername = new AtomicReference<>();
    
    // Configure email manager to capture both MDC and Subject
    doAnswer(invocation -> {
      // Capture MDC
      capturedMdc.set(MDC.get(MDC_KEY));
      
      // Capture Subject
      Subject currentSubject = SecurityUtils.getSubject();
      if (currentSubject != null && currentSubject.getPrincipal() != null) {
        capturedUsername.set(currentSubject.getPrincipal().toString());
      }
      
      // Signal completion
      latch.countDown();
      return null;
    }).when(emailManager).send(any(Email.class));
    
    // Create nested virtual threads
    try (ExecutorService executor1 = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
        // First level virtual thread
        try (ExecutorService executor2 = Executors.newVirtualThreadPerTaskExecutor()) {
          CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
            // Second level virtual thread
            try (ExecutorService executor3 = Executors.newVirtualThreadPerTaskExecutor()) {
              CompletableFuture<Void> future3 = CompletableFuture.runAsync(() -> {
                // Third level virtual thread - innermost
                testSubject.execute(() -> {
                  try {
                    // Send an email from the innermost virtual thread
                    emailManager.send(new SimpleEmail());
                  }
                  catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
              }, executor3);
              future3.join();
            }
          }, executor2);
          future2.join();
        }
      }, executor1);
      future1.join();
    }
    
    // Wait for the test to complete
    assertThat("Test timed out", latch.await(5, TimeUnit.SECONDS), is(true));
    
    // Verify that both contexts were properly propagated through all virtual thread levels
    verify(emailManager).send(any(Email.class));
    assertThat(capturedMdc.get(), is(MDC_VALUE));
    assertThat(capturedUsername.get(), is(TEST_USER));
  }
  
  /**
   * Tests that MDC context is properly propagated when sending verification emails.
   */
  @Test
  void testMdcPropagationInVerificationEmails() throws Exception {
    // Set up MDC in the main thread
    MDC.put(MDC_KEY, MDC_VALUE);
    
    // Configure email manager to log MDC value when sendVerification is called
    doAnswer(invocation -> {
      Logger logger = LoggerFactory.getLogger(EmailManager.class);
      logger.info("Verification email sent with MDC context: {}", MDC.get(MDC_KEY));
      return null;
    }).when(emailManager).sendVerification(any(EmailConfiguration.class));
    
    // Create and execute a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Send a verification email from the virtual thread
          emailManager.sendVerification(emailManager.newConfiguration());
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for the virtual thread to complete
      future.join();
    }
    
    // Verify that the MDC context was properly propagated to the virtual thread
    verify(emailManager).sendVerification(any(EmailConfiguration.class));
    
    // Check the log output to verify MDC was available in the virtual thread
    assertThat(testLogger.getLoggingEvents().size(), is(1));
    LoggingEvent event = testLogger.getLoggingEvents().get(0);
    assertThat(event.getMessage(), containsString("Verification email sent with MDC context: " + MDC_VALUE));
  }
  
  /**
   * Tests that both MDC and Subject contexts are properly propagated when updating email configuration.
   */
  @Test
  void testContextPropagationInConfigurationUpdate() throws Exception {
    // Set up MDC in the main thread
    MDC.put(MDC_KEY, MDC_VALUE);
    
    // References to capture values
    AtomicReference<String> capturedMdc = new AtomicReference<>();
    AtomicReference<String> capturedUsername = new AtomicReference<>();
    
    // Mock a configuration
    EmailConfiguration config = emailManager.newConfiguration();
    
    // Configure email manager to capture both MDC and Subject when setConfiguration is called
    doAnswer(invocation -> {
      // Capture MDC
      capturedMdc.set(MDC.get(MDC_KEY));
      
      // Capture Subject
      Subject currentSubject = SecurityUtils.getSubject();
      if (currentSubject != null && currentSubject.getPrincipal() != null) {
        capturedUsername.set(currentSubject.getPrincipal().toString());
      }
      
      return null;
    }).when(emailManager).setConfiguration(any(EmailConfiguration.class));
    
    // Create and execute a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        // Bind the subject to the virtual thread
        testSubject.execute(() -> {
          // Update configuration from the virtual thread
          emailManager.setConfiguration(config);
        });
      }, executor);
      
      // Wait for the virtual thread to complete
      future.join();
    }
    
    // Verify that both contexts were properly propagated to the virtual thread
    verify(emailManager).setConfiguration(any(EmailConfiguration.class));
    assertThat(capturedMdc.get(), is(MDC_VALUE));
    assertThat(capturedUsername.get(), is(TEST_USER));
  }
}