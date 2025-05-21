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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.UserIdHelper.UNKNOWN;

/**
 * Tests for {@link UserIdHelper}.
 */
public class UserIdHelperTest
  extends TestSupport
{
  private Subject subject;
  
  @BeforeEach
  public void setUp() {
    // Clear any existing subject
    ThreadContext.remove();
  }
  
  @AfterEach
  public void tearDown() {
    // Clean up after each test
    ThreadContext.remove();
  }
  
  private Subject subject(final Object principal) {
    Subject subject = mock(Subject.class);
    when(subject.getPrincipal()).thenReturn(principal);
    return subject;
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
  
  @Test
  public void userIdPropagationAcrossVirtualThreads() throws Exception {
    // Set up a subject with a known principal
    subject = subject("virtualThreadUser");
    ThreadContext.bind(subject);
    
    // Latch to ensure the virtual thread completes before assertions
    CountDownLatch latch = new CountDownLatch(1);
    
    // Result holder for the user ID from the virtual thread
    final String[] virtualThreadUserId = new String[1];
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      try {
        // Get the user ID from within the virtual thread
        virtualThreadUserId[0] = UserIdHelper.get();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify that the user ID was correctly propagated to the virtual thread
    assertEquals("virtualThreadUser", virtualThreadUserId[0], 
        "User ID should be propagated across virtual threads");
  }
  
  @Test
  public void userIdNotPropagatedByDefaultAcrossVirtualThreads() throws Exception {
    // Set up a subject with a known principal
    subject = subject("mainThreadUser");
    ThreadContext.bind(subject);
    
    // Clear the thread context to simulate a fresh virtual thread without propagation
    ThreadContext.remove();
    
    // Latch to ensure the virtual thread completes before assertions
    CountDownLatch latch = new CountDownLatch(1);
    
    // Result holder for the user ID from the virtual thread
    final String[] virtualThreadUserId = new String[1];
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      try {
        // Get the user ID from within the virtual thread
        virtualThreadUserId[0] = UserIdHelper.get();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify that the user ID was not propagated to the virtual thread by default
    assertEquals(UNKNOWN, virtualThreadUserId[0], 
        "User ID should not be propagated across virtual threads by default");
  }
}