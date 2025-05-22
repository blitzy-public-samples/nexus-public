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
import java.util.stream.IntStream;

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
 * @since 3.60
 */
public class MavenAttributesVirtualThreadTest
    extends VirtualThreadTestSupport
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
    assumeVirtualThreadSupported();
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      validator = factory.getValidator();
    }
  }

  @Test
  public void testConstructorAndGettersInVirtualThread() throws Exception {
    String versionPolicy = VALID_VERSION_POLICIES[0];
    String layoutPolicy = VALID_LAYOUT_POLICIES[0];
    String contentDisposition = VALID_CONTENT_DISPOSITIONS[0];

    callVirtual(() -> {
      MavenAttributes mavenAttributes = new MavenAttributes(versionPolicy, layoutPolicy, contentDisposition);

      assertEquals(versionPolicy, mavenAttributes.getVersionPolicy());
      assertEquals(layoutPolicy, mavenAttributes.getLayoutPolicy());
      assertEquals(contentDisposition, mavenAttributes.getContentDisposition());
      return null;
    });
  }

  @Test
  public void testValidVersionPolicyInVirtualThread() throws Exception {
    ThreadFactory factory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);
    
    try {
      CompletableFuture<?>[] futures = Arrays.stream(VALID_VERSION_POLICIES)
          .map(validVersionPolicy -> CompletableFuture.supplyAsync(() -> {
            MavenAttributes attributes =
                new MavenAttributes(validVersionPolicy, VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
            assertTrue(isValid(attributes));
            return null;
          }, executor))
          .toArray(CompletableFuture[]::new);
      
      CompletableFuture.allOf(futures).join();
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  public void testValidLayoutPolicyInVirtualThread() throws Exception {
    ThreadFactory factory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);
    
    try {
      CompletableFuture<?>[] futures = Arrays.stream(VALID_LAYOUT_POLICIES)
          .map(validLayoutPolicy -> CompletableFuture.supplyAsync(() -> {
            MavenAttributes attributes =
                new MavenAttributes(VALID_VERSION_POLICIES[0], validLayoutPolicy, VALID_CONTENT_DISPOSITIONS[0]);
            assertTrue(isValid(attributes));
            return null;
          }, executor))
          .toArray(CompletableFuture[]::new);
      
      CompletableFuture.allOf(futures).join();
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  public void testValidContentDispositionInVirtualThread() throws Exception {
    ThreadFactory factory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);
    
    try {
      CompletableFuture<?>[] futures = Arrays.stream(VALID_CONTENT_DISPOSITIONS)
          .map(validContentDisposition -> CompletableFuture.supplyAsync(() -> {
            MavenAttributes attributes =
                new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], validContentDisposition);
            assertTrue(isValid(attributes));
            return null;
          }, executor))
          .toArray(CompletableFuture[]::new);
      
      CompletableFuture.allOf(futures).join();
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  public void testInvalidVersionPolicyInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes =
          new MavenAttributes("invalid", VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, VERSION_POLICY_ERROR_MSG));
      return null;
    });
  }

  @Test
  public void testInvalidLayoutPolicyInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes =
          new MavenAttributes(VALID_VERSION_POLICIES[0], "invalid", VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, LAYOUT_POLICY_ERROR_MSG));
      return null;
    });
  }

  @Test
  public void testInvalidContentDispositionInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes =
          new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], "invalid");
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, CONTENT_DISPOSITION_ERROR_MSG));
      return null;
    });
  }

  @Test
  public void testEmptyVersionPolicyInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes = new MavenAttributes("", VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
      return null;
    });
  }

  @Test
  public void testEmptyLayoutPolicyInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], "", VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
      return null;
    });
  }

  @Test
  public void testEmptyContentDispositionInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], "");
      assertFalse(isValid(attributes));
      return null;
    });
  }

  @Test
  public void testNullVersionPolicyInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes = new MavenAttributes(null, VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
      return null;
    });
  }

  @Test
  public void testNullLayoutPolicyInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], null, VALID_CONTENT_DISPOSITIONS[0]);
      assertFalse(isValid(attributes));
      assertTrue(hasMessage(attributes, EMPTY_ERROR_MSG));
      return null;
    });
  }

  @Test
  public void testNullContentDispositionInVirtualThread() throws Exception {
    callVirtual(() -> {
      MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], null);
      assertTrue(isValid(attributes));
      return null;
    });
  }
  
  @Test
  public void testConcurrentValidationInVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = newVirtualThreadExecutor("maven-attributes-validation-");
    
    try {
      // Create a mix of valid and invalid attributes to validate concurrently
      final int iterations = 100;
      CompletableFuture<?>[] futures = new CompletableFuture[iterations];
      
      for (int i = 0; i < iterations; i++) {
        final int index = i;
        futures[i] = CompletableFuture.supplyAsync(() -> {
          // Create different combinations of attributes based on the iteration index
          String versionPolicy = index % 3 == 0 ? "invalid" : VALID_VERSION_POLICIES[index % VALID_VERSION_POLICIES.length];
          String layoutPolicy = index % 5 == 0 ? "invalid" : VALID_LAYOUT_POLICIES[index % VALID_LAYOUT_POLICIES.length];
          String contentDisposition = index % 7 == 0 ? "invalid" : 
              (index % 11 == 0 ? null : VALID_CONTENT_DISPOSITIONS[index % VALID_CONTENT_DISPOSITIONS.length]);
          
          MavenAttributes attributes = new MavenAttributes(versionPolicy, layoutPolicy, contentDisposition);
          
          // Validate and verify expected results based on the inputs
          boolean valid = isValid(attributes);
          
          // The attribute should only be valid if all fields are valid
          boolean shouldBeValid = !versionPolicy.equals("invalid") && 
                                !layoutPolicy.equals("invalid") && 
                                (contentDisposition == null || !contentDisposition.equals("invalid"));
          
          assertEquals(shouldBeValid, valid, 
              "Validation result mismatch for attributes: " + versionPolicy + ", " + 
              layoutPolicy + ", " + contentDisposition);
          
          return null;
        }, executor);
      }
      
      // Wait for all validations to complete
      CompletableFuture.allOf(futures).join();
    }
    finally {
      executor.shutdown();
    }
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