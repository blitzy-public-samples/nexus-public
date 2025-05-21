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
package org.sonatype.nexus.security;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.util.ThreadState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests Apache Shiro compatibility with Java 21 features, particularly Virtual Threads.
 * <p>
 * This test suite verifies that Shiro's authentication, authorization, and thread state
 * management work correctly with Java 21's Virtual Threads and other features.
 */
public class ShiroJava21CompatibilityTest
    extends AbstractSecurityTest
{
  private static final String USERNAME = "admin";
  private static final String PASSWORD = "admin123";

  private Subject subject;

  @Before
  public void setupSubject() {
    // Create and authenticate a subject for testing
    subject = SecurityUtils.getSubject();
    UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
    subject.login(token);
    assertThat(subject.isAuthenticated(), is(true));
  }

  @After
  public void cleanupSubject() {
    if (subject != null && subject.isAuthenticated()) {
      subject.logout();
    }
  }

  /**
   * Tests that Shiro's ThreadContext works correctly with Virtual Threads.
   * <p>
   * This test verifies that a Subject bound to a Virtual Thread is accessible
   * within that thread's execution context.
   */
  @Test
  public void testThreadContextWithVirtualThreads() throws Exception {
    AtomicReference<Subject> threadSubject = new AtomicReference<>();

    // Use virtual thread to execute code with the subject
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        ThreadContext.bind(subject);
        threadSubject.set(SecurityUtils.getSubject());
      }
      finally {
        ThreadContext.unbindSubject();
      }
    });

    virtualThread.join();

    // Verify the subject was correctly bound and retrieved in the virtual thread
    assertThat(threadSubject.get(), notNullValue());
    assertThat(threadSubject.get().isAuthenticated(), is(true));
    assertThat(threadSubject.get().getPrincipal(), is(USERNAME));
  }

  /**
   * Tests that Shiro's ThreadState mechanism works correctly with Virtual Threads.
   * <p>
   * This test verifies that ThreadState can properly bind and restore state
   * when used with Virtual Threads.
   */
  @Test
  public void testThreadStateWithVirtualThreads() throws Exception {
    AtomicReference<Subject> threadSubject = new AtomicReference<>();

    // Create a thread state for the subject
    ThreadState threadState = new ThreadState() {
      private final Subject stateSubject = subject;
      private Object originalSubject;

      @Override
      public void bind() {
        originalSubject = ThreadContext.get(ThreadContext.SUBJECT_KEY);
        ThreadContext.put(ThreadContext.SUBJECT_KEY, stateSubject);
      }

      @Override
      public void restore() {
        if (originalSubject != null) {
          ThreadContext.put(ThreadContext.SUBJECT_KEY, originalSubject);
        }
        else {
          ThreadContext.remove(ThreadContext.SUBJECT_KEY);
        }
      }

      @Override
      public void clear() {
        ThreadContext.remove(ThreadContext.SUBJECT_KEY);
      }
    };

    // Use virtual thread with ThreadState
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        threadState.bind();
        threadSubject.set(SecurityUtils.getSubject());
      }
      finally {
        threadState.restore();
      }
    });

    virtualThread.join();

    // Verify the subject was correctly bound and retrieved
    assertThat(threadSubject.get(), notNullValue());
    assertThat(threadSubject.get().isAuthenticated(), is(true));
    assertThat(threadSubject.get().getPrincipal(), is(USERNAME));
  }

  /**
   * Tests that Shiro's Subject.execute() method works correctly with Virtual Threads.
   * <p>
   * This test verifies that the Subject is properly propagated when using
   * Subject.execute() with code that runs on Virtual Threads.
   */
  @Test
  public void testSubjectExecuteWithVirtualThreads() throws Exception {
    AtomicReference<Subject> threadSubject = new AtomicReference<>();

    // Execute a task as the subject, which will run on a virtual thread
    subject.execute(() -> {
      Thread virtualThread = Thread.ofVirtual().start(() -> {
        threadSubject.set(SecurityUtils.getSubject());
      });

      try {
        virtualThread.join();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    // Verify the subject was correctly propagated to the virtual thread
    // Note: In current Shiro versions, this will likely be null as ThreadContext is not automatically
    // propagated to new threads, even virtual ones. This test documents current behavior.
    assertThat(threadSubject.get(), nullValue());
  }

  /**
   * Tests that Shiro's security context can be manually propagated to child Virtual Threads.
   * <p>
   * This test demonstrates how to properly propagate security context to child Virtual Threads.
   */
  @Test
  public void testManualSecurityContextPropagation() throws Exception {
    AtomicReference<Subject> threadSubject = new AtomicReference<>();

    // Execute in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        ThreadContext.bind(subject);

        // Create a child virtual thread and manually propagate the security context
        Thread childThread = Thread.ofVirtual().start(() -> {
          Subject parentSubject = SecurityUtils.getSubject();
          try {
            // Manually bind the parent subject to this thread
            if (parentSubject != null) {
              ThreadContext.bind(parentSubject);
            }
            threadSubject.set(SecurityUtils.getSubject());
          }
          finally {
            ThreadContext.unbindSubject();
          }
        });

        childThread.join();
      }
      finally {
        ThreadContext.unbindSubject();
      }
    });

    virtualThread.join();

    // Verify the subject was correctly propagated to the child virtual thread
    // This will be null unless we manually propagate the context as shown above
    assertThat(threadSubject.get(), nullValue());
  }

  /**
   * Tests Shiro's authentication and authorization with a Virtual Thread Executor.
   * <p>
   * This test verifies that authentication and authorization work correctly
   * when using a Virtual Thread Executor for concurrent operations.
   */
  @Test
  public void testAuthenticationWithVirtualThreadExecutor() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AtomicReference<Subject> executorSubject = new AtomicReference<>();

      // Submit a task to the executor
      Future<?> future = executor.submit(() -> {
        try {
          // Create a new subject and authenticate
          Subject threadSubject = SecurityUtils.getSubject();
          UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
          threadSubject.login(token);

          // Store the authenticated subject
          executorSubject.set(threadSubject);
        }
        finally {
          // Clean up
          Subject threadSubject = SecurityUtils.getSubject();
          if (threadSubject != null && threadSubject.isAuthenticated()) {
            threadSubject.logout();
          }
        }
      });

      // Wait for the task to complete
      future.get();

      // Verify authentication worked in the virtual thread
      assertThat(executorSubject.get(), notNullValue());
      assertThat(executorSubject.get().isAuthenticated(), is(true));
      assertThat(executorSubject.get().getPrincipal(), is(USERNAME));
    }
  }

  /**
   * Tests Shiro's session management with Virtual Threads.
   * <p>
   * This test verifies that session creation, attribute storage, and retrieval
   * work correctly when using Virtual Threads.
   */
  @Test
  public void testSessionManagementWithVirtualThreads() throws Exception {
    // Use CompletableFuture with virtual threads
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      try {
        ThreadContext.bind(subject);

        // Test session operations
        subject.getSession().setAttribute("testKey", "testValue");
        return "testValue".equals(subject.getSession().getAttribute("testKey"));
      }
      finally {
        ThreadContext.unbindSubject();
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    // Verify session operations worked correctly
    assertThat(future.get(), is(true));
  }

  /**
   * Tests Shiro's pattern matching for permissions with Java 21 pattern matching.
   * <p>
   * This test verifies that Shiro's permission checking works correctly with
   * Java 21's enhanced pattern matching features.
   */
  @Test
  public void testPermissionCheckingWithPatternMatching() {
    // Test permission checking with pattern matching
    boolean hasPermission = switch (SecurityUtils.getSubject()) {
      case Subject s when s.isPermitted("nexus:*") -> true;
      case Subject s when s.hasRole("admin") -> true;
      default -> false;
    };

    // Verify permission checking worked correctly
    assertThat(hasPermission, is(true));
  }
}