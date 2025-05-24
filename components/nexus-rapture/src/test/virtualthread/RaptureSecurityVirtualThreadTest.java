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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.common.wonderland.AuthTicketService;
import org.sonatype.nexus.rapture.internal.security.SecurityComponent;
import org.sonatype.nexus.rapture.internal.security.UserXO;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;
import org.sonatype.nexus.security.authz.WildcardPermission2;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link SecurityComponent} with Java 21 Virtual Threads to validate authentication and authorization
 * operations under high concurrency.
 * 
 * This test class verifies that security operations remain thread-safe and performant when executed with
 * thousands of concurrent virtual threads, ensuring that the Rapture UI's security layer can handle
 * extreme load conditions efficiently.
 */
public class RaptureSecurityVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int WARMUP_THREADS = 100;
  private static final int THREAD_TIMEOUT_SECONDS = 30;
  private static final String TEST_USERNAME = "testuser";
  private static final String TEST_PASSWORD = "testpassword";
  private static final String TEST_REALM = "testrealm";
  private static final String TEST_TICKET = "testticket";
  
  @Mock
  private SecuritySystem securitySystem;
  
  @Mock
  private AnonymousManager anonymousManager;
  
  @Mock
  private AuthTicketService authTickets;
  
  @Mock
  private Subject subject;
  
  @Mock
  private PrincipalCollection principals;
  
  @Mock
  private SecurityManager securityManager;
  
  private SecurityComponent underTest;
  
  @Before
  public void setup() {
    // Configure mocks
    when(securitySystem.getSubject()).thenReturn(subject);
    when(subject.getPrincipals()).thenReturn(principals);
    
    Set<String> realmNames = new HashSet<>();
    realmNames.add(TEST_REALM);
    when(principals.getRealmNames()).thenReturn(realmNames);
    
    when(subject.getPrincipal()).thenReturn(TEST_USERNAME);
    when(subject.isAuthenticated()).thenReturn(true);
    
    AnonymousConfiguration anonConfig = new AnonymousConfiguration();
    anonConfig.setEnabled(true);
    anonConfig.setUserId("anonymous");
    when(anonymousManager.getConfiguration()).thenReturn(anonConfig);
    
    when(authTickets.createTicket(TEST_USERNAME, TEST_REALM)).thenReturn(TEST_TICKET);
    
    // Create the component under test
    underTest = new SecurityComponent(securitySystem, anonymousManager, authTickets);
    
    // Bind the subject to the current thread
    ThreadContext.bind(subject);
  }
  
  @After
  public void cleanup() {
    // Unbind the subject from the current thread
    ThreadContext.unbindSubject();
  }
  
  /**
   * Tests that the {@link SecurityComponent#getUser()} method is thread-safe when called
   * concurrently from multiple virtual threads.
   */
  @Test
  public void testGetUserWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicReference<Exception> firstException = new AtomicReference<>();
      
      // Submit tasks to get user information concurrently
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Get the user
            UserXO user = underTest.getUser();
            
            // Verify the user information
            assertThat(user, notNullValue());
            assertThat(user.getId(), equalTo(TEST_USERNAME));
            assertThat(user.isAuthenticated(), is(true));
          }
          catch (Exception e) {
            if (failed.compareAndSet(false, true)) {
              firstException.set(e);
            }
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Check if any thread failed
      if (failed.get()) {
        fail("Concurrent execution failed: " + firstException.get().getMessage());
      }
      
      // Verify that the security system was called the expected number of times
      verify(securitySystem, times(CONCURRENT_THREADS)).getSubject();
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Tests that the {@link SecurityComponent#authenticate(String, String)} method is thread-safe
   * when called concurrently from multiple virtual threads.
   */
  @Test
  public void testAuthenticateWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Encode credentials
      String base64Username = Strings2.encodeBase64(TEST_USERNAME);
      String base64Password = Strings2.encodeBase64(TEST_PASSWORD);
      
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicReference<Exception> firstException = new AtomicReference<>();
      
      // Submit tasks to authenticate concurrently
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Authenticate
            UserXO user = underTest.authenticate(base64Username, base64Password);
            
            // Verify the user information
            assertThat(user, notNullValue());
            assertThat(user.getId(), equalTo(TEST_USERNAME));
            assertThat(user.isAuthenticated(), is(true));
          }
          catch (Exception e) {
            if (failed.compareAndSet(false, true)) {
              firstException.set(e);
            }
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Check if any thread failed
      if (failed.get()) {
        fail("Concurrent execution failed: " + firstException.get().getMessage());
      }
      
      // Verify that the security system was called the expected number of times
      verify(securitySystem, times(CONCURRENT_THREADS)).getSubject();
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Tests that the {@link SecurityComponent#authenticationToken(String, String)} method is thread-safe
   * when called concurrently from multiple virtual threads.
   */
  @Test
  public void testAuthenticationTokenWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Encode credentials
      String base64Username = Strings2.encodeBase64(TEST_USERNAME);
      String base64Password = Strings2.encodeBase64(TEST_PASSWORD);
      
      // Configure security manager mock
      when(securitySystem.getSecurityManager()).thenReturn(securityManager);
      
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicReference<Exception> firstException = new AtomicReference<>();
      
      // Submit tasks to get authentication tokens concurrently
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Get authentication token
            String token = underTest.authenticationToken(base64Username, base64Password);
            
            // Verify the token
            assertThat(token, equalTo(TEST_TICKET));
          }
          catch (Exception e) {
            if (failed.compareAndSet(false, true)) {
              firstException.set(e);
            }
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Check if any thread failed
      if (failed.get()) {
        fail("Concurrent execution failed: " + firstException.get().getMessage());
      }
      
      // Verify that the security manager was called the expected number of times
      verify(securityManager, times(CONCURRENT_THREADS)).authenticate(any(UsernamePasswordToken.class));
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Tests that the {@link SecurityComponent#getPermissions()} method is thread-safe
   * when called concurrently from multiple virtual threads.
   */
  @Test
  public void testGetPermissionsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicReference<Exception> firstException = new AtomicReference<>();
      
      // Submit tasks to get permissions concurrently
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Get permissions
            underTest.getPermissions();
          }
          catch (Exception e) {
            if (failed.compareAndSet(false, true)) {
              firstException.set(e);
            }
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Check if any thread failed
      if (failed.get()) {
        fail("Concurrent execution failed: " + firstException.get().getMessage());
      }
      
      // Verify that the security system was called the expected number of times
      verify(securitySystem, times(CONCURRENT_THREADS)).getSubject();
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Tests that authentication failures are properly handled when using virtual threads.
   */
  @Test
  public void testAuthenticationFailureWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Encode credentials
      String base64Username = Strings2.encodeBase64(TEST_USERNAME);
      String base64Password = Strings2.encodeBase64(TEST_PASSWORD);
      
      // Configure security manager to throw authentication exception
      when(securitySystem.getSecurityManager()).thenReturn(securityManager);
      doThrow(new AuthenticationException("Authentication failed"))
          .when(securityManager).authenticate(any(UsernamePasswordToken.class));
      
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger failureCount = new AtomicInteger(0);
      
      // Submit tasks to get authentication tokens concurrently
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Try to get authentication token (should fail)
            underTest.authenticationToken(base64Username, base64Password);
            
            // Should not reach here
            fail("Expected authentication to fail");
          }
          catch (Exception e) {
            // Expected exception
            failureCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Verify that all authentication attempts failed
      assertEquals(CONCURRENT_THREADS, failureCount.get());
      
      // Verify that the security manager was called the expected number of times
      verify(securityManager, times(CONCURRENT_THREADS)).authenticate(any(UsernamePasswordToken.class));
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Tests that the Shiro security context is properly maintained across virtual threads.
   */
  @Test
  public void testShiroSecurityContextWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicBoolean contextLost = new AtomicBoolean(false);
      
      // Submit tasks to check security context in virtual threads
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Check if the security context is properly maintained
            Subject threadSubject = ThreadContext.getSubject();
            if (threadSubject == null) {
              contextLost.set(true);
            }
          }
          catch (Exception e) {
            contextLost.set(true);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Verify that the security context was not lost in any thread
      assertFalse("Security context was lost in at least one virtual thread", contextLost.get());
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Compares the performance of virtual threads vs platform threads for security operations.
   */
  @Test
  public void testVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Run performance test with platform threads
    long platformThreadTime = measurePerformance(Thread.ofPlatform().factory(), WARMUP_THREADS);
    
    // Run performance test with virtual threads
    long virtualThreadTime = measurePerformance(Thread.ofVirtual().factory(), WARMUP_THREADS);
    
    // Log the results
    log.info("Platform threads execution time: {} ms", platformThreadTime);
    log.info("Virtual threads execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads", 
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests the memory consumption of virtual threads under high load.
   */
  @Test
  public void testVirtualThreadMemoryConsumption() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Measure memory before creating threads
      Runtime runtime = Runtime.getRuntime();
      System.gc(); // Request garbage collection to get more accurate measurements
      long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
      
      // Create a large number of virtual threads
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Simulate some work
            Thread.sleep(100);
            
            // Get user to exercise security component
            underTest.getUser();
          }
          catch (Exception e) {
            // Ignore exceptions for this test
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Measure memory after threads have completed
      System.gc(); // Request garbage collection to get more accurate measurements
      long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
      
      // Calculate memory used per thread
      long memoryDifference = memoryAfter - memoryBefore;
      long memoryPerThread = memoryDifference / CONCURRENT_THREADS;
      
      // Log the results
      log.info("Memory before: {} bytes", memoryBefore);
      log.info("Memory after: {} bytes", memoryAfter);
      log.info("Memory difference: {} bytes", memoryDifference);
      log.info("Memory per virtual thread: {} bytes", memoryPerThread);
      
      // Virtual threads should use significantly less memory than platform threads
      // Platform threads typically use 1-2MB each, virtual threads should use much less
      assertThat("Virtual threads should use minimal memory", memoryPerThread, lessThan(100000L));
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Tests that permission checking works correctly under high concurrency with virtual threads.
   */
  @Test
  public void testPermissionCheckingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Configure subject to have admin permission
      when(subject.isPermitted(any(WildcardPermission2.class))).thenReturn(true);
      
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicReference<Exception> firstException = new AtomicReference<>();
      
      // Submit tasks to get user with permission check concurrently
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Get user (which checks permissions)
            UserXO user = underTest.getUser();
            
            // Verify the user has admin flag set (based on permission check)
            assertThat(user, notNullValue());
            assertThat(user.isAdministrator(), is(true));
          }
          catch (Exception e) {
            if (failed.compareAndSet(false, true)) {
              firstException.set(e);
            }
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed in time", completed);
      
      // Check if any thread failed
      if (failed.get()) {
        fail("Concurrent execution failed: " + firstException.get().getMessage());
      }
      
      // Verify that the permission check was called the expected number of times
      verify(subject, times(CONCURRENT_THREADS)).isPermitted(any(WildcardPermission2.class));
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Helper method to measure the performance of security operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param threadCount The number of concurrent threads to use
   * @return The execution time in milliseconds
   */
  private long measurePerformance(ThreadFactory threadFactory, int threadCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Create a latch to synchronize thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      
      // Record start time
      long startTime = System.currentTimeMillis();
      
      // Submit tasks
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform a mix of security operations
            for (int j = 0; j < 10; j++) {
              underTest.getUser();
              underTest.getPermissions();
            }
          }
          catch (Exception e) {
            // Ignore exceptions for performance measurement
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Record end time
      long endTime = System.currentTimeMillis();
      
      // Return execution time
      return endTime - startTime;
    }
    finally {
      executor.shutdownNow();
    }
  }
}