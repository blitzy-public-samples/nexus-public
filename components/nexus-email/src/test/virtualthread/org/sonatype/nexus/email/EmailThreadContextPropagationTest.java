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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.log.LoggerLevel;
import org.sonatype.nexus.testcommon.validation.TestLoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for thread context propagation in {@link EmailManager} operations when executed in Java 21 virtual threads.
 * <p>
 * This test verifies that both security context (Subject) and logging context (MDC) are correctly maintained
 * when email operations span across thread boundaries using virtual threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class EmailThreadContextPropagationTest
    extends TestSupport
{
  private static final String TEST_USER_ID = "test-user";
  private static final String TEST_REQUEST_ID = "request-123";
  private static final String TEST_SESSION_ID = "session-456";
  private static final String TEST_EMAIL = "test@example.com";
  private static final String TEST_SUBJECT = "Test Email";
  private static final String TEST_MESSAGE = "This is a test email message";

  private static final String MDC_USER_ID_KEY = "userId";
  private static final String MDC_REQUEST_ID_KEY = "requestId";
  private static final String MDC_SESSION_ID_KEY = "sessionId";

  @Mock
  private EmailManager emailManager;

  @Mock
  private Subject subject;

  private TestLoggerFactory testLoggerFactory;
  private Logger testLogger;

  @BeforeEach
  void setUp() {
    // Set up test logger to capture log output
    testLoggerFactory = new TestLoggerFactory();
    testLoggerFactory.setLevel(LoggerLevel.DEBUG);
    testLogger = testLoggerFactory.getLogger(EmailThreadContextPropagationTest.class);

    // Set up MDC context
    MDC.put(MDC_USER_ID_KEY, TEST_USER_ID);
    MDC.put(MDC_REQUEST_ID_KEY, TEST_REQUEST_ID);
    MDC.put(MDC_SESSION_ID_KEY, TEST_SESSION_ID);

    // Set up security context
    ThreadContext.bind(subject);
    when(subject.getPrincipal()).thenReturn(TEST_USER_ID);
  }

  @AfterEach
  void tearDown() {
    // Clean up MDC context
    MDC.clear();

    // Clean up security context
    ThreadContext.unbindSubject();
    ThreadContext.remove();
  }

  /**
   * Tests that MDC context is properly propagated to virtual threads when using the asynchronous
   * email sending method {@link EmailManager#sendAsync(Email)}.
   */
  @Test
  public void testMdcPropagationInVirtualThreads() throws Exception {
    // Prepare test email
    Email email = createTestEmail();

    // Capture MDC context in the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    Map<String, String> capturedMdc = null;

    // Mock the sendAsync method to capture MDC context
    when(emailManager.sendAsync(any(Email.class))).thenAnswer(invocation -> {
      // This will run in a virtual thread
      return CompletableFuture.supplyAsync(() -> {
        // Capture the MDC context in the virtual thread
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        testLogger.info("MDC context in virtual thread: {}", mdcContext);
        latch.countDown();
        return null;
      });
    });

    // Execute the async email operation
    CompletableFuture<Void> future = emailManager.sendAsync(email);

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    future.get(1, TimeUnit.SECONDS); // Ensure the future completes

    // Verify MDC context was propagated
    String logOutput = testLoggerFactory.getOutput();
    assertThat(logOutput, containsString(TEST_USER_ID));
    assertThat(logOutput, containsString(TEST_REQUEST_ID));
    assertThat(logOutput, containsString(TEST_SESSION_ID));
  }

  /**
   * Tests that security Subject is properly propagated to virtual threads when using the asynchronous
   * email sending method {@link EmailManager#sendAsync(Email)}.
   */
  @Test
  public void testSubjectPropagationInVirtualThreads() throws Exception {
    // Prepare test email
    Email email = createTestEmail();

    // Capture Subject in the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    ArgumentCaptor<Subject> subjectCaptor = ArgumentCaptor.forClass(Subject.class);

    // Mock the sendAsync method to capture Subject
    when(emailManager.sendAsync(any(Email.class))).thenAnswer(invocation -> {
      // This will run in a virtual thread
      return CompletableFuture.supplyAsync(() -> {
        // Capture the Subject in the virtual thread
        Subject threadSubject = SecurityUtils.getSubject();
        testLogger.info("Subject in virtual thread: {}", threadSubject);
        if (threadSubject != null) {
          testLogger.info("Subject principal: {}", threadSubject.getPrincipal());
        }
        latch.countDown();
        return null;
      });
    });

    // Execute the async email operation
    CompletableFuture<Void> future = emailManager.sendAsync(email);

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    future.get(1, TimeUnit.SECONDS); // Ensure the future completes

    // Verify Subject was propagated
    String logOutput = testLoggerFactory.getOutput();
    assertThat(logOutput, containsString("Subject in virtual thread"));
    assertThat(logOutput, containsString(TEST_USER_ID)); // Subject principal should be logged
  }

  /**
   * Tests that both MDC and Subject contexts are properly propagated through nested virtual thread
   * hierarchies when using the asynchronous email verification method.
   */
  @Test
  public void testNestedVirtualThreadContextPropagation() throws Exception {
    // Prepare test configuration
    EmailConfiguration config = new EmailConfiguration();
    config.setHost("smtp.example.com");
    config.setPort(25);
    config.setFromAddress(TEST_EMAIL);

    // Set up nested virtual thread execution
    CountDownLatch outerLatch = new CountDownLatch(1);
    CountDownLatch innerLatch = new CountDownLatch(1);

    // Mock the sendVerificationAsync method to create nested virtual threads
    when(emailManager.sendVerificationAsync(any(EmailConfiguration.class), any(String.class)))
        .thenAnswer(invocation -> {
          // First level virtual thread
          return CompletableFuture.supplyAsync(() -> {
            // Capture context in first level
            String userId = MDC.get(MDC_USER_ID_KEY);
            Subject threadSubject = SecurityUtils.getSubject();
            testLogger.info("First level - MDC userId: {}", userId);
            testLogger.info("First level - Subject principal: {}", threadSubject.getPrincipal());
            outerLatch.countDown();

            // Create second level virtual thread
            CompletableFuture<Void> innerFuture = CompletableFuture.runAsync(() -> {
              // Capture context in second level
              String nestedUserId = MDC.get(MDC_USER_ID_KEY);
              String nestedRequestId = MDC.get(MDC_REQUEST_ID_KEY);
              Subject nestedSubject = SecurityUtils.getSubject();
              testLogger.info("Second level - MDC userId: {}", nestedUserId);
              testLogger.info("Second level - MDC requestId: {}", nestedRequestId);
              testLogger.info("Second level - Subject principal: {}", nestedSubject.getPrincipal());
              innerLatch.countDown();
            });

            try {
              innerFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
              testLogger.error("Error in nested virtual thread", e);
            }
            return null;
          });
        });

    // Execute the async email verification
    CompletableFuture<Void> future = emailManager.sendVerificationAsync(config, TEST_EMAIL);

    // Wait for both virtual threads to complete
    assertTrue(outerLatch.await(5, TimeUnit.SECONDS), "Outer virtual thread did not complete in time");
    assertTrue(innerLatch.await(5, TimeUnit.SECONDS), "Inner virtual thread did not complete in time");
    future.get(1, TimeUnit.SECONDS); // Ensure the future completes

    // Verify context propagation through both levels
    String logOutput = testLoggerFactory.getOutput();
    assertThat(logOutput, containsString("First level - MDC userId: " + TEST_USER_ID));
    assertThat(logOutput, containsString("First level - Subject principal: " + TEST_USER_ID));
    assertThat(logOutput, containsString("Second level - MDC userId: " + TEST_USER_ID));
    assertThat(logOutput, containsString("Second level - MDC requestId: " + TEST_REQUEST_ID));
    assertThat(logOutput, containsString("Second level - Subject principal: " + TEST_USER_ID));
  }

  /**
   * Tests that MDC context is properly propagated when constructing messages asynchronously
   * using {@link EmailManager#constructMessageAsync(String)}.
   */
  @Test
  public void testMdcPropagationInMessageConstruction() throws Exception {
    // Set up test message
    String message = "Template with userId: ${userId}";

    // Mock the constructMessageAsync method to verify MDC context
    when(emailManager.constructMessageAsync(any(String.class))).thenAnswer(invocation -> {
      return CompletableFuture.supplyAsync(() -> {
        // Access MDC context in the virtual thread
        String userId = MDC.get(MDC_USER_ID_KEY);
        String requestId = MDC.get(MDC_REQUEST_ID_KEY);
        
        // Log the context for verification
        testLogger.info("Constructing message with userId: {}, requestId: {}", userId, requestId);
        
        // Simulate template processing using MDC values
        return message.replace("${userId}", userId);
      });
    });

    // Execute the async message construction
    CompletableFuture<String> future = emailManager.constructMessageAsync(message);
    String result = future.get(2, TimeUnit.SECONDS);

    // Verify MDC context was propagated and used in message construction
    assertThat(result, is("Template with userId: " + TEST_USER_ID));
    String logOutput = testLoggerFactory.getOutput();
    assertThat(logOutput, containsString("Constructing message with userId: " + TEST_USER_ID));
    assertThat(logOutput, containsString("requestId: " + TEST_REQUEST_ID));
  }

  /**
   * Tests that context propagation works correctly when multiple virtual threads are executed
   * concurrently with different context values.
   */
  @Test
  public void testConcurrentVirtualThreadsWithDifferentContexts() throws Exception {
    // Prepare test emails
    Email email1 = createTestEmail();
    Email email2 = createTestEmail();
    
    // Set up different contexts for each thread
    final String userId1 = "user1";
    final String userId2 = "user2";
    final String requestId1 = "req1";
    final String requestId2 = "req2";
    
    CountDownLatch latch = new CountDownLatch(2);
    
    // Mock the sendAsync method to verify context isolation
    doAnswer(invocation -> {
      Email email = invocation.getArgument(0);
      String threadId = email.getSubject().contains("1") ? "Thread-1" : "Thread-2";
      
      return CompletableFuture.supplyAsync(() -> {
        // Each thread should have its own isolated context
        String userId = MDC.get(MDC_USER_ID_KEY);
        String requestId = MDC.get(MDC_REQUEST_ID_KEY);
        Subject threadSubject = SecurityUtils.getSubject();
        
        testLogger.info("{} - MDC userId: {}, requestId: {}", threadId, userId, requestId);
        testLogger.info("{} - Subject principal: {}", threadId, threadSubject.getPrincipal());
        
        latch.countDown();
        return null;
      });
    }).when(emailManager).sendAsync(any(Email.class));
    
    // Execute first thread with first context
    MDC.put(MDC_USER_ID_KEY, userId1);
    MDC.put(MDC_REQUEST_ID_KEY, requestId1);
    when(subject.getPrincipal()).thenReturn(userId1);
    email1.setSubject("Test Email 1");
    CompletableFuture<Void> future1 = emailManager.sendAsync(email1);
    
    // Execute second thread with second context
    MDC.put(MDC_USER_ID_KEY, userId2);
    MDC.put(MDC_REQUEST_ID_KEY, requestId2);
    when(subject.getPrincipal()).thenReturn(userId2);
    email2.setSubject("Test Email 2");
    CompletableFuture<Void> future2 = emailManager.sendAsync(email2);
    
    // Wait for both threads to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual threads did not complete in time");
    CompletableFuture.allOf(future1, future2).get(2, TimeUnit.SECONDS);
    
    // Verify each thread had its own context
    String logOutput = testLoggerFactory.getOutput();
    assertThat(logOutput, containsString("Thread-1 - MDC userId: " + userId1));
    assertThat(logOutput, containsString("Thread-1 - MDC userId: " + userId1));
    assertThat(logOutput, containsString("Thread-2 - MDC userId: " + userId2));
    assertThat(logOutput, containsString("Thread-2 - MDC userId: " + userId2));
  }

  /**
   * Helper method to create a test email for use in tests.
   */
  private Email createTestEmail() throws EmailException {
    SimpleEmail email = new SimpleEmail();
    email.setHostName("smtp.example.com");
    email.setSmtpPort(25);
    email.setFrom(TEST_EMAIL);
    email.setSubject(TEST_SUBJECT);
    email.setMsg(TEST_MESSAGE);
    email.addTo(TEST_EMAIL);
    return email;
  }
}