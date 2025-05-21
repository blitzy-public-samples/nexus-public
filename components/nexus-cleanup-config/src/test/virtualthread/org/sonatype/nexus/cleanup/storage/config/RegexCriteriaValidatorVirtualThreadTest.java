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
package org.sonatype.nexus.cleanup.storage.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.storage.config.RegexCriteriaValidator.InvalidExpressionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RegexCriteriaValidator} specifically focused on virtual thread execution.
 * 
 * This test validates that the RegexCriteriaValidator behaves correctly when invoked by
 * thousands of concurrent virtual threads, ensuring thread-safety and proper performance
 * under high concurrency scenarios with Java 21's virtual threads.
 */
public class RegexCriteriaValidatorVirtualThreadTest
    extends TestSupport
{
  private static final String VALID_EXPRESSION = "org/sonatype";
  private static final String INVALID_EXPRESSION = "hello(world";
  private static final int CONCURRENT_THREADS = 1000;
  
  /**
   * Tests that the RegexCriteriaValidator can handle many concurrent validations
   * using virtual threads without issues.
   */
  @Test
  @DisplayName("Should handle concurrent validations with virtual threads")
  void shouldHandleConcurrentValidationsWithVirtualThreads() throws Exception {
    // Use Java 21's virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> futures = new ArrayList<>();
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit many concurrent validation tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          String result = RegexCriteriaValidator.validate(VALID_EXPRESSION);
          successCount.incrementAndGet();
          return result;
        }));
      }
      
      // Verify all tasks completed successfully
      for (Future<String> future : futures) {
        assertEquals(VALID_EXPRESSION, future.get());
      }
      
      assertEquals(CONCURRENT_THREADS, successCount.get(), 
          "All validation tasks should complete successfully");
    }
  }
  
  /**
   * Tests that the RegexCriteriaValidator properly handles invalid expressions
   * when invoked concurrently by many virtual threads.
   */
  @Test
  @DisplayName("Should handle concurrent invalid regex validations with virtual threads")
  void shouldHandleConcurrentInvalidRegexValidationsWithVirtualThreads() throws Exception {
    // Use Java 21's virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      AtomicInteger exceptionCount = new AtomicInteger(0);
      ConcurrentMap<String, Integer> errorMessages = new ConcurrentHashMap<>();
      
      // Submit many concurrent invalid validation tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            RegexCriteriaValidator.validate(INVALID_EXPRESSION);
          }
          catch (InvalidExpressionException e) {
            exceptionCount.incrementAndGet();
            errorMessages.compute(e.getMessage(), (k, v) -> v == null ? 1 : v + 1);
          }
          return null;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Verify all tasks threw the expected exception
      assertEquals(CONCURRENT_THREADS, exceptionCount.get(), 
          "All invalid validation tasks should throw exceptions");
      
      // Verify all exceptions had the same error message
      assertEquals(1, errorMessages.size(), 
          "All exceptions should have the same error message");
      
      // Verify the error message contains the expected pattern
      String errorMessage = errorMessages.keySet().iterator().next();
      assertTrue(errorMessage.contains("Invalid regular expression pattern:"), 
          STR."Error message '{errorMessage}' should contain 'Invalid regular expression pattern:'");
    }
  }
  
  /**
   * Tests that the RegexCriteriaValidator can handle mixed valid and invalid expressions
   * when invoked concurrently by many virtual threads.
   */
  @Test
  @DisplayName("Should handle mixed valid and invalid regex validations with virtual threads")
  void shouldHandleMixedValidAndInvalidRegexValidationsWithVirtualThreads() throws Exception {
    // Use Java 21's virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      AtomicInteger validCount = new AtomicInteger(0);
      AtomicInteger invalidCount = new AtomicInteger(0);
      
      // Submit many concurrent mixed validation tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final boolean useValidExpression = i % 2 == 0;
        futures.add(executor.submit(() -> {
          try {
            String expression = useValidExpression ? VALID_EXPRESSION : INVALID_EXPRESSION;
            String result = RegexCriteriaValidator.validate(expression);
            if (useValidExpression) {
              validCount.incrementAndGet();
              assertEquals(VALID_EXPRESSION, result);
            }
          }
          catch (InvalidExpressionException e) {
            if (!useValidExpression) {
              invalidCount.incrementAndGet();
              assertTrue(e.getMessage().contains("Invalid regular expression pattern:"));
            }
          }
          return null;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Verify counts match expectations
      int expectedValidCount = CONCURRENT_THREADS / 2;
      int expectedInvalidCount = CONCURRENT_THREADS - expectedValidCount;
      
      assertEquals(expectedValidCount, validCount.get(), 
          "Valid expression count should match expected value");
      assertEquals(expectedInvalidCount, invalidCount.get(), 
          "Invalid expression count should match expected value");
    }
  }
}