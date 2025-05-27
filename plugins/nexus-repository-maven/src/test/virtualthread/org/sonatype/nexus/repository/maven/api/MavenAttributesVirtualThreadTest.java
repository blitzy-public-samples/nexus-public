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
package org.sonatype.nexus.repository.maven.api;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.sonatype.nexus.repository.maven.ContentDisposition;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenAttributes} using Java 21 Virtual Threads.
 * 
 * This test validates that MavenAttributes functionality works correctly when executed
 * in a Virtual Thread environment, ensuring thread safety of bean validation.
 */
public class MavenAttributesVirtualThreadTest extends VirtualThreadTestSupport
{
  private Validator validator;

  private static final String[] VALID_VERSION_POLICIES = Arrays.stream(VersionPolicy.values())
      .map(Enum::toString)
      .toArray(String[]::new);

  private static final String[] VALID_LAYOUT_POLICIES = Arrays.stream(LayoutPolicy.values())
      .map(Enum::toString)
      .toArray(String[]::new);

  private static final String[] VALID_CONTENT_DISPOSITIONS = Arrays.stream(ContentDisposition.values())
      .map(Enum::toString)
      .toArray(String[]::new);

  private static final String VERSION_POLICY_ERROR_MSG = "must be one of RELEASE, SNAPSHOT, MIXED";

  private static final String LAYOUT_POLICY_ERROR_MSG = "must be one of STRICT, PERMISSIVE";

  private static final String CONTENT_DISPOSITION_ERROR_MSG = "must be one of INLINE, ATTACHMENT";

  private static final String EMPTY_ERROR_MSG = "must not be empty";

  @BeforeEach
  public void setUp() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      validator = factory.getValidator();
    }
  }

  @Test
  public void testConstructorAndGettersInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      String versionPolicy = VALID_VERSION_POLICIES[0];
      String layoutPolicy = VALID_LAYOUT_POLICIES[0];
      String contentDisposition = VALID_CONTENT_DISPOSITIONS[0];

      MavenAttributes mavenAttributes = new MavenAttributes(versionPolicy, layoutPolicy, contentDisposition);

      assertEquals(versionPolicy, mavenAttributes.getVersionPolicy());
      assertEquals(layoutPolicy, mavenAttributes.getLayoutPolicy());
      assertEquals(contentDisposition, mavenAttributes.getContentDisposition());
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testValidVersionPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      for (String validVersionPolicy : VALID_VERSION_POLICIES) {
        MavenAttributes attributes =
            new MavenAttributes(validVersionPolicy, VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
        assertTrue(isValid(attributes));
      }
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testValidLayoutPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      for (String validLayoutPolicy : VALID_LAYOUT_POLICIES) {
        MavenAttributes attributes =
            new MavenAttributes(VALID_VERSION_POLICIES[0], validLayoutPolicy, VALID_CONTENT_DISPOSITIONS[0]);
        assertTrue(isValid(attributes));
      }
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testValidContentDispositionInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      for (String validContentDisposition : VALID_CONTENT_DISPOSITIONS) {
        MavenAttributes attributes =
            new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], validContentDisposition);
        assertTrue(isValid(attributes));
      }
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testInvalidVersionPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes =
          new MavenAttributes("invalid", VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, VERSION_POLICY_ERROR_MSG));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testInvalidLayoutPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes =
          new MavenAttributes(VALID_VERSION_POLICIES[0], "invalid", VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, LAYOUT_POLICY_ERROR_MSG));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testInvalidContentDispositionInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes =
          new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], "invalid");
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, CONTENT_DISPOSITION_ERROR_MSG));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testEmptyVersionPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes = new MavenAttributes("", VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testEmptyLayoutPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], "", VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testEmptyContentDispositionInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], "");
      assertFalse(isValid(attributes));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testNullVersionPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes = new MavenAttributes(null, VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testNullLayoutPolicyInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], null, VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testNullContentDispositionInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], null);
      assertTrue(isValid(attributes));
    }, createVirtualThreadExecutor());
    
    future.join();
  }

  @Test
  public void testConcurrentValidationInVirtualThreads() throws Exception {
    int concurrentTasks = 100;
    AtomicInteger validCount = new AtomicInteger(0);
    AtomicInteger invalidCount = new AtomicInteger(0);
    
    ExecutorService executor = createVirtualThreadExecutor();
    
    CompletableFuture<?>[] futures = new CompletableFuture[concurrentTasks];
    
    // Create a mix of valid and invalid attribute instances and validate them concurrently
    for (int i = 0; i < concurrentTasks; i++) {
      final int index = i;
      futures[i] = CompletableFuture.runAsync(() -> {
        if (index % 2 == 0) {
          // Create valid attributes
          MavenAttributes attributes = new MavenAttributes(
              VALID_VERSION_POLICIES[index % VALID_VERSION_POLICIES.length],
              VALID_LAYOUT_POLICIES[index % VALID_LAYOUT_POLICIES.length],
              VALID_CONTENT_DISPOSITIONS[index % VALID_CONTENT_DISPOSITIONS.length]);
          
          if (isValid(attributes)) {
            validCount.incrementAndGet();
          }
        } else {
          // Create invalid attributes
          MavenAttributes attributes = new MavenAttributes(
              "invalid-" + index,
              VALID_LAYOUT_POLICIES[index % VALID_LAYOUT_POLICIES.length],
              VALID_CONTENT_DISPOSITIONS[index % VALID_CONTENT_DISPOSITIONS.length]);
          
          if (!isValid(attributes)) {
            invalidCount.incrementAndGet();
          }
        }
      }, executor);
    }
    
    // Wait for all validation tasks to complete
    CompletableFuture.allOf(futures).join();
    
    // Verify that all validations were performed correctly
    assertEquals(concurrentTasks / 2, validCount.get(), "Expected half of the attributes to be valid");
    assertEquals(concurrentTasks / 2, invalidCount.get(), "Expected half of the attributes to be invalid");
    
    executor.shutdown();
  }

  /**
   * Creates an executor service that uses virtual threads.
   */
  private ExecutorService createVirtualThreadExecutor() {
    ThreadFactory factory = Thread.ofVirtual().factory();
    return Executors.newThreadPerTaskExecutor(factory);
  }

  private boolean isValid(final MavenAttributes attributes) {
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    return violations.isEmpty();
  }

  private boolean hasMessage(final MavenAttributes attributes, String errorMessage) {
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    for (ConstraintViolation<MavenAttributes> violation : violations) {
      if (violation.getMessage().equals(errorMessage)) {
        return true;
      }
    }
    return false;
  }
}