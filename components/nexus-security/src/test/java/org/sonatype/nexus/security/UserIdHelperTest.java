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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.apache.shiro.util.ThreadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.UserIdHelper.UNKNOWN;

/**
 * Tests for {@link UserIdHelper}.
 */
public class UserIdHelperTest
  extends TestSupport
{
  private ThreadState threadState;
  private Subject subject;
  
  @BeforeEach
  public void setUp() {
    // Create a mock Subject for testing
    subject = mock(Subject.class);
    when(subject.toString()).thenReturn("MockSubject");
  }
  
  @AfterEach
  public void tearDown() {
    // Unbind the subject from the current thread if it was bound
    if (threadState != null) {
      threadState.clear();
      threadState = null;
    }
  }
  
  private Subject subject(final Object principal) {
    Subject subject = mock(Subject.class);
    when(subject.getPrincipal()).thenReturn(principal);
    return subject;
  }
  
  private void bindSubject(Subject subject) {
    threadState = new SubjectThreadState(subject);
    threadState.bind();
  }

  @Test
  public void get_subject() {
    assertEquals("test", UserIdHelper.get(subject("test")));
  }

  @Test
  public void get_nullSubject() {
    assertEquals(UNKNOWN, UserIdHelper.get(null));
  }

  @Test
  public void get_nullPrincipal() {
    assertEquals(UNKNOWN, UserIdHelper.get(subject(null)));
  }
  
  /**
   * Tests that UserIdHelper.get() works correctly with the current thread's Subject.
   */
  @Test
  public void get_currentSubject() {
    Subject testSubject = subject("currentUser");
    bindSubject(testSubject);
    
    assertEquals("currentUser", UserIdHelper.get());
  }
  
  /**
   * Tests that UserIdHelper.get() returns UNKNOWN when no Subject is bound to the current thread.
   */
  @Test
  public void get_noCurrentSubject() {
    // Ensure no subject is bound
    if (threadState != null) {
      threadState.clear();
      threadState = null;
    }
    
    assertEquals(UNKNOWN, UserIdHelper.get());
  }
  
  /**
   * Tests that UserIdHelper.isSystem() correctly identifies system users.
   */
  @Test
  public void isSystem_withSystemUser() {
    Subject systemSubject = subject(UserIdHelper.SYSTEM);
    bindSubject(systemSubject);
    
    assertEquals(true, UserIdHelper.isSystem());
  }
  
  /**
   * Tests that UserIdHelper.isSystem() correctly identifies non-system users.
   */
  @Test
  public void isSystem_withNonSystemUser() {
    Subject regularSubject = subject("regularUser");
    bindSubject(regularSubject);
    
    assertEquals(false, UserIdHelper.isSystem());
  }
  
  /**
   * Tests that UserIdHelper.isUnknown() correctly identifies unknown users.
   */
  @Test
  public void isUnknown_withUnknownUser() {
    // No subject bound means unknown user
    if (threadState != null) {
      threadState.clear();
      threadState = null;
    }
    
    assertEquals(true, UserIdHelper.isUnknown());
  }
  
  /**
   * Tests that UserIdHelper.isUnknown() correctly identifies known users.
   */
  @Test
  public void isUnknown_withKnownUser() {
    Subject knownSubject = subject("knownUser");
    bindSubject(knownSubject);
    
    assertEquals(false, UserIdHelper.isUnknown());
  }
  
  /**
   * Tests that UserIdHelper.get() works correctly within a Virtual Thread.
   */
  @Test
  public void get_inVirtualThread() throws Exception {
    // Create a subject with a known principal
    Subject testSubject = subject("virtualUser");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-virtual-").factory();
    
    // Create a thread-per-task executor with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit a task that binds the subject and gets the user ID
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        // Bind the subject to this virtual thread
        ThreadState virtualThreadState = new SubjectThreadState(testSubject);
        virtualThreadState.bind();
        
        try {
          // Get the user ID
          return UserIdHelper.get();
        } finally {
          // Clean up
          virtualThreadState.clear();
        }
      }, executor);
      
      // Get the result and verify
      String userId = future.get(5, TimeUnit.SECONDS);
      assertEquals("virtualUser", userId);
    }
  }
  
  /**
   * Tests that UserIdHelper.get() returns UNKNOWN in a Virtual Thread with no bound Subject.
   */
  @Test
  public void get_inVirtualThreadWithNoSubject() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-virtual-").factory();
    
    // Create a thread-per-task executor with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit a task that gets the user ID without binding a subject
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        // Get the user ID (should be UNKNOWN since no subject is bound)
        return UserIdHelper.get();
      }, executor);
      
      // Get the result and verify
      String userId = future.get(5, TimeUnit.SECONDS);
      assertEquals(UNKNOWN, userId);
    }
  }
  
  /**
   * Tests that UserIdHelper.get() works correctly with multiple concurrent Virtual Threads.
   */
  @Test
  public void get_withMultipleConcurrentVirtualThreads() throws Exception {
    // Create subjects with different principals
    Subject subject1 = subject("user1");
    Subject subject2 = subject("user2");
    Subject subject3 = subject("user3");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("concurrent-virtual-").factory();
    
    // Create a thread-per-task executor with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks that bind different subjects and get the user ID
      CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> {
        ThreadState state = new SubjectThreadState(subject1);
        state.bind();
        try {
          // Simulate some work
          try { Thread.sleep(50); } catch (InterruptedException e) { }
          return UserIdHelper.get();
        } finally {
          state.clear();
        }
      }, executor);
      
      CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> {
        ThreadState state = new SubjectThreadState(subject2);
        state.bind();
        try {
          // Simulate some work
          try { Thread.sleep(30); } catch (InterruptedException e) { }
          return UserIdHelper.get();
        } finally {
          state.clear();
        }
      }, executor);
      
      CompletableFuture<String> future3 = CompletableFuture.supplyAsync(() -> {
        ThreadState state = new SubjectThreadState(subject3);
        state.bind();
        try {
          // Simulate some work
          try { Thread.sleep(10); } catch (InterruptedException e) { }
          return UserIdHelper.get();
        } finally {
          state.clear();
        }
      }, executor);
      
      // Get the results and verify
      assertEquals("user1", future1.get(5, TimeUnit.SECONDS));
      assertEquals("user2", future2.get(5, TimeUnit.SECONDS));
      assertEquals("user3", future3.get(5, TimeUnit.SECONDS));
    }
  }
  
  /**
   * Tests that UserIdHelper.isSystem() works correctly within a Virtual Thread.
   */
  @Test
  public void isSystem_inVirtualThread() throws Exception {
    // Create a subject with SYSTEM principal
    Subject systemSubject = subject(UserIdHelper.SYSTEM);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("system-virtual-").factory();
    
    // Create a thread-per-task executor with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit a task that binds the subject and checks if it's a system user
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        // Bind the subject to this virtual thread
        ThreadState virtualThreadState = new SubjectThreadState(systemSubject);
        virtualThreadState.bind();
        
        try {
          // Check if it's a system user
          return UserIdHelper.isSystem();
        } finally {
          // Clean up
          virtualThreadState.clear();
        }
      }, executor);
      
      // Get the result and verify
      Boolean isSystem = future.get(5, TimeUnit.SECONDS);
      assertEquals(true, isSystem);
    }
  }
}
