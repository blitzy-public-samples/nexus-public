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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.storage.config.RegexCriteriaValidator.InvalidExpressionException;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for {@link RegexCriteriaValidator} specifically validating behavior under high-concurrency
 * virtual thread execution using Java 21's virtual threads.
 * 
 * This test ensures that regex validation operates correctly when invoked by thousands of concurrent
 * virtual threads without synchronization issues or memory leaks.
 */
public class RegexCriteriaValidatorVirtualThreadTest
    extends RegexCriteriaValidatorTest
{
  private static final String VALID_EXPRESSION = "org/sonatype";
  private static final String INVALID_EXPRESSION = "hello(world";
  private static final int THREAD_COUNT = 1000;

  /**
   * Tests that valid regex expressions are correctly validated when processed by many concurrent virtual threads.
   */
  @Test
  public void testValidExpressionWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> futures = new ArrayList<>();
      
      // Submit 1000 validation tasks to virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> RegexCriteriaValidator.validate(VALID_EXPRESSION)));
      }
      
      // Verify all results are correct
      for (Future<String> future : futures) {
        assertThat(future.get(), is(VALID_EXPRESSION));
      }
    }
  }

  /**
   * Tests that invalid regex expressions consistently throw the expected exception
   * when processed by many concurrent virtual threads.
   */
  @Test
  public void testInvalidExpressionWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      AtomicInteger exceptionCount = new AtomicInteger(0);
      
      // Submit 1000 validation tasks to virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            RegexCriteriaValidator.validate(INVALID_EXPRESSION);
            return null; // Should not reach here
          } catch (InvalidExpressionException e) {
            exceptionCount.incrementAndGet();
            return null;
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Verify all tasks threw the expected exception
      assertThat(exceptionCount.get(), is(THREAD_COUNT));
    }
  }

  /**
   * Tests mixed valid and invalid regex expressions to ensure consistent behavior
   * across many concurrent virtual threads.
   */
  @Test
  public void testMixedExpressionsWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      AtomicInteger validCount = new AtomicInteger(0);
      AtomicInteger invalidCount = new AtomicInteger(0);
      
      // Submit 1000 validation tasks alternating between valid and invalid expressions
      for (int i = 0; i < THREAD_COUNT; i++) {
        final boolean useValidExpression = (i % 2 == 0);
        futures.add(executor.submit(() -> {
          try {
            String result = RegexCriteriaValidator.validate(
                useValidExpression ? VALID_EXPRESSION : INVALID_EXPRESSION);
            if (VALID_EXPRESSION.equals(result)) {
              validCount.incrementAndGet();
            }
            return null;
          } catch (InvalidExpressionException e) {
            invalidCount.incrementAndGet();
            return null;
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          // This should not happen as we're catching InvalidExpressionException inside the task
          throw new AssertionError("Unexpected exception: " + e.getCause(), e);
        }
      }
      
      // Verify counts match expectations (half valid, half invalid)
      assertThat(validCount.get(), is(THREAD_COUNT / 2));
      assertThat(invalidCount.get(), is(THREAD_COUNT / 2));
    }
  }
}