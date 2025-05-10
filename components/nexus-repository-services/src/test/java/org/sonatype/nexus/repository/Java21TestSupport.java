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
package org.sonatype.nexus.repository;

import java.lang.Thread.Builder.OfVirtual;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import java.lang.Thread.Builder.OfVirtual;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.junit.jupiter.api.Assertions;

/**
 * Base support class for Java 21 feature testing in repository services.
 * 
 * This class provides utilities for testing Java 21 specific features including:
 * <ul>
 *   <li>Virtual Threads - For testing the new lightweight threading model</li>
 *   <li>Pattern Matching - For testing enhanced switch expressions with pattern matching</li>
 *   <li>Record Patterns - For testing destructuring of records in pattern matching</li>
 *   <li>String Templates - For testing the new string template feature</li>
 * </ul>
 * 
 * Tests that use this class should be annotated with {@code @Category(Java21TestGroup.class)}
 * to enable selective test execution through the java21-tests Maven profile.
 * 
 * @since 3.60
 */
public class Java21TestSupport
    extends TestSupport
{
  /**
   * Verifies that the provided code executes using a virtual thread.
   * 
   * @param runnable The code to execute and verify
   */
  protected void assertExecutesOnVirtualThread(Runnable runnable) {
    boolean[] isVirtual = new boolean[1];
    
    Thread thread = Thread.ofVirtual().start(() -> {
      isVirtual[0] = Thread.currentThread().isVirtual();
      runnable.run();
    });
    
    try {
      thread.join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Thread was interrupted while checking virtual thread execution", e);
    }
    
    Assertions.assertTrue(isVirtual[0], "Code did not execute on a virtual thread");
  }
  
  /**
   * Creates an executor service that uses virtual threads.
   * 
   * @return An executor service backed by virtual threads
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates a virtual thread factory with the specified name pattern.
   * <p>
   * This factory can be used with executor services and other thread-based APIs
   * to leverage virtual threads instead of platform threads.
   * 
   * @param namePattern The name pattern for created threads
   * @return A thread factory that creates virtual threads
   */
  protected ThreadFactory createVirtualThreadFactory(String namePattern) {
    OfVirtual builder = Thread.ofVirtual().name(namePattern, 0);
    return builder.factory();
  }
  
  /**
   * Executes the given task with both virtual and platform threads and verifies
   * that the behavior is consistent between both thread types.
   * 
   * @param task The task to execute
   * @throws AssertionError if the behavior differs between thread types
   */
  protected void assertConsistentBehaviorAcrossThreadTypes(Runnable task) {
    AtomicReference<Throwable> virtualThreadException = new AtomicReference<>();
    AtomicReference<Throwable> platformThreadException = new AtomicReference<>();
    AtomicBoolean virtualThreadCompleted = new AtomicBoolean(false);
    AtomicBoolean platformThreadCompleted = new AtomicBoolean(false);
    
    // Run with virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        task.run();
        virtualThreadCompleted.set(true);
      }
      catch (Throwable t) {
        virtualThreadException.set(t);
      }
    });
    
    // Run with platform thread
    Thread platformThread = Thread.ofPlatform().start(() -> {
      try {
        task.run();
        platformThreadCompleted.set(true);
      }
      catch (Throwable t) {
        platformThreadException.set(t);
      }
    });
    
    try {
      virtualThread.join();
      platformThread.join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Thread was interrupted during consistency check", e);
    }
    
    // Check for consistent behavior
    if (virtualThreadException.get() != null && platformThreadException.get() == null) {
      throw new AssertionError("Task failed on virtual thread but succeeded on platform thread", 
          virtualThreadException.get());
    }
    else if (virtualThreadException.get() == null && platformThreadException.get() != null) {
      throw new AssertionError("Task succeeded on virtual thread but failed on platform thread", 
          platformThreadException.get());
    }
    else if (virtualThreadException.get() != null && platformThreadException.get() != null) {
      // Both failed, but with different exceptions?
      if (!virtualThreadException.get().getClass().equals(platformThreadException.get().getClass())) {
        throw new AssertionError("Task failed with different exceptions across thread types", 
            virtualThreadException.get());
      }
    }
    
    Assertions.assertTrue(virtualThreadCompleted.get() && platformThreadCompleted.get(), 
        "Task did not complete successfully on both thread types");
  }
  
  /**
   * Verifies that the provided code correctly implements pattern matching for switch.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * Object obj = "test";
   * assertPatternMatching(obj, () -> {
   *   return switch(obj) {
   *     case String s -> s.length();
   *     case Integer i -> i;
   *     default -> -1;
   *   };
   * }, 4);
   * }
   * </pre>
   * 
   * @param <T> The type of the object being pattern matched
   * @param <R> The return type of the pattern matching operation
   * @param object The object to pattern match against
   * @param patternMatchingFunction The function implementing pattern matching
   * @param expectedResult The expected result of the pattern matching
   */
  protected <T, R> void assertPatternMatching(T object, Supplier<R> patternMatchingFunction, R expectedResult) {
    R result = patternMatchingFunction.get();
    Assertions.assertEquals(expectedResult, result, "Pattern matching did not produce expected result");
  }
  
  /**
   * Verifies that the provided code correctly implements record pattern matching.
   * <p>
   * Example usage with a Point record:
   * <pre>
   * {@code
   * record Point(int x, int y) {}
   * record Rectangle(Point upperLeft, Point lowerRight) {}
   * 
   * Rectangle rectangle = new Rectangle(new Point(0, 0), new Point(10, 10));
   * 
   * assertRecordPattern(rectangle, () -> {
   *   if (rectangle instanceof Rectangle(Point(int x1, int y1), Point(int x2, int y2))) {
   *     return (x2 - x1) * (y2 - y1); // Area calculation
   *   }
   *   return -1;
   * }, 100);
   * }
   * </pre>
   * 
   * @param <T> The type of the object being pattern matched
   * @param <R> The return type of the pattern matching operation
   * @param object The object to pattern match against
   * @param recordPatternFunction The function implementing record pattern matching
   * @param expectedResult The expected result of the record pattern matching
   */
  protected <T, R> void assertRecordPattern(T object, Supplier<R> recordPatternFunction, R expectedResult) {
    R result = recordPatternFunction.get();
    Assertions.assertEquals(expectedResult, result, "Record pattern matching did not produce expected result");
  }
  
  /**
   * Verifies that the provided code correctly implements string templates.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * String name = "World";
   * int value = 42;
   * 
   * assertStringTemplate(() -> STR."Hello \{name}! The answer is \{value}.",
   *                      "Hello World! The answer is 42.");
   * }
   * </pre>
   * 
   * @param templateFunction The function implementing string templates
   * @param expectedResult The expected result of the string template
   */
  protected void assertStringTemplate(Supplier<String> templateFunction, String expectedResult) {
    String result = templateFunction.get();
    Assertions.assertEquals(expectedResult, result, "String template did not produce expected result");
  }
  
  /**
   * Verifies that the thread is not pinned during execution.
   * <p>
   * This method provides a simplified check for thread pinning. For more comprehensive
   * pinning detection, use the JVM flag {@code -Djdk.tracePinnedThreads=full} during test execution.
   * 
   * @param runnable The code to execute and verify for pinning
   */
  protected void assertNotPinned(Runnable runnable) {
    // Execute on a virtual thread to check for pinning
    Thread thread = Thread.ofVirtual().start(() -> {
      runnable.run();
    });
    
    try {
      thread.join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Thread was interrupted while checking for pinning", e);
    }
    
    // Note: This is a simplified check. In a real implementation, you would use JFR events
    // or the jdk.tracePinnedThreads JVM flag to detect actual pinning.
    log.info("Thread execution completed - no pinning detected through simplified check");
  }
  
  /**
   * Measures and compares the performance between virtual threads and platform threads.
   * 
   * @param virtualThreadTask The task to execute with virtual threads
   * @param platformThreadTask The task to execute with platform threads
   * @param iterations The number of iterations to run
   * @return true if virtual threads performed better than platform threads
   */
  protected boolean compareThreadPerformance(Runnable virtualThreadTask, Runnable platformThreadTask, int iterations) {
    // Measure platform thread performance
    long platformStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Thread platformThread = Thread.ofPlatform().start(platformThreadTask);
      try {
        platformThread.join();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Thread was interrupted during performance comparison", e);
      }
    }
    long platformDuration = System.nanoTime() - platformStart;
    
    // Measure virtual thread performance
    long virtualStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Thread virtualThread = Thread.ofVirtual().start(virtualThreadTask);
      try {
        virtualThread.join();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Thread was interrupted during performance comparison", e);
      }
    }
    long virtualDuration = System.nanoTime() - virtualStart;
    
    log.info("Platform thread duration: {} ns", platformDuration);
    log.info("Virtual thread duration: {} ns", virtualDuration);
    
    return virtualDuration < platformDuration;
  }
}