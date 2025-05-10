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
package org.sonatype.nexus.repository.security.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.security.RepositoryContentSelectorPermission;
import org.sonatype.nexus.repository.security.RepositoryViewPermission;
import org.sonatype.nexus.security.BreadActions;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.selector.JexlSelector;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.selector.VariableSource;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ContentPermissionCheckerImpl} with Java 21 Virtual Threads.
 * 
 * This test class validates that the ContentPermissionChecker operates correctly
 * and thread-safely in high-concurrency scenarios using Java 21's Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
class ContentPermissionCheckerVirtualThreadTest extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  SecurityHelper securityHelper;

  @Mock
  SelectorManager selectorManager;

  @Mock
  VariableSource variableSource;

  SelectorConfiguration config;

  ContentPermissionCheckerImpl underTest;

  @BeforeEach
  void setup() {
    underTest = new ContentPermissionCheckerImpl(securityHelper, selectorManager);

    config = mock(SelectorConfiguration.class);
    when(config.getName()).thenReturn("selector");
    when(config.getDescription()).thenReturn("selector");
    when(config.getType()).thenReturn(JexlSelector.TYPE);
    when(config.getAttributes()).thenReturn(Collections.singletonMap("expression", "true"));
  }

  /**
   * Tests that view permission checks work correctly with many concurrent virtual threads.
   */
  @Test
  void testIsViewPermittedWithVirtualThreads() throws Exception {
    // Configure the mock to return true for permission checks
    when(securityHelper
        .anyPermitted(eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit tasks to check permissions from multiple virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isViewPermitted("repoName", "repoFormat", BreadActions.READ)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all permission checks succeeded
      assertEquals(VIRTUAL_THREAD_COUNT, successCount.get(), 
          "All virtual thread permission checks should succeed");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that content permission checks work correctly with many concurrent virtual threads.
   */
  @Test
  void testIsContentPermittedWithVirtualThreads() throws Exception {
    // Configure mocks to return true for permission checks
    when(selectorManager.evaluate(any(), any())).thenReturn(true);
    when(securityHelper.anyPermitted(eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName",
        Arrays.asList(BreadActions.READ))))).thenReturn(true);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit tasks to check permissions from multiple virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isContentPermitted("repoName", "repoFormat", BreadActions.READ, config, variableSource)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all permission checks succeeded
      assertEquals(VIRTUAL_THREAD_COUNT, successCount.get(), 
          "All virtual thread permission checks should succeed");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that general permission checks work correctly with many concurrent virtual threads.
   */
  @Test
  void testIsPermittedWithVirtualThreads() throws Exception {
    // Configure mocks to return true for permission checks
    when(securityHelper
        .anyPermitted(eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);
    when(selectorManager.browse()).thenReturn(Arrays.asList(config));
    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit tasks to check permissions from multiple virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isPermitted("repoName", "repoFormat", BreadActions.READ, variableSource)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all permission checks succeeded
      assertEquals(VIRTUAL_THREAD_COUNT, successCount.get(), 
          "All virtual thread permission checks should succeed");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multi-repository view permission checks work correctly with many concurrent virtual threads.
   */
  @Test
  void testIsViewPermittedMultipleRepositoriesWithVirtualThreads() throws Exception {
    // Configure mocks to return true for permission checks
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryViewPermission("repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    Set<String> repositories = Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2"));
    
    try {
      // Submit tasks to check permissions from multiple virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isViewPermitted(repositories, "repoFormat", BreadActions.READ)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all permission checks succeeded
      assertEquals(VIRTUAL_THREAD_COUNT, successCount.get(), 
          "All virtual thread permission checks should succeed");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multi-repository content permission checks work correctly with many concurrent virtual threads.
   */
  @Test
  void testIsContentPermittedMultipleRepositoriesWithVirtualThreads() throws Exception {
    // Configure mocks to return true for permission checks
    when(selectorManager.evaluate(any(), any())).thenReturn(true);
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    Set<String> repositories = Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2"));
    
    try {
      // Submit tasks to check permissions from multiple virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isContentPermitted(repositories, "repoFormat", BreadActions.READ, config, variableSource)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all permission checks succeeded
      assertEquals(VIRTUAL_THREAD_COUNT, successCount.get(), 
          "All virtual thread permission checks should succeed");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multi-repository general permission checks work correctly with many concurrent virtual threads.
   */
  @Test
  void testIsPermittedMultipleRepositoriesWithVirtualThreads() throws Exception {
    // Configure mocks to return true for permission checks
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryViewPermission("repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);
    when(selectorManager.browse()).thenReturn(Arrays.asList(config));
    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    Set<String> repositories = Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2"));
    
    try {
      // Submit tasks to check permissions from multiple virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isPermitted(repositories, "repoFormat", BreadActions.READ, variableSource)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all permission checks succeeded
      assertEquals(VIRTUAL_THREAD_COUNT, successCount.get(), 
          "All virtual thread permission checks should succeed");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that permission checks handle high concurrency with mixed results correctly.
   * This test simulates a scenario where some permission checks succeed and others fail.
   */
  @Test
  void testMixedPermissionResultsWithVirtualThreads() throws Exception {
    // Configure mocks to return alternating results based on thread index
    AtomicInteger counter = new AtomicInteger(0);
    when(securityHelper.anyPermitted(any(RepositoryViewPermission.class)))
        .thenAnswer(invocation -> counter.incrementAndGet() % 2 == 0);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit tasks to check permissions from multiple virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isViewPermitted("repoName", "repoFormat", BreadActions.READ)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify approximately half of the permission checks succeeded
      int expectedSuccesses = VIRTUAL_THREAD_COUNT / 2;
      int tolerance = VIRTUAL_THREAD_COUNT / 10; // Allow 10% tolerance
      
      assertTrue(Math.abs(successCount.get() - expectedSuccesses) <= tolerance,
          "Expected approximately " + expectedSuccesses + " successes, but got " + successCount.get());
    } finally {
      executor.shutdown();
    }
  }
}