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
package org.apache.shiro;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.Factory;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests to validate Apache Shiro 2.0.0 compatibility with Java 21's Virtual Threads.
 * Verifies that Shiro security operations function correctly when running in Virtual Thread contexts,
 * including authentication, authorization, and session management.
 */
public class ShiroVirtualThreadTest
{
  private SecurityManager securityManager;

  @Before
  public void setUp() {
    // Create a simple security manager with an in-memory realm
    SimpleAccountRealm realm = new SimpleAccountRealm();
    realm.addAccount("user1", "password1", "role1");
    realm.addAccount("user2", "password2", "role2");
    
    securityManager = new DefaultSecurityManager(realm);
    SecurityUtils.setSecurityManager(securityManager);
  }

  @After
  public void tearDown() {
    ThreadContext.remove();
    SecurityUtils.setSecurityManager(null);
  }

  /**
   * Tests basic authentication within a Virtual Thread.
   */
  @Test
  public void testAuthenticationInVirtualThread() throws Exception {
    Thread virtualThread = Thread.startVirtualThread(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
      subject.login(token);
      assertTrue(subject.isAuthenticated());
      subject.logout();
      assertFalse(subject.isAuthenticated());
    });
    
    virtualThread.join();
  }

  /**
   * Tests authorization checks within a Virtual Thread.
   */
  @Test
  public void testAuthorizationInVirtualThread() throws Exception {
    Thread virtualThread = Thread.startVirtualThread(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
      subject.login(token);
      
      assertTrue(subject.hasRole("role1"));
      assertFalse(subject.hasRole("role2"));
      
      subject.logout();
    });
    
    virtualThread.join();
  }

  /**
   * Tests session management within a Virtual Thread.
   */
  @Test
  public void testSessionManagementInVirtualThread() throws Exception {
    Thread virtualThread = Thread.startVirtualThread(() -> {
      Subject subject = SecurityUtils.getSubject();
      Session session = subject.getSession();
      assertThat(session, is(notNullValue()));
      
      // Test session attribute storage and retrieval
      session.setAttribute("testKey", "testValue");
      assertEquals("testValue", session.getAttribute("testKey"));
      
      // Test session timeout configuration
      long originalTimeout = session.getTimeout();
      session.setTimeout(3600000); // 1 hour
      assertEquals(3600000, session.getTimeout());
      
      // Restore original timeout
      session.setTimeout(originalTimeout);
    });
    
    virtualThread.join();
  }

  /**
   * Tests that ThreadLocal state is properly maintained in Virtual Threads.
   */
  @Test
  public void testThreadLocalStateInVirtualThread() throws Exception {
    Thread virtualThread = Thread.startVirtualThread(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
      subject.login(token);
      
      // Verify that the subject is bound to the ThreadContext
      Subject threadContextSubject = ThreadContext.getSubject();
      assertThat(threadContextSubject, is(notNullValue()));
      assertEquals(subject, threadContextSubject);
      
      // Verify that the security manager is bound to the ThreadContext
      SecurityManager threadContextSecurityManager = ThreadContext.getSecurityManager();
      assertThat(threadContextSecurityManager, is(notNullValue()));
      assertEquals(securityManager, threadContextSecurityManager);
      
      subject.logout();
    });
    
    virtualThread.join();
  }

  /**
   * Tests that multiple Virtual Threads can operate independently with their own Shiro contexts.
   */
  @Test
  public void testMultipleVirtualThreadsWithIndependentContexts() throws Exception {
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Thread> threads = new ArrayList<>();
    
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread virtualThread = Thread.startVirtualThread(() -> {
        try {
          // Each thread gets its own subject
          Subject subject = SecurityUtils.getSubject();
          
          // Even threads use user1, odd threads use user2
          String username = (index % 2 == 0) ? "user1" : "user2";
          String password = (index % 2 == 0) ? "password1" : "password2";
          String expectedRole = (index % 2 == 0) ? "role1" : "role2";
          
          UsernamePasswordToken token = new UsernamePasswordToken(username, password);
          subject.login(token);
          
          // Verify correct role assignment
          assertTrue(subject.hasRole(expectedRole));
          
          // Set a thread-specific session attribute
          Session session = subject.getSession();
          String attributeKey = "thread-" + index;
          session.setAttribute(attributeKey, "value-" + index);
          
          // Verify the attribute was set correctly
          assertEquals("value-" + index, session.getAttribute(attributeKey));
          
          subject.logout();
        } finally {
          latch.countDown();
        }
      });
      
      threads.add(virtualThread);
    }
    
    // Wait for all threads to complete
    latch.await(10, TimeUnit.SECONDS);
    
    // Join all threads to ensure they've completed
    for (Thread thread : threads) {
      thread.join();
    }
  }

  /**
   * Tests that Virtual Thread Executor works correctly with Shiro's Subject.associateWith().
   */
  @Test
  public void testVirtualThreadExecutorWithSubjectAssociation() throws Exception {
    // Create and authenticate a subject
    Subject subject = SecurityUtils.getSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
    subject.login(token);
    
    try {
      // Create a virtual thread executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Create a runnable that will be associated with the subject
        Runnable secureRunnable = subject.associateWith(() -> {
          // This code should run with the subject's security context
          Subject executorSubject = SecurityUtils.getSubject();
          assertTrue(executorSubject.isAuthenticated());
          assertTrue(executorSubject.hasRole("role1"));
          assertEquals(subject.getPrincipal(), executorSubject.getPrincipal());
        });
        
        // Submit the runnable to the executor and wait for it to complete
        Future<?> future = executor.submit(secureRunnable);
        future.get(5, TimeUnit.SECONDS);
      }
    } finally {
      subject.logout();
    }
  }

  /**
   * Tests that ThreadContext is properly cleaned up after Virtual Thread completion.
   */
  @Test
  public void testThreadContextCleanupAfterVirtualThreadCompletion() throws Exception {
    AtomicReference<Subject> subjectRef = new AtomicReference<>();
    AtomicBoolean cleanupVerified = new AtomicBoolean(false);
    
    Thread virtualThread = Thread.startVirtualThread(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
      subject.login(token);
      
      // Store the subject for later verification
      subjectRef.set(subject);
      
      // Verify ThreadContext has the subject and security manager
      assertThat(ThreadContext.getSubject(), is(notNullValue()));
      assertThat(ThreadContext.getSecurityManager(), is(notNullValue()));
      
      // Explicitly remove ThreadContext resources
      ThreadContext.remove();
      
      // Verify ThreadContext is cleared
      assertThat(ThreadContext.getSubject(), is(org.hamcrest.Matchers.nullValue()));
      assertThat(ThreadContext.getSecurityManager(), is(org.hamcrest.Matchers.nullValue()));
      
      cleanupVerified.set(true);
    });
    
    virtualThread.join();
    
    // Verify that our cleanup checks ran
    assertTrue(cleanupVerified.get());
    assertThat(subjectRef.get(), is(notNullValue()));
  }

  /**
   * Tests that Shiro's SecurityManager is accessible from Virtual Threads.
   */
  @Test
  public void testSecurityManagerAccessFromVirtualThread() throws Exception {
    Thread virtualThread = Thread.startVirtualThread(() -> {
      SecurityManager sm = SecurityUtils.getSecurityManager();
      assertThat(sm, is(notNullValue()));
      assertEquals(securityManager, sm);
    });
    
    virtualThread.join();
  }

  /**
   * Tests parallel execution of Shiro operations in multiple Virtual Threads.
   */
  @Test
  public void testParallelVirtualThreadsWithShiro() throws Exception {
    int threadCount = 100; // Test with 100 virtual threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean anyFailures = new AtomicBoolean(false);
    
    List<Thread> threads = new ArrayList<>();
    
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread virtualThread = Thread.startVirtualThread(() -> {
        try {
          // Wait for all threads to be created before starting
          startLatch.await();
          
          // Each thread gets its own subject
          Subject subject = SecurityUtils.getSubject();
          
          // Even threads use user1, odd threads use user2
          String username = (index % 2 == 0) ? "user1" : "user2";
          String password = (index % 2 == 0) ? "password1" : "password2";
          String expectedRole = (index % 2 == 0) ? "role1" : "role2";
          
          UsernamePasswordToken token = new UsernamePasswordToken(username, password);
          subject.login(token);
          
          // Verify correct authentication and authorization
          assertTrue(subject.isAuthenticated());
          assertTrue(subject.hasRole(expectedRole));
          
          // Create a session and store some data
          Session session = subject.getSession();
          session.setAttribute("threadIndex", index);
          assertEquals(index, session.getAttribute("threadIndex"));
          
          // Logout
          subject.logout();
          assertFalse(subject.isAuthenticated());
        } catch (Exception e) {
          anyFailures.set(true);
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(virtualThread);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean allCompleted = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertTrue("Not all threads completed in time", allCompleted);
    assertFalse("Some threads encountered failures", anyFailures.get());
  }
}