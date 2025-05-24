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
package org.sonatype.nexus.core.virtualthread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.AuthenticationException;
import org.sonatype.nexus.security.authz.AuthorizationException;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Test class for validating security operations with Java 21 Virtual Threads in the Nexus Core component.
 * This class tests authentication, authorization, and security configuration operations under
 * high-concurrency Virtual Thread scenarios. It verifies that security components maintain proper
 * thread safety, context propagation, and resource management when executed across numerous
 * lightweight virtual threads, ensuring secure operations in Java 21's threading model.
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThreadTestGroup")
public class SecurityVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  private static final String TEST_USERNAME = "admin";
  private static final String TEST_PASSWORD = "admin123";
  private static final String TEST_PERMISSION = "nexus:*";

  @Mock
  private SecuritySystem securitySystem;

  private DefaultSecurityManager securityManager;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setUp() {
    // Set up a basic security manager for testing
    securityManager = new DefaultSecurityManager();
    SecurityUtils.setSecurityManager(securityManager);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("security-test-", 0).factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }

  @AfterEach
  public void tearDown() throws Exception {
    // Clean up security context
    ThreadContext.remove();
    SecurityUtils.setSecurityManager(null);
    
    // Shutdown the executor service
    virtualThreadExecutor.shutdown();
    if (!virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests that authentication operations work correctly when executed concurrently
   * across many virtual threads. This verifies that the security framework can handle
   * high-concurrency authentication requests using Java 21 Virtual Threads.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    // Set up a mock subject for testing
    Subject mockSubject = new FakeAlmightySubject();
    when(securitySystem.authenticate(new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD)))
        .thenReturn(mockSubject);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    // Submit authentication tasks to virtual threads
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform authentication
          Subject subject = securitySystem.authenticate(
              new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD));
          
          if (subject != null) {
            successCount.incrementAndGet();
          } else {
            failureCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          failureCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Authentication tasks did not complete within timeout");
    
    // Verify results
    assertEquals(CONCURRENT_THREADS, successCount.get(), 
        "All authentication attempts should succeed");
    assertEquals(0, failureCount.get(), 
        "No authentication attempts should fail");
    
    // Verify the authentication method was called the expected number of times
    verify(securitySystem, times(CONCURRENT_THREADS))
        .authenticate(new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD));
  }

  /**
   * Tests that authorization checks work correctly when executed concurrently
   * across many virtual threads. This verifies that the security framework can handle
   * high-concurrency permission checks using Java 21 Virtual Threads.
   */
  @Test
  public void testConcurrentAuthorizationWithVirtualThreads() throws Exception {
    // Set up mock behavior
    Subject mockSubject = new FakeAlmightySubject();
    when(securitySystem.authorize(mockSubject, TEST_PERMISSION)).thenReturn(true);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    // Submit authorization tasks to virtual threads
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform authorization check
          boolean isAuthorized = securitySystem.authorize(mockSubject, TEST_PERMISSION);
          
          if (isAuthorized) {
            successCount.incrementAndGet();
          } else {
            failureCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          failureCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Authorization tasks did not complete within timeout");
    
    // Verify results
    assertEquals(CONCURRENT_THREADS, successCount.get(), 
        "All authorization checks should succeed");
    assertEquals(0, failureCount.get(), 
        "No authorization checks should fail");
    
    // Verify the authorization method was called the expected number of times
    verify(securitySystem, times(CONCURRENT_THREADS)).authorize(mockSubject, TEST_PERMISSION);
  }

  /**
   * Tests that security context propagation works correctly when using virtual threads.
   * This verifies that the security context is properly maintained and propagated
   * during virtual thread handoffs and scheduling operations.
   */
  @Test
  public void testSecurityContextPropagationWithVirtualThreads() throws Exception {
    // Create a subject and bind it to the current thread
    Subject parentSubject = new FakeAlmightySubject();
    ThreadContext.bind(parentSubject);

    // Verify the subject is bound in the parent thread
    Subject currentSubject = ThreadContext.getSubject();
    assertNotNull(currentSubject, "Subject should be bound to parent thread");

    // Test context propagation to a child virtual thread
    AtomicReference<Subject> childThreadSubject = new AtomicReference<>();
    CountDownLatch childThreadLatch = new CountDownLatch(1);

    virtualThreadExecutor.submit(() -> {
      try {
        // Capture the subject in the child thread
        childThreadSubject.set(ThreadContext.getSubject());
      }
      finally {
        childThreadLatch.countDown();
      }
    });

    // Wait for the child thread to complete
    assertTrue(childThreadLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Child thread did not complete within timeout");

    // By default, ThreadContext is not propagated to child threads
    // This test verifies the current behavior to document it
    assertNull(childThreadSubject.get(), 
        "Subject should not be automatically propagated to child virtual thread");

    // Now test explicit context propagation
    AtomicReference<Subject> explicitPropagationSubject = new AtomicReference<>();
    CountDownLatch explicitPropagationLatch = new CountDownLatch(1);

    // Capture the current subject before creating the child thread
    Subject subjectToPropagate = ThreadContext.getSubject();

    virtualThreadExecutor.submit(() -> {
      try {
        // Explicitly bind the subject in this thread
        ThreadContext.bind(subjectToPropagate);
        
        // Capture the subject in the child thread after explicit binding
        explicitPropagationSubject.set(ThreadContext.getSubject());
      }
      finally {
        // Clean up thread context
        ThreadContext.unbindSubject();
        explicitPropagationLatch.countDown();
      }
    });

    // Wait for the child thread to complete
    assertTrue(explicitPropagationLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Explicit propagation thread did not complete within timeout");

    // Verify explicit propagation worked
    assertNotNull(explicitPropagationSubject.get(), 
        "Subject should be available in child thread after explicit propagation");
    assertEquals(parentSubject, explicitPropagationSubject.get(), 
        "Subject in child thread should match parent thread subject");
  }

  /**
   * Tests that concurrent security operations maintain thread safety when executed
   * across many virtual threads. This verifies that security components can handle
   * high-concurrency scenarios without thread safety issues.
   */
  @Test
  public void testThreadSafetyWithConcurrentSecurityOperations() throws Exception {
    // Set up mock behavior
    Subject mockSubject = new FakeAlmightySubject();
    when(securitySystem.authenticate(new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD)))
        .thenReturn(mockSubject);
    when(securitySystem.authorize(mockSubject, TEST_PERMISSION)).thenReturn(true);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    // Submit mixed security operations to virtual threads
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          if (index % 2 == 0) {
            // Even threads perform authentication
            Subject subject = securitySystem.authenticate(
                new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD));
            if (subject != null) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } else {
            // Odd threads perform authorization
            boolean isAuthorized = securitySystem.authorize(mockSubject, TEST_PERMISSION);
            if (isAuthorized) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          }
        } 
        catch (Exception e) {
          failureCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Security operations did not complete within timeout");
    
    // Verify results
    assertEquals(CONCURRENT_THREADS, successCount.get(), 
        "All security operations should succeed");
    assertEquals(0, failureCount.get(), 
        "No security operations should fail");
    
    // Verify the methods were called the expected number of times
    int expectedAuthCalls = CONCURRENT_THREADS / 2 + (CONCURRENT_THREADS % 2);
    int expectedAuthzCalls = CONCURRENT_THREADS / 2;
    
    verify(securitySystem, times(expectedAuthCalls))
        .authenticate(new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD));
    verify(securitySystem, times(expectedAuthzCalls)).authorize(mockSubject, TEST_PERMISSION);
  }
}